import type { PendingChunks } from './chunkRepository';
import type { ChunkUploadSnapshot } from './chunkUploadCoordinator';
import { ProcessingStartApiError, type ProcessingStartRequest, type ProcessingStartResponse } from './processingStartApi';

export type RecordingEndStatus = 'idle' | 'stopping' | 'persisting' | 'uploading' | 'starting' | 'retryable' | 'incomplete' | 'upload-failed' | 'failed' | 'complete';

export type RecordingEndSnapshot = {
  status: RecordingEndStatus;
  message?: string;
};

export type ProcessingAttempt = {
  request: ProcessingStartRequest;
  key: string;
};

const ATTEMPT_STORAGE_PREFIX = 'meeting-automation.processing-attempt.';
const fallbackAttempts = new Map<string, ProcessingAttempt>();

export class ProcessingAttemptStore {
  getOrCreate(sessionId: string, request: ProcessingStartRequest, createKey: () => string = createIdempotencyKey): ProcessingAttempt {
    const serialized = JSON.stringify(request);
    const stored = this.read(sessionId);
    if (stored && JSON.stringify(stored.request) === serialized && stored.key) return stored;
    const attempt = { request: { ...request }, key: createKey() };
    fallbackAttempts.set(sessionId, attempt);
    try { globalThis.sessionStorage?.setItem(this.storageKey(sessionId), JSON.stringify(attempt)); } catch { /* keep the same key in memory when browser storage is unavailable */ }
    return attempt;
  }

  clear(sessionId: string): void {
    fallbackAttempts.delete(sessionId);
    try { globalThis.sessionStorage?.removeItem(this.storageKey(sessionId)); } catch { /* cleanup is best effort */ }
  }

  private read(sessionId: string): ProcessingAttempt | null {
    try {
      const raw = globalThis.sessionStorage?.getItem(this.storageKey(sessionId));
      if (raw) {
        const value = JSON.parse(raw) as Partial<ProcessingAttempt>;
        if (value.request && typeof value.key === 'string' && value.key) {
          const attempt = value as ProcessingAttempt;
          fallbackAttempts.set(sessionId, attempt);
          return attempt;
        }
      }
    } catch { /* fall through to the per-session memory copy */ }
    return fallbackAttempts.get(sessionId) ?? null;
  }

  private storageKey(sessionId: string): string {
    return `${ATTEMPT_STORAGE_PREFIX}${encodeURIComponent(sessionId)}`;
  }
}

type RecordingEndOptions = {
  sessionId: string;
  stopRecorderAndWait: () => Promise<number>;
  waitForPersistence: () => Promise<void>;
  flushUploads: () => Promise<ChunkUploadSnapshot>;
  reconcileUploads: () => Promise<ChunkUploadSnapshot>;
  getAudioState: () => Promise<PendingChunks>;
  startProcessing: (request: ProcessingStartRequest, idempotencyKey: string) => Promise<ProcessingStartResponse>;
  clearLocalAudio: () => Promise<void>;
  navigateToProcessing: (sessionId: string) => void;
  attemptStore?: ProcessingAttemptStore;
  createIdempotencyKey?: () => string;
  onChange?: (snapshot: RecordingEndSnapshot) => void;
};

const MESSAGES = {
  stopping: '녹음을 종료하고 마지막 오디오를 저장하는 중입니다.',
  persisting: '마지막 오디오를 저장하는 중입니다.',
  uploading: '모든 오디오를 업로드하고 있습니다.',
  starting: '회의 처리를 시작하고 있습니다.',
  retryable: '처리 시작 응답을 확인할 수 없습니다. 같은 요청으로 다시 시도할 수 있습니다.',
  incomplete: '서버에서 일부 오디오를 확인하지 못했습니다. 업로드를 다시 확인했으니 처리를 다시 시도해 주세요.',
  uploadFailed: '오디오 업로드가 완료되지 않았습니다. 녹음 오디오는 브라우저에 보관되어 있습니다. 업로드를 다시 시도해 주세요.',
  failed: '처리를 시작하지 못했습니다. 녹음 오디오는 브라우저에 보관되어 있습니다. 오류를 확인해 주세요.',
  missingAudio: '처리를 시작할 저장된 오디오가 없습니다. 녹음 데이터를 확인해 주세요.',
  complete: '회의 처리를 시작했습니다.',
} as const;

export class RecordingEndCoordinator {
  private readonly options: RecordingEndOptions;
  private readonly attempts: ProcessingAttemptStore;
  private snapshot: RecordingEndSnapshot = { status: 'idle' };
  private inFlight: Promise<void> | null = null;
  private stopPromise: Promise<number> | null = null;
  private completed = false;

  constructor(options: RecordingEndOptions) {
    this.options = options;
    this.attempts = options.attemptStore ?? new ProcessingAttemptStore();
  }

  getSnapshot(): RecordingEndSnapshot { return { ...this.snapshot }; }

  start(): Promise<void> {
    if (this.completed) return Promise.resolve();
    if (this.inFlight) return this.inFlight;
    const current = this.run();
    this.inFlight = current;
    void current.finally(() => { if (this.inFlight === current) this.inFlight = null; });
    return current;
  }

  private async stopOnce(): Promise<number> {
    if (!this.stopPromise) this.stopPromise = this.options.stopRecorderAndWait();
    return this.stopPromise;
  }

  private async run(): Promise<void> {
    this.publish('stopping', MESSAGES.stopping);
    let durationMs: number;
    try {
      durationMs = await this.stopOnce();
      this.publish('persisting', MESSAGES.persisting);
      await this.options.waitForPersistence();
    } catch {
      this.publish('failed', MESSAGES.failed);
      return;
    }

    const audio = await this.flushAndCheck();
    if (!audio || !audio.session) return;
    const wholeDurationMs = Math.floor(durationMs);
    if (audio.nextSequence < 1 || !Number.isFinite(durationMs) || wholeDurationMs < 1) {
      this.publish('failed', MESSAGES.missingAudio);
      return;
    }
    const request: ProcessingStartRequest = {
      expectedChunks: audio.nextSequence,
      durationMs: wholeDurationMs,
      mimeType: audio.session.mimeType,
    };
    const attempt = this.attempts.getOrCreate(this.options.sessionId, request, this.options.createIdempotencyKey);
    this.publish('starting', MESSAGES.starting);

    try {
      const response = await this.options.startProcessing(attempt.request, attempt.key);
      if (response.sessionId !== this.options.sessionId || response.status !== 'PROCESSING') {
        this.publish('retryable', MESSAGES.retryable);
        return;
      }
    } catch (error) {
      if (getErrorCode(error) === 'AUDIO_CHUNKS_INCOMPLETE') {
        const reconciled = await this.flushAndCheck(true);
        if (reconciled) this.publish('incomplete', MESSAGES.incomplete);
        return;
      }
      this.publish(isUncertain(error) ? 'retryable' : 'failed', isUncertain(error) ? MESSAGES.retryable : MESSAGES.failed);
      return;
    }

    this.attempts.clear(this.options.sessionId);
    try { await this.options.clearLocalAudio(); } catch { /* keep local recovery audio if cleanup is unavailable */ }
    this.completed = true;
    this.publish('complete', MESSAGES.complete);
    this.options.navigateToProcessing(this.options.sessionId);
  }

  private async flushAndCheck(reconcile = false): Promise<PendingChunks | null> {
    this.publish('uploading', MESSAGES.uploading);
    try {
      const snapshot = await (reconcile ? this.options.reconcileUploads() : this.options.flushUploads());
      const audio = await this.options.getAudioState();
      if (!audio.session) {
        this.publish('upload-failed', MESSAGES.uploadFailed);
        return null;
      }
      if (snapshot.status !== 'complete' || snapshot.pendingCount !== 0 || audio.session.pendingCount !== 0 || audio.chunks.length > 0) {
        this.publish('upload-failed', snapshot.message ?? MESSAGES.uploadFailed);
        return null;
      }
      return audio;
    } catch {
      this.publish('upload-failed', MESSAGES.uploadFailed);
      return null;
    }
  }

  private publish(status: RecordingEndStatus, message: string) {
    this.snapshot = { status, message };
    this.options.onChange?.(this.getSnapshot());
  }
}

function getErrorCode(error: unknown): string | undefined {
  return (error as { code?: string } | null)?.code;
}

function isUncertain(error: unknown): boolean {
  if (error instanceof ProcessingStartApiError) return error.responseUncertain;
  const candidate = error as { responseUncertain?: boolean; status?: number } | null;
  return candidate?.responseUncertain === true || candidate?.status === 408 || candidate?.status === 429 || (candidate?.status !== undefined && candidate.status >= 500);
}

function createIdempotencyKey(): string {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

import type { ChunkRepository, StoredChunk } from './chunkRepository';

export type ChunkUploadState = 'idle' | 'uploading' | 'complete' | 'retryable' | 'failed' | 'session-not-found';

export type ChunkUploadSnapshot = {
  status: ChunkUploadState;
  totalCount: number;
  confirmedCount: number;
  pendingCount: number;
  message?: string;
};

type UploadAck = { sequence: number; received: true };
type UploadApi = {
  getReceivedSequences: (sessionId: string) => Promise<number[]>;
  putChunk: (sessionId: string, chunk: StoredChunk) => Promise<UploadAck>;
};

type Repository = Pick<ChunkRepository, 'getPending' | 'acknowledge'> & Partial<Pick<ChunkRepository, 'getRecoverable'>>;
type CoordinatorOptions = {
  repository: Repository;
  api: UploadApi;
  sleep?: (milliseconds: number) => Promise<void>;
  onChange?: (snapshot: ChunkUploadSnapshot) => void;
};

const MAX_ATTEMPTS = 5;
const BACKOFF_MS = [1_000, 2_000, 4_000, 8_000, 16_000];

export class ChunkUploadCoordinator {
  private readonly repository: Repository;
  private readonly api: UploadApi;
  private readonly sleep: (milliseconds: number) => Promise<void>;
  private readonly onChange?: (snapshot: ChunkUploadSnapshot) => void;
  private readonly queues = new Map<string, Promise<ChunkUploadSnapshot>>();
  private readonly snapshots = new Map<string, ChunkUploadSnapshot>();

  constructor(options: CoordinatorOptions) {
    this.repository = options.repository;
    this.api = options.api;
    this.sleep = options.sleep ?? ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)));
    this.onChange = options.onChange;
  }

  uploadPending(sessionId: string): Promise<ChunkUploadSnapshot> {
    return this.enqueue(sessionId, false);
  }

  retryManually(sessionId: string): Promise<ChunkUploadSnapshot> {
    return this.enqueue(sessionId, false);
  }

  reconcile(sessionId: string): Promise<ChunkUploadSnapshot> {
    return this.enqueue(sessionId, true);
  }

  getSnapshot(sessionId: string): ChunkUploadSnapshot {
    return this.snapshots.get(sessionId) ?? { status: 'idle', totalCount: 0, confirmedCount: 0, pendingCount: 0 };
  }

  private enqueue(sessionId: string, includeAcknowledged: boolean): Promise<ChunkUploadSnapshot> {
    const previous = this.queues.get(sessionId) ?? Promise.resolve(this.getSnapshot(sessionId));
    const current = previous.catch(() => this.getSnapshot(sessionId)).then(() => this.run(sessionId, includeAcknowledged)).catch((error: unknown) =>
      this.failFor(error, sessionId, 0, 0, 0));
    this.queues.set(sessionId, current);
    void current.finally(() => { if (this.queues.get(sessionId) === current) this.queues.delete(sessionId); });
    return current;
  }

  private async run(sessionId: string, includeAcknowledged: boolean): Promise<ChunkUploadSnapshot> {
    const source = includeAcknowledged ? this.repository.getRecoverable?.(sessionId) : undefined;
    const { chunks: unsorted } = await (source ?? this.repository.getPending(sessionId));
    const chunks = [...unsorted].sort((a, b) => a.sequence - b.sequence);
    const totalCount = chunks.length;
    let confirmedCount = 0;
    let pendingCount = totalCount;
    if (!totalCount) return this.publish(sessionId, { status: 'complete', totalCount, confirmedCount, pendingCount });
    this.publish(sessionId, { status: 'uploading', totalCount, confirmedCount, pendingCount });

    let receivedSequences: Set<number>;
    try {
      receivedSequences = new Set(await this.readReceivedSequences(sessionId));
    } catch (error) {
      return this.failFor(error, sessionId, totalCount, confirmedCount, pendingCount);
    }

    for (const chunk of chunks) {
      if (!receivedSequences.has(chunk.sequence)) continue;
      await this.repository.acknowledge(sessionId, chunk.sequence);
      confirmedCount += 1;
      pendingCount -= 1;
      this.publish(sessionId, { status: 'uploading', totalCount, confirmedCount, pendingCount });
    }

    for (const chunk of chunks) {
      if (receivedSequences.has(chunk.sequence)) continue;
      let acknowledged = false;
      for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt += 1) {
        try {
          const ack = await this.api.putChunk(sessionId, chunk);
          if (ack.received !== true || ack.sequence !== chunk.sequence) throw Object.assign(new Error('Invalid upload acknowledgement'), { responseUncertain: true });
          await this.repository.acknowledge(sessionId, chunk.sequence);
          confirmedCount += 1;
          pendingCount -= 1;
          acknowledged = true;
          this.publish(sessionId, { status: 'uploading', totalCount, confirmedCount, pendingCount });
          break;
        } catch (error) {
          if (isSessionNotFound(error)) return this.failFor(error, sessionId, totalCount, confirmedCount, pendingCount);
          if (!isRetryable(error)) return this.failFor(error, sessionId, totalCount, confirmedCount, pendingCount);

          // A lost/uncertain response is resolved against the server before the same chunk can be sent again.
          try {
            receivedSequences = new Set(await this.readReceivedSequences(sessionId));
          } catch (statusError) {
            return this.failFor(statusError, sessionId, totalCount, confirmedCount, pendingCount);
          }
          if (receivedSequences.has(chunk.sequence)) {
            await this.repository.acknowledge(sessionId, chunk.sequence);
            confirmedCount += 1;
            pendingCount -= 1;
            acknowledged = true;
            this.publish(sessionId, { status: 'uploading', totalCount, confirmedCount, pendingCount });
            break;
          }
          if (attempt === MAX_ATTEMPTS) return this.failFor(error, sessionId, totalCount, confirmedCount, pendingCount);
          await this.sleep(BACKOFF_MS[attempt - 1]);
        }
      }
      if (!acknowledged) return this.failFor(new Error('Upload is still pending'), sessionId, totalCount, confirmedCount, pendingCount);
    }

    return this.publish(sessionId, { status: 'complete', totalCount, confirmedCount, pendingCount: 0 });
  }

  private async readReceivedSequences(sessionId: string): Promise<number[]> {
    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt += 1) {
      try {
        return await this.api.getReceivedSequences(sessionId);
      } catch (error) {
        if (!isRetryable(error) || attempt === MAX_ATTEMPTS) throw error;
        await this.sleep(BACKOFF_MS[attempt - 1]);
      }
    }
    return [];
  }

  private failFor(error: unknown, sessionId: string, totalCount: number, confirmedCount: number, pendingCount: number): ChunkUploadSnapshot {
    const status: ChunkUploadState = isSessionNotFound(error) ? 'session-not-found' : isRetryable(error) ? 'retryable' : 'failed';
    const message = status === 'session-not-found'
      ? '세션을 서버에서 찾을 수 없습니다. 로컬 오디오는 보존되었습니다. 올바른 세션인지 확인해 주세요.'
      : status === 'retryable'
        ? '업로드가 일시 중단되었습니다. 네트워크를 확인한 뒤 다시 시도해 주세요.'
        : '오디오 업로드를 완료하지 못했습니다. 오류를 확인하고 다시 시도해 주세요.';
    return this.publish(sessionId, { status, totalCount, confirmedCount, pendingCount, message });
  }

  private publish(sessionId: string, snapshot: ChunkUploadSnapshot): ChunkUploadSnapshot {
    this.snapshots.set(sessionId, snapshot);
    this.onChange?.(snapshot);
    return snapshot;
  }
}

function isSessionNotFound(error: unknown): boolean {
  return (error as { code?: string } | null)?.code === 'SESSION_NOT_FOUND';
}

function isRetryable(error: unknown): boolean {
  const candidate = error as { status?: number; responseUncertain?: boolean; code?: string } | null;
  if (candidate?.code === 'SESSION_NOT_FOUND') return false;
  if (candidate?.status !== undefined) return candidate.status === 408 || candidate.status === 429 || candidate.status >= 500;
  return candidate?.responseUncertain === true || candidate?.code === 'NETWORK_ERROR' || error instanceof TypeError;
}

import { getApiBaseUrl } from './apiBase';
export type ProcessingStage = 'AUDIO_ASSEMBLY' | 'TRANSCRIPTION' | 'DIARIZATION' | 'MINUTES_GENERATION' | 'DRAFT_READY' | 'DRAFT_REGENERATION';
export type ProcessingStatus = 'PROCESSING' | 'REVIEW' | 'COMPLETED_WITH_WARNINGS' | 'PROCESSING_FAILED';
export type ProcessingAction = 'RETRY_PROCESSING' | 'DOWNLOAD_AUDIO' | 'FINALIZE_PROCESSING_FAILURE';
export type AudioDisposition = 'DOWNLOADED' | 'DISCARDED';

export type ProcessingSnapshot = {
  sessionId: string;
  version: number;
  status: ProcessingStatus;
  processing: { stage?: ProcessingStage; progressPercent?: number | null; errorCode?: string | null; retryable?: boolean; audioAvailable?: boolean; audioExpiresAt?: string | null };
  allowedActions: string[];
  document?: { documentId?: string; documentUrl?: string };
};

export class ProcessingApiError extends Error {
  constructor(readonly code: string, readonly status?: number, readonly responseUncertain = false) {
    super(code);
    this.name = 'ProcessingApiError';
  }
}

const stages = new Set<ProcessingStage>(['AUDIO_ASSEMBLY', 'TRANSCRIPTION', 'DIARIZATION', 'MINUTES_GENERATION', 'DRAFT_READY', 'DRAFT_REGENERATION']);
const statuses = new Set<ProcessingStatus>(['PROCESSING', 'REVIEW', 'COMPLETED_WITH_WARNINGS', 'PROCESSING_FAILED']);

export async function getProcessingStatus(sessionId: string, signal?: AbortSignal, fetchImpl: typeof fetch = fetch): Promise<ProcessingSnapshot> {
  let response: Response;
  try {
    response = await fetchImpl(`${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}`, {
      signal, headers: { Accept: 'application/json' }, cache: 'no-store',
    });
  } catch (error) {
    if (signal?.aborted) throw error;
    throw new ProcessingApiError('NETWORK_ERROR');
  }
  if (response.status === 404) throw new ProcessingApiError('SESSION_NOT_FOUND', response.status);
  if (!response.ok) throw new ProcessingApiError('HTTP_ERROR', response.status);
  try {
    const body: unknown = await response.json();
    const data = typeof body === 'object' && body !== null && 'data' in body ? (body as { data: unknown }).data : body;
    if (typeof data !== 'object' || data === null) throw new Error('invalid');
    const raw = data as Record<string, unknown>;
    const processingRaw = typeof raw.processing === 'object' && raw.processing !== null ? raw.processing as Record<string, unknown> : null;
    if (typeof raw.sessionId !== 'string' || typeof raw.version !== 'number' || !statuses.has(raw.status as ProcessingStatus) || !processingRaw) throw new Error('invalid');
    const stage = processingRaw?.stage;
    const knownStage = typeof stage === 'string' && stages.has(stage as ProcessingStage) ? stage as ProcessingStage : undefined;
    const progress = processingRaw?.progressPercent;
    const processing = {
      ...(knownStage ? { stage: knownStage } : {}),
      ...(typeof progress === 'number' ? { progressPercent: progress } : {}),
      ...(typeof processingRaw.retryable === 'boolean' ? { retryable: processingRaw.retryable } : {}),
      ...(typeof processingRaw.audioAvailable === 'boolean' ? { audioAvailable: processingRaw.audioAvailable } : {}),
      ...(typeof processingRaw.errorCode === 'string' ? { errorCode: processingRaw.errorCode } : {}),
      audioExpiresAt: typeof processingRaw.audioExpiresAt === 'string' ? processingRaw.audioExpiresAt : null,
    };
    return {
      sessionId: raw.sessionId, version: raw.version, status: raw.status as ProcessingStatus, processing,
      allowedActions: Array.isArray(raw.allowedActions) ? raw.allowedActions.filter((item): item is ProcessingAction => item === 'RETRY_PROCESSING' || item === 'DOWNLOAD_AUDIO' || item === 'FINALIZE_PROCESSING_FAILURE') : [],
      ...(typeof raw.document === 'object' && raw.document !== null ? { document: {
        ...(typeof (raw.document as Record<string, unknown>).documentId === 'string' ? { documentId: (raw.document as Record<string, unknown>).documentId as string } : {}),
        ...(typeof (raw.document as Record<string, unknown>).documentUrl === 'string' ? { documentUrl: (raw.document as Record<string, unknown>).documentUrl as string } : {}),
      } } : {}),
    };
  } catch {
    throw new ProcessingApiError('INVALID_RESPONSE', response.status);
  }
}

export async function retryProcessing(sessionId: string, version: number, idempotencyKey: string, fetchImpl: typeof fetch = fetch): Promise<{ sessionId: string; status: 'PROCESSING'; stage: string }> {
  return postAccepted(`${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}/processing/retry`, sessionId, version, idempotencyKey, undefined, fetchImpl);
}

export async function finalizeProcessingFailure(sessionId: string, version: number, audioDisposition: AudioDisposition, idempotencyKey: string, fetchImpl: typeof fetch = fetch): Promise<void> {
  await postAccepted(`${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}/processing/finalize-failure`, sessionId, version, idempotencyKey, { audioDisposition }, fetchImpl);
}

export function audioDownloadUrl(sessionId: string): string {
  return `${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}/audio/download`;
}

async function postAccepted(url: string, sessionId: string, version: number, idempotencyKey: string, body: unknown, fetchImpl: typeof fetch): Promise<{ sessionId: string; status: 'PROCESSING'; stage: string }> {
  let response: Response;
  let payload: unknown;
  try {
    response = await fetchImpl(url, {
      method: 'POST',
      headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), 'If-Match': `"${version}"`, 'Idempotency-Key': idempotencyKey },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
  } catch {
    throw new ProcessingApiError('NETWORK_ERROR', undefined, true);
  }
  try { payload = await response.json(); } catch { payload = undefined; }
  const data = typeof payload === 'object' && payload !== null && 'data' in payload ? (payload as { data?: unknown }).data : undefined;
  if (response.status !== 202) {
    const error = typeof payload === 'object' && payload !== null && 'error' in payload ? (payload as { error?: { code?: string } }).error : undefined;
    throw new ProcessingApiError(error?.code ?? 'HTTP_ERROR', response.status, response.status >= 500 || response.status === 408 || response.status === 429);
  }
  if (body !== undefined) return { sessionId, status: 'PROCESSING', stage: '' };
  if (typeof data !== 'object' || data === null || (data as Record<string, unknown>).sessionId !== sessionId || (data as Record<string, unknown>).status !== 'PROCESSING' || typeof (data as Record<string, unknown>).stage !== 'string') {
    throw new ProcessingApiError('INVALID_RESPONSE', 202, true);
  }
  return { sessionId, status: 'PROCESSING', stage: (data as Record<string, unknown>).stage as string };
}

function baseUrl(): string {
  return getApiBaseUrl();
}

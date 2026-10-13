export type ProcessingStage = 'AUDIO_ASSEMBLY' | 'TRANSCRIPTION' | 'DIARIZATION' | 'MINUTES_GENERATION' | 'DRAFT_READY';
export type ProcessingStatus = 'PROCESSING' | 'REVIEW' | 'COMPLETED_WITH_WARNINGS' | 'PROCESSING_FAILED';

export type ProcessingSnapshot = {
  sessionId: string;
  version: number;
  status: ProcessingStatus;
  processing: { stage?: ProcessingStage; progressPercent?: number | null; errorCode?: string | null; audioExpiresAt?: string | null };
  allowedActions: string[];
};

export class ProcessingApiError extends Error {
  constructor(readonly code: 'SESSION_NOT_FOUND' | 'NETWORK_ERROR' | 'INVALID_RESPONSE' | 'HTTP_ERROR', readonly status?: number) {
    super(code);
    this.name = 'ProcessingApiError';
  }
}

const stages = new Set<ProcessingStage>(['AUDIO_ASSEMBLY', 'TRANSCRIPTION', 'DIARIZATION', 'MINUTES_GENERATION', 'DRAFT_READY']);
const statuses = new Set<ProcessingStatus>(['PROCESSING', 'REVIEW', 'COMPLETED_WITH_WARNINGS', 'PROCESSING_FAILED']);

export async function getProcessingStatus(sessionId: string, signal?: AbortSignal, fetchImpl: typeof fetch = fetch): Promise<ProcessingSnapshot> {
  let response: Response;
  try {
    response = await fetchImpl(`${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}`, { signal, headers: { Accept: 'application/json' } });
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
      ...(typeof processingRaw.errorCode === 'string' ? { errorCode: processingRaw.errorCode } : {}),
      audioExpiresAt: typeof processingRaw.audioExpiresAt === 'string' ? processingRaw.audioExpiresAt : null,
    };
    return {
      sessionId: raw.sessionId, version: raw.version, status: raw.status as ProcessingStatus, processing,
      allowedActions: Array.isArray(raw.allowedActions) ? raw.allowedActions.filter((item): item is string => typeof item === 'string') : [],
    };
  } catch {
    throw new ProcessingApiError('INVALID_RESPONSE', response.status);
  }
}

function baseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
}

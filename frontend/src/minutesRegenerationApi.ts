import { parseReviewSessionData, type ReviewSession } from './reviewApi';
import { ProcessingApiError, type ProcessingSnapshot, type ProcessingStatus } from './processingApi';

export type MinutesRegenerationAccepted = {
  sessionId: string;
  status: 'PROCESSING';
  stage: 'DRAFT_REGENERATION';
  templateId: string;
};

export type MinutesRegenerationSnapshot = ProcessingSnapshot & {
  review?: ReviewSession;
  lastOperation?: ReviewSession['lastOperation'];
};

export class MinutesRegenerationApiError extends Error {
  constructor(readonly code: string, readonly status?: number, readonly outcomeUnknown = false) {
    super(code);
    this.name = 'MinutesRegenerationApiError';
  }
}

export type MinutesRegenerationRequestOptions = { timeoutMs?: number };
const DEFAULT_REGENERATION_REQUEST_TIMEOUT_MS = 15_000;

function baseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
}

function parseData(body: unknown): Record<string, unknown> {
  const data = typeof body === 'object' && body !== null && 'data' in body ? (body as { data: unknown }).data : undefined;
  if (typeof data !== 'object' || data === null) throw new Error('invalid');
  return data as Record<string, unknown>;
}

export async function regenerateMinutes(
  sessionId: string,
  version: number,
  templateId: string,
  idempotencyKey: string,
  options: MinutesRegenerationRequestOptions = {},
): Promise<MinutesRegenerationAccepted> {
  let response: Response;
  const controller = new AbortController();
  let timeoutHandle: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_resolve, reject) => {
    timeoutHandle = setTimeout(() => {
      controller.abort();
      reject(new MinutesRegenerationApiError('NETWORK_ERROR', undefined, true));
    }, options.timeoutMs ?? DEFAULT_REGENERATION_REQUEST_TIMEOUT_MS);
  });
  try {
    const request = fetch(`${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}/minutes/regenerate`, {
      method: 'POST',
      headers: {
        Accept: 'application/json', 'Content-Type': 'application/json',
        'If-Match': `"${version}"`, 'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify({ templateId }),
      signal: controller.signal,
    });
    response = await Promise.race([request, timeout]);
  } catch {
    throw new MinutesRegenerationApiError('NETWORK_ERROR', undefined, true);
  } finally {
    if (timeoutHandle !== undefined) clearTimeout(timeoutHandle);
  }
  let body: unknown;
  try { body = await response.json(); } catch { body = undefined; }
  if (!response.ok) {
    const error = typeof body === 'object' && body !== null && 'error' in body
      ? (body as { error?: { code?: unknown } }).error : undefined;
    throw new MinutesRegenerationApiError(typeof error?.code === 'string' ? error.code : 'REQUEST_FAILED', response.status,
      response.status >= 500 || response.status === 408 || response.status === 429);
  }
  try {
    const data = parseData(body);
    if (response.status !== 202 || data.sessionId !== sessionId || data.status !== 'PROCESSING'
      || data.stage !== 'DRAFT_REGENERATION' || data.templateId !== templateId) throw new Error('invalid');
    return { sessionId, status: 'PROCESSING', stage: 'DRAFT_REGENERATION', templateId };
  } catch {
    throw new MinutesRegenerationApiError('INVALID_RESPONSE', response.status, true);
  }
}

export async function getMinutesRegenerationSnapshot(sessionId: string, signal: AbortSignal): Promise<MinutesRegenerationSnapshot> {
  let response: Response;
  try {
    response = await fetch(`${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}`, {
      signal, headers: { Accept: 'application/json' },
    });
  } catch (error) {
    if (signal.aborted) throw error;
    throw new ProcessingApiError('NETWORK_ERROR');
  }
  if (response.status === 404) throw new ProcessingApiError('SESSION_NOT_FOUND', 404);
  if (!response.ok) throw new ProcessingApiError('HTTP_ERROR', response.status);
  try {
    const raw = parseData(await response.json());
    const status = raw.status as ProcessingStatus;
    if (raw.sessionId !== sessionId || typeof raw.version !== 'number'
      || !['PROCESSING', 'REVIEW', 'COMPLETED_WITH_WARNINGS', 'PROCESSING_FAILED'].includes(status)) throw new Error('invalid');
    const processingRaw = typeof raw.processing === 'object' && raw.processing !== null
      ? raw.processing as Record<string, unknown> : {};
    const processing: ProcessingSnapshot['processing'] = {
      ...(processingRaw.stage === 'DRAFT_REGENERATION' ? { stage: 'DRAFT_REGENERATION' as const } : {}),
      ...(typeof processingRaw.progressPercent === 'number' ? { progressPercent: processingRaw.progressPercent } : {}),
      ...(typeof processingRaw.errorCode === 'string' ? { errorCode: processingRaw.errorCode } : {}),
      audioExpiresAt: typeof processingRaw.audioExpiresAt === 'string' ? processingRaw.audioExpiresAt : null,
    };
    const common: MinutesRegenerationSnapshot = {
      sessionId, version: raw.version, status, processing,
      allowedActions: Array.isArray(raw.allowedActions) ? raw.allowedActions.filter((action): action is string => typeof action === 'string') : [],
    };
    if (status !== 'REVIEW') return common;
    const review = parseReviewSessionData(raw, sessionId, response.status);
    return { ...common, review, lastOperation: review.lastOperation };
  } catch {
    throw new ProcessingApiError('INVALID_RESPONSE', response.status);
  }
}

import { parseReviewSessionData, type ReviewSession } from './reviewApi';

export type PublishStatus = 'REVIEW' | 'CONFIRMED' | 'PUBLISHING' | 'DOCUMENT_SAVED' | 'DELIVERING'
  | 'COMPLETED' | 'COMPLETED_WITH_WARNINGS' | 'DOCUMENT_FAILED' | 'PROCESSING_FAILED';
export type ValidationIssue = { path: string; code: string };
export type PublishDelivery = {
  deliveryId?: string;
  channel: string;
  recipientParticipantId?: string;
  status: string;
  attemptCount?: number;
  retryable?: boolean;
  errorCode?: string;
  lastAttemptAt?: string;
};
export type PublishSnapshot = {
  sessionId: string;
  version: number;
  status: PublishStatus;
  allowedActions: string[];
  meeting?: { participantIds: string[] };
  deliveries?: PublishDelivery[];
  review?: ReviewSession;
  document?: { documentId?: string; documentUrl?: string | null };
};

export class PublishApiError extends Error {
  constructor(readonly code: string, readonly status?: number, readonly issues: ValidationIssue[] = [], readonly outcomeUnknown = false) {
    super(code);
    this.name = 'PublishApiError';
  }
}

const STATUSES = new Set<PublishStatus>([
  'REVIEW', 'CONFIRMED', 'PUBLISHING', 'DOCUMENT_SAVED', 'DELIVERING', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'DOCUMENT_FAILED', 'PROCESSING_FAILED',
]);

function baseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
}

function parseData(body: unknown): Record<string, unknown> {
  const value = typeof body === 'object' && body !== null && 'data' in body ? (body as { data: unknown }).data : null;
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('invalid');
  return value as Record<string, unknown>;
}

function parseMeeting(value: unknown): PublishSnapshot['meeting'] {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return undefined;
  const participantIds = (value as Record<string, unknown>).participantIds;
  if (!Array.isArray(participantIds) || !participantIds.every((id) => typeof id === 'string')) return undefined;
  return { participantIds: [...new Set(participantIds as string[])] };
}

function parseDeliveries(value: unknown): PublishDelivery[] | undefined {
  if (!Array.isArray(value)) return undefined;
  return value.flatMap((item): PublishDelivery[] => {
    if (typeof item !== 'object' || item === null || Array.isArray(item)) return [];
    const raw = item as Record<string, unknown>;
    if (typeof raw.channel !== 'string' || typeof raw.status !== 'string') return [];
    return [{
      ...(typeof raw.deliveryId === 'string' ? { deliveryId: raw.deliveryId } : {}),
      channel: raw.channel,
      ...(typeof raw.recipientParticipantId === 'string' ? { recipientParticipantId: raw.recipientParticipantId } : {}),
      status: raw.status,
      ...(typeof raw.attemptCount === 'number' ? { attemptCount: raw.attemptCount } : {}),
      ...(typeof raw.retryable === 'boolean' ? { retryable: raw.retryable } : {}),
      ...(typeof raw.errorCode === 'string' && /^[A-Z][A-Z0-9_]*$/.test(raw.errorCode) ? { errorCode: raw.errorCode } : {}),
      ...(typeof raw.lastAttemptAt === 'string' ? { lastAttemptAt: raw.lastAttemptAt } : {}),
    }];
  });
}

function parseError(body: unknown, status: number): PublishApiError {
  const root = typeof body === 'object' && body !== null ? body as Record<string, unknown> : {};
  const error = typeof root.error === 'object' && root.error !== null ? root.error as Record<string, unknown> : {};
  const details = typeof error.details === 'object' && error.details !== null ? error.details as Record<string, unknown> : {};
  const issues = Array.isArray(details.issues) ? details.issues.flatMap((item): ValidationIssue[] => {
    if (typeof item !== 'object' || item === null) return [];
    const value = item as Record<string, unknown>;
    return typeof value.path === 'string' && typeof value.code === 'string' ? [{ path: value.path, code: value.code }] : [];
  }) : [];
  return new PublishApiError(typeof error.code === 'string' ? error.code : 'REQUEST_FAILED', status, issues, status >= 500 || status === 408 || status === 429);
}

async function request(path: string, init: RequestInit): Promise<{ response: Response; body: unknown }> {
  let response: Response;
  try {
    response = await fetch(`${baseUrl()}${path}`, { ...init, headers: { Accept: 'application/json', ...init.headers } });
  } catch {
    throw new PublishApiError('NETWORK_ERROR', undefined, [], true);
  }
  let body: unknown;
  try { body = await response.json(); } catch { body = undefined; }
  if (!response.ok) throw parseError(body, response.status);
  return { response, body };
}

export async function confirmReview(sessionId: string, version: number, idempotencyKey: string): Promise<number> {
  const { body } = await request(`/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'If-Match': `"${version}"`, 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ confirm: true }),
  });
  try {
    const data = parseData(body);
    if (data.status !== 'CONFIRMED' || typeof data.version !== 'number' || data.version <= version) throw new Error('invalid');
    return data.version;
  } catch {
    throw new PublishApiError('INVALID_RESPONSE', undefined, [], true);
  }
}

export async function publishSession(
  sessionId: string,
  version: number,
  idempotencyKey: string,
  emailRecipientParticipantIds: string[],
  notificationEnabled: boolean,
): Promise<void> {
  const { response, body } = await request(`/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}/publish`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'If-Match': `"${version}"`, 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ emailRecipientParticipantIds, notificationEnabled }),
  });
  try {
    const data = parseData(body);
    if (response.status !== 202 || data.sessionId !== sessionId || data.status !== 'PUBLISHING') throw new Error('invalid');
  } catch {
    throw new PublishApiError('INVALID_RESPONSE', response.status, [], true);
  }
}

export async function getPublishSnapshot(sessionId: string, signal?: AbortSignal): Promise<PublishSnapshot> {
  let response: Response;
  try {
    response = await fetch(`${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}`, { signal, headers: { Accept: 'application/json' } });
  } catch {
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    throw new PublishApiError('NETWORK_ERROR');
  }
  let body: unknown;
  try { body = await response.json(); } catch { body = undefined; }
  if (!response.ok) throw parseError(body, response.status);
  try {
    const raw = parseData(body);
    if (raw.sessionId !== sessionId || typeof raw.version !== 'number' || !STATUSES.has(raw.status as PublishStatus)) throw new Error('invalid');
    const documentRaw = typeof raw.document === 'object' && raw.document !== null && !Array.isArray(raw.document)
      ? raw.document as Record<string, unknown> : undefined;
    const document = documentRaw ? {
      ...(typeof documentRaw.documentId === 'string' ? { documentId: documentRaw.documentId } : {}),
      ...(typeof documentRaw.documentUrl === 'string' || documentRaw.documentUrl === null
        ? { documentUrl: documentRaw.documentUrl as string | null } : {}),
    } : undefined;
    const meeting = parseMeeting(raw.meeting);
    const deliveries = parseDeliveries(raw.deliveries);
    const review = raw.status === 'REVIEW' ? parseReviewSessionData(raw, sessionId, response.status) : undefined;
    return {
      sessionId,
      version: raw.version,
      status: raw.status as PublishStatus,
      allowedActions: Array.isArray(raw.allowedActions) ? raw.allowedActions.filter((action): action is string => typeof action === 'string') : [],
      ...(meeting ? { meeting } : {}),
      ...(deliveries ? { deliveries } : {}),
      ...(review ? { review } : {}),
      ...(document ? { document } : {}),
    };
  } catch {
    throw new PublishApiError('INVALID_RESPONSE', response.status);
  }
}

export function createIdempotencyKey(): string {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

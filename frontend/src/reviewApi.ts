import { getApiBaseUrl } from './apiBase';
export type ReviewSpeaker = { speakerId: string; label: string; participantId: string | null; participantName?: string | null };
export type TranscriptSegment = { segmentId: string; speakerId: string; startMs: number; endMs: number; text: string };
export type StructuredMinutesActionItem = { ownerParticipantId: string | null; task: string; dueDate: string | null };
export type StructuredMinutes = {
  templateId: string;
  templateVersion: string;
  summary: string;
  discussionPoints: string[];
  decisions: string[];
  actionItems: StructuredMinutesActionItem[];
  followUps: string[];
};
export type ReviewSession = {
  sessionId: string;
  version: number;
  participantIds: string[];
  speakers: ReviewSpeaker[];
  transcript: TranscriptSegment[];
  minutes: StructuredMinutes | null;
  allowedActions: string[];
  lastOperation?: { type: string; outcome: string; requestedTemplateId?: string; errorCode?: string } | null;
};
export type SpeakerMappingInput = { speakerId: string; participantId: string | null };
export type SpeakerMappingResult = { speakerId: string; participantId: string | null; participantName: string | null };
export type SpeakerMappingUpdate = { version: number; mappings: SpeakerMappingResult[] };
export type MinutesUpdate = { version: number; minutes: StructuredMinutes };
export type ReviewMinutesRequestOptions = { timeoutMs?: number };

const DEFAULT_MINUTES_REQUEST_TIMEOUT_MS = 15_000;

class MinutesRequestTimeoutError extends Error {
  constructor(readonly status?: number) { super('Minutes update request timed out'); }
}

export class ReviewApiError extends Error {
  constructor(
    readonly code: 'SESSION_NOT_FOUND' | 'SESSION_NOT_IN_REVIEW' | 'INVALID_REVIEW' | 'REQUEST_FAILED' | 'MAPPING_FAILED' | 'MINUTES_UPDATE_FAILED',
    readonly status?: number,
    readonly outcomeUnknown = false,
    readonly apiCode?: string,
    readonly fieldErrors: string[] = [],
  ) {
    super(code);
    this.name = 'ReviewApiError';
  }
}

function baseUrl(): string {
  return getApiBaseUrl();
}

function parseData(body: unknown): Record<string, unknown> {
  const data = typeof body === 'object' && body !== null && 'data' in body ? (body as { data: unknown }).data : undefined;
  if (typeof data !== 'object' || data === null) throw new Error('invalid');
  return data as Record<string, unknown>;
}

function parseMinutes(raw: unknown): StructuredMinutes | null {
  if (raw === null || raw === undefined) return null;
  if (typeof raw !== 'object' || Array.isArray(raw)) throw new Error('invalid');
  const minutes = raw as Record<string, unknown>;
  const textList = (value: unknown) => {
    if (!Array.isArray(value) || !value.every((item) => typeof item === 'string')) throw new Error('invalid');
    return value as string[];
  };
  if (typeof minutes.templateId !== 'string' || typeof minutes.templateVersion !== 'string'
    || typeof minutes.summary !== 'string' || !Array.isArray(minutes.actionItems)) throw new Error('invalid');
  const actionItems = minutes.actionItems.map((rawItem): StructuredMinutesActionItem => {
    if (typeof rawItem !== 'object' || rawItem === null || Array.isArray(rawItem)) throw new Error('invalid');
    const item = rawItem as Record<string, unknown>;
    if ((item.ownerParticipantId !== null && typeof item.ownerParticipantId !== 'string')
      || typeof item.task !== 'string'
      || (item.dueDate !== null && typeof item.dueDate !== 'string')) throw new Error('invalid');
    return { ownerParticipantId: item.ownerParticipantId, task: item.task, dueDate: item.dueDate };
  });
  return {
    templateId: minutes.templateId,
    templateVersion: minutes.templateVersion,
    summary: minutes.summary,
    discussionPoints: textList(minutes.discussionPoints),
    decisions: textList(minutes.decisions),
    actionItems,
    followUps: textList(minutes.followUps),
  };
}

function parseError(body: unknown): { apiCode?: string; fieldErrors: string[] } {
  if (typeof body !== 'object' || body === null || !('error' in body)) return { fieldErrors: [] };
  const error = (body as { error: unknown }).error;
  if (typeof error !== 'object' || error === null) return { fieldErrors: [] };
  const value = error as Record<string, unknown>;
  const allowedFields = new Set(['templateId', 'templateVersion', 'summary', 'discussionPoints', 'decisions', 'actionItems', 'followUps', 'request', 'If-Match']);
  const details = typeof value.details === 'object' && value.details !== null ? value.details as Record<string, unknown> : undefined;
  const fieldErrors = Array.isArray(details?.fieldErrors)
    ? details.fieldErrors.flatMap((item) => {
      if (typeof item !== 'object' || item === null) return [];
      const field = (item as Record<string, unknown>).field;
      return typeof field === 'string' && allowedFields.has(field) ? [field] : [];
    })
    : [];
  return { apiCode: typeof value.code === 'string' ? value.code : undefined, fieldErrors };
}

export async function getReviewSession(sessionId: string): Promise<ReviewSession> {
  let response: Response;
  try {
    response = await fetch(`${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}`, { headers: { Accept: 'application/json' } });
  } catch {
    throw new ReviewApiError('REQUEST_FAILED', undefined, true);
  }
  if (response.status === 404) throw new ReviewApiError('SESSION_NOT_FOUND', 404);
  if (!response.ok) throw new ReviewApiError('REQUEST_FAILED', response.status);
  try {
    return parseReviewSessionData(parseData(await response.json()), sessionId, response.status);
  } catch (error) {
    if (error instanceof ReviewApiError) throw error;
    throw new ReviewApiError('INVALID_REVIEW', response.status);
  }
}

export function parseReviewSessionData(raw: Record<string, unknown>, sessionId: string, statusCode?: number): ReviewSession {
  try {
    const meeting = typeof raw.meeting === 'object' && raw.meeting !== null ? raw.meeting as Record<string, unknown> : null;
    if (raw.sessionId !== sessionId) throw new Error('invalid');
    if (raw.status !== 'REVIEW') throw new ReviewApiError('SESSION_NOT_IN_REVIEW', statusCode);
    if (!meeting || !Array.isArray(meeting.participantIds)
      || !meeting.participantIds.every((id) => typeof id === 'string') || !Array.isArray(raw.speakers)) throw new Error('invalid');
    const speakers = raw.speakers.map((speaker): ReviewSpeaker => {
      if (typeof speaker !== 'object' || speaker === null) throw new Error('invalid');
      const value = speaker as Record<string, unknown>;
      if (typeof value.speakerId !== 'string' || typeof value.label !== 'string'
        || (value.participantId !== null && typeof value.participantId !== 'string')) throw new Error('invalid');
      return { speakerId: value.speakerId, label: value.label, participantId: value.participantId };
    });
    if (new Set(speakers.map(({ speakerId }) => speakerId)).size !== speakers.length) throw new Error('invalid');
    const transcript = Array.isArray(raw.transcript) ? raw.transcript.map((segment): TranscriptSegment => {
      if (typeof segment !== 'object' || segment === null) throw new Error('invalid');
      const value = segment as Record<string, unknown>;
      if (typeof value.segmentId !== 'string' || typeof value.speakerId !== 'string' || typeof value.startMs !== 'number'
        || typeof value.endMs !== 'number' || typeof value.text !== 'string') throw new Error('invalid');
      return { segmentId: value.segmentId, speakerId: value.speakerId, startMs: value.startMs, endMs: value.endMs, text: value.text };
    }) : [];
    const operationRaw = typeof raw.lastOperation === 'object' && raw.lastOperation !== null
      ? raw.lastOperation as Record<string, unknown> : null;
    const lastOperation = operationRaw && typeof operationRaw.type === 'string' && typeof operationRaw.outcome === 'string'
      ? {
        type: operationRaw.type,
        outcome: operationRaw.outcome,
        ...(typeof operationRaw.requestedTemplateId === 'string' ? { requestedTemplateId: operationRaw.requestedTemplateId } : {}),
        ...(typeof operationRaw.errorCode === 'string' ? { errorCode: operationRaw.errorCode } : {}),
      }
      : null;
    return {
      sessionId, version: typeof raw.version === 'number' ? raw.version : 0,
      participantIds: meeting.participantIds as string[], speakers, transcript,
      minutes: parseMinutes(raw.minutes),
      allowedActions: Array.isArray(raw.allowedActions) ? raw.allowedActions.filter((action): action is string => typeof action === 'string') : [],
      lastOperation,
    };
  } catch (error) {
    if (error instanceof ReviewApiError) throw error;
    throw new ReviewApiError('INVALID_REVIEW', statusCode);
  }
}

export async function updateReviewMinutes(
  sessionId: string,
  version: number,
  minutes: StructuredMinutes,
  fetchImpl: typeof fetch = fetch,
  options: ReviewMinutesRequestOptions = {},
): Promise<MinutesUpdate> {
  let response: Response;
  let body: unknown;
  try {
    ({ response, body } = await requestAndRead(fetchImpl, `${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}/minutes`, {
      method: 'PUT',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'If-Match': `"${version}"` },
      body: JSON.stringify(minutes),
    }, options.timeoutMs ?? DEFAULT_MINUTES_REQUEST_TIMEOUT_MS));
  } catch (error) {
    const status = error instanceof MinutesRequestTimeoutError ? error.status : undefined;
    throw new ReviewApiError('MINUTES_UPDATE_FAILED', status, true);
  }
  if (!response.ok) {
    const parsed = parseError(body);
    const outcomeUnknown = response.status >= 500 || response.status === 408 || response.status === 429;
    throw new ReviewApiError('MINUTES_UPDATE_FAILED', response.status, outcomeUnknown, parsed.apiCode, parsed.fieldErrors);
  }
  try {
    const raw = parseData(body);
    const updatedMinutes = parseMinutes(raw.minutes);
    if (typeof raw.version !== 'number' || updatedMinutes === null) throw new Error('invalid');
    return { version: raw.version, minutes: updatedMinutes };
  } catch {
    throw new ReviewApiError('MINUTES_UPDATE_FAILED', response.status, true);
  }
}

async function requestAndRead(
  fetchImpl: typeof fetch,
  input: RequestInfo | URL,
  init: RequestInit,
  timeoutMs: number,
): Promise<{ response: Response; body: unknown }> {
  const controller = new AbortController();
  let response: Response | undefined;
  let timeoutTriggered = false;
  let timeoutHandle: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_resolve, reject) => {
    timeoutHandle = setTimeout(() => {
      timeoutTriggered = true;
      reject(new MinutesRequestTimeoutError(response?.status));
      controller.abort();
    }, timeoutMs);
  });
  const request = (async () => {
    try {
      response = await fetchImpl(input, { ...init, signal: controller.signal });
      const body = await response.json();
      return { response, body };
    } catch (error) {
      if (timeoutTriggered) throw new MinutesRequestTimeoutError(response?.status);
      if (response) return { response, body: undefined };
      throw error;
    }
  })();
  try {
    return await Promise.race([request, timeout]);
  } finally {
    if (timeoutHandle !== undefined) clearTimeout(timeoutHandle);
  }
}

export async function updateSpeakerMappings(sessionId: string, version: number, mappings: SpeakerMappingInput[]): Promise<SpeakerMappingUpdate> {
  let response: Response;
  try {
    response = await fetch(`${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}/speaker-mappings`, {
      method: 'PUT',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'If-Match': `"${version}"` },
      body: JSON.stringify({ mappings }),
    });
  } catch {
    throw new ReviewApiError('MAPPING_FAILED', undefined, true);
  }
  if (!response.ok) throw new ReviewApiError('MAPPING_FAILED', response.status, false);
  try {
    const raw = parseData(await response.json());
    if (typeof raw.version !== 'number' || !Array.isArray(raw.mappings)) throw new Error('invalid');
    const results = raw.mappings.map((item): SpeakerMappingResult => {
      if (typeof item !== 'object' || item === null) throw new Error('invalid');
      const value = item as Record<string, unknown>;
      if (typeof value.speakerId !== 'string' || (value.participantId !== null && typeof value.participantId !== 'string')
        || (value.participantName !== null && typeof value.participantName !== 'string')) throw new Error('invalid');
      return { speakerId: value.speakerId, participantId: value.participantId, participantName: value.participantName };
    });
    if (results.length !== mappings.length || results.some((item, index) => item.speakerId !== mappings[index]?.speakerId)) throw new Error('invalid');
    return { version: raw.version, mappings: results };
  } catch {
    throw new ReviewApiError('MAPPING_FAILED', response.status, true);
  }
}

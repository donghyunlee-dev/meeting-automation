import { getApiBaseUrl } from './apiBase';
import { apiFetch } from './apiFetch';
import { normalizeApiError, type SafeApiError } from './lib/api/normalizeApiError';

export type MeetingParticipantSummary = { id: string; name: string };
export type MeetingSummary = {
  documentId: string;
  title: string;
  meetingAt: string;
  participants: MeetingParticipantSummary[];
  documentUrl: string | null;
};

export class MeetingListApiError extends Error {
  readonly code: string;

  constructor(readonly safe: SafeApiError) {
    super(safe.message);
    this.code = safe.code;
    this.name = 'MeetingListApiError';
  }
}

const pendingMeetingLists = new WeakMap<typeof fetch, Map<string, Promise<MeetingSummary[]>>>();

function baseUrl(): string {
  return getApiBaseUrl();
}

function isMeetingSummary(value: unknown): value is MeetingSummary {
  if (typeof value !== 'object' || value === null) return false;
  const item = value as Partial<MeetingSummary>;
  return typeof item.documentId === 'string'
    && typeof item.title === 'string'
    && typeof item.meetingAt === 'string'
    && Array.isArray(item.participants)
    && item.participants.every((participant) => typeof participant?.id === 'string' && typeof participant?.name === 'string')
    && (typeof item.documentUrl === 'string' || item.documentUrl === null);
}

export function listMeetings(limit?: number): Promise<MeetingSummary[]> {
  const query = limit === undefined ? '' : `?limit=${encodeURIComponent(String(limit))}`;
  const url = `${baseUrl()}/api/v1/meetings${query}`;
  const fetchImpl = fetch;
  let requestsForClient = pendingMeetingLists.get(fetchImpl);
  if (!requestsForClient) {
    requestsForClient = new Map<string, Promise<MeetingSummary[]>>();
    pendingMeetingLists.set(fetchImpl, requestsForClient);
  }
  const pending = requestsForClient.get(url);
  if (pending) return pending;

  const request = requestMeetingList(url, fetchImpl).finally(() => {
    if (requestsForClient?.get(url) === request) requestsForClient.delete(url);
  });
  requestsForClient.set(url, request);
  return request;
}

async function requestMeetingList(url: string, fetchImpl: typeof fetch): Promise<MeetingSummary[]> {
  try {
    const response = await apiFetch(url, { headers: { Accept: 'application/json' } }, {}, fetchImpl);
    const body: unknown = await response.json();
    if (!response.ok || typeof body !== 'object' || body === null || !('data' in body)) {
      throw new MeetingListApiError(normalizeApiError(body, response.status));
    }
    const data = (body as { data?: { items?: unknown } }).data;
    if (!data || !Array.isArray(data.items) || !data.items.every(isMeetingSummary)) {
      throw new MeetingListApiError(normalizeApiError({ error: { code: 'MEETING_LIST_FAILED' } }, response.status));
    }
    return data.items;
  } catch (error) {
    if (error instanceof MeetingListApiError) throw error;
    throw new MeetingListApiError(normalizeApiError({ error: { code: 'NETWORK_ERROR' } }));
  }
}

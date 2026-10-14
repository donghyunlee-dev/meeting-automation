export type MeetingParticipantSummary = { id: string; name: string };
export type MeetingSummary = {
  documentId: string;
  title: string;
  meetingAt: string;
  participants: MeetingParticipantSummary[];
  documentUrl: string | null;
};

export class MeetingListApiError extends Error {
  readonly code = 'MEETING_LIST_FAILED';

  constructor() {
    super('회의 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.');
    this.name = 'MeetingListApiError';
  }
}

const pendingMeetingLists = new Map<string, Promise<MeetingSummary[]>>();

function baseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
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
  const pending = pendingMeetingLists.get(url);
  if (pending) return pending;

  const request = requestMeetingList(url).finally(() => {
    if (pendingMeetingLists.get(url) === request) pendingMeetingLists.delete(url);
  });
  pendingMeetingLists.set(url, request);
  return request;
}

async function requestMeetingList(url: string): Promise<MeetingSummary[]> {
  try {
    const response = await fetch(url, { headers: { Accept: 'application/json' } });
    const body: unknown = await response.json();
    if (!response.ok || typeof body !== 'object' || body === null || !('data' in body)) throw new MeetingListApiError();
    const data = (body as { data?: { items?: unknown } }).data;
    if (!data || !Array.isArray(data.items) || !data.items.every(isMeetingSummary)) throw new MeetingListApiError();
    return data.items;
  } catch (error) {
    if (error instanceof MeetingListApiError) throw error;
    throw new MeetingListApiError();
  }
}

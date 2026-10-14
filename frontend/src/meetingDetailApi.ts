import { normalizeApiError, type SafeApiError } from './lib/api/normalizeApiError';

export type MeetingDetail = {
  documentId: string;
  title: string;
  meetingAt: string;
  participants: { id: string; name: string }[];
  minutes: {
    summary: string;
    discussionPoints: string[];
    decisions: string[];
    actionItems: { ownerParticipantId: string | null; task: string; dueDate: string | null }[];
    followUps: string[];
  };
  transcript: { segmentId: string; speakerId: string; startMs: number; endMs: number; text: string }[];
  documentUrl: string | null;
};

export class MeetingDetailApiError extends Error {
  readonly code: string;

  constructor(code: 'MEETING_NOT_FOUND' | 'MEETING_DETAIL_FAILED', readonly safe: SafeApiError = normalizeApiError({ error: { code } }, code === 'MEETING_NOT_FOUND' ? 404 : undefined)) {
    super(safe.message);
    this.code = code;
    this.name = 'MeetingDetailApiError';
  }
}

function baseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
}

function isMeetingDetail(value: unknown): value is MeetingDetail {
  if (!value || typeof value !== 'object') return false;
  const detail = value as Partial<MeetingDetail>;
  return typeof detail.documentId === 'string'
    && typeof detail.title === 'string'
    && typeof detail.meetingAt === 'string'
    && Array.isArray(detail.participants)
    && typeof detail.minutes === 'object' && detail.minutes !== null
    && Array.isArray(detail.transcript)
    && (typeof detail.documentUrl === 'string' || detail.documentUrl === null);
}

export async function getMeetingDetail(documentId: string): Promise<MeetingDetail> {
  try {
    const response = await fetch(`${baseUrl()}/api/v1/meetings/${encodeURIComponent(documentId)}`, { headers: { Accept: 'application/json' } });
    const body: unknown = await response.json();
    const code = body && typeof body === 'object' && 'error' in body
      && (body.error as { code?: unknown } | null)?.code === 'MEETING_NOT_FOUND' ? 'MEETING_NOT_FOUND' : null;
    if (response.status === 404 && code === 'MEETING_NOT_FOUND') {
      throw new MeetingDetailApiError('MEETING_NOT_FOUND', normalizeApiError(body, response.status));
    }
    if (!response.ok || !body || typeof body !== 'object' || !('data' in body)) {
      throw new MeetingDetailApiError('MEETING_DETAIL_FAILED', normalizeApiError(body, response.status));
    }
    const data = (body as { data?: unknown }).data;
    if (!isMeetingDetail(data)) throw new MeetingDetailApiError('MEETING_DETAIL_FAILED');
    return data;
  } catch (error) {
    if (error instanceof MeetingDetailApiError) throw error;
    throw new MeetingDetailApiError('MEETING_DETAIL_FAILED');
  }
}

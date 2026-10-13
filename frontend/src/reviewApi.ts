export type ReviewSpeaker = { speakerId: string; label: string; participantId: string | null };
export type ReviewSession = { sessionId: string; participantIds: string[]; speakers: ReviewSpeaker[] };

export class ReviewApiError extends Error {
  constructor(readonly code: 'SESSION_NOT_FOUND' | 'INVALID_REVIEW' | 'REQUEST_FAILED') {
    super(code);
    this.name = 'ReviewApiError';
  }
}

function baseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
}

export async function getReviewSession(sessionId: string): Promise<ReviewSession> {
  let response: Response;
  try {
    response = await fetch(`${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}`, { headers: { Accept: 'application/json' } });
  } catch {
    throw new ReviewApiError('REQUEST_FAILED');
  }
  if (response.status === 404) throw new ReviewApiError('SESSION_NOT_FOUND');
  if (!response.ok) throw new ReviewApiError('REQUEST_FAILED');
  try {
    const body: unknown = await response.json();
    const data = typeof body === 'object' && body !== null && 'data' in body ? (body as { data: unknown }).data : undefined;
    if (typeof data !== 'object' || data === null) throw new Error('invalid');
    const raw = data as Record<string, unknown>;
    const meeting = typeof raw.meeting === 'object' && raw.meeting !== null ? raw.meeting as Record<string, unknown> : null;
    if (raw.sessionId !== sessionId || raw.status !== 'REVIEW' || !meeting || !Array.isArray(meeting.participantIds)
      || !meeting.participantIds.every((id) => typeof id === 'string') || !Array.isArray(raw.speakers)) throw new Error('invalid');
    const speakers = raw.speakers.map((speaker): ReviewSpeaker => {
      if (typeof speaker !== 'object' || speaker === null) throw new Error('invalid');
      const value = speaker as Record<string, unknown>;
      if (typeof value.speakerId !== 'string' || typeof value.label !== 'string'
        || (value.participantId !== null && typeof value.participantId !== 'string')) throw new Error('invalid');
      return { speakerId: value.speakerId, label: value.label, participantId: value.participantId };
    });
    if (new Set(speakers.map(({ speakerId }) => speakerId)).size !== speakers.length) throw new Error('invalid');
    return { sessionId, participantIds: meeting.participantIds as string[], speakers };
  } catch {
    throw new ReviewApiError('INVALID_REVIEW');
  }
}

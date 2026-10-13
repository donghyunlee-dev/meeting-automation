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
};
export type SpeakerMappingInput = { speakerId: string; participantId: string | null };
export type SpeakerMappingResult = { speakerId: string; participantId: string | null; participantName: string | null };
export type SpeakerMappingUpdate = { version: number; mappings: SpeakerMappingResult[] };

export class ReviewApiError extends Error {
  constructor(readonly code: 'SESSION_NOT_FOUND' | 'INVALID_REVIEW' | 'REQUEST_FAILED' | 'MAPPING_FAILED', readonly status?: number, readonly outcomeUnknown = false) {
    super(code);
    this.name = 'ReviewApiError';
  }
}

function baseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
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
    const raw = parseData(await response.json());
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
    const transcript = Array.isArray(raw.transcript) ? raw.transcript.map((segment): TranscriptSegment => {
      if (typeof segment !== 'object' || segment === null) throw new Error('invalid');
      const value = segment as Record<string, unknown>;
      if (typeof value.segmentId !== 'string' || typeof value.speakerId !== 'string' || typeof value.startMs !== 'number'
        || typeof value.endMs !== 'number' || typeof value.text !== 'string') throw new Error('invalid');
      return { segmentId: value.segmentId, speakerId: value.speakerId, startMs: value.startMs, endMs: value.endMs, text: value.text };
    }) : [];
    return {
      sessionId, version: typeof raw.version === 'number' ? raw.version : 0,
      participantIds: meeting.participantIds as string[], speakers, transcript,
      minutes: parseMinutes(raw.minutes),
      allowedActions: Array.isArray(raw.allowedActions) ? raw.allowedActions.filter((action): action is string => typeof action === 'string') : [],
    };
  } catch {
    throw new ReviewApiError('INVALID_REVIEW', response.status);
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

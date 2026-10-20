import { getApiBaseUrl } from './apiBase';
import { apiFetch } from './apiFetch';
import type { Participant } from './participantsApi';

export type MeetingTemplate = { id: string; name: string; version: string };
export type CompanyConfig = { id: string; name: string; timezone: string };
export type DocumentAppConfig = {
  company: CompanyConfig;
  document?: { provider: string; configured: boolean; connectionVersion: number; rootUrl: string };
  email?: { enabled: boolean; configured: boolean };
  notification?: { provider: string | null; enabled: boolean };
  setup?: { status: string; required: boolean; canCreateMeeting: boolean; operationId: string | null };
};
export type MeetingSessionAttempt = {
  title: string;
  meetingDate: string;
  templateId: string;
  participantIds: readonly string[];
  timezone: string;
  documentConnectionVersion: number;
  recoveryKey: string;
  idempotencyKey: string;
};
export type MeetingSession = {
  sessionId: string;
  version: number;
  status: string;
  uploadPolicy: { chunkDurationSeconds: number; maxChunkBytes: number; acceptedMimeTypes: string[] };
};

type ApiErrorBody = { error?: { code?: string } };

export class NewMeetingApiError extends Error {
  constructor(readonly code: string, readonly responseUncertain = false) {
    super('요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    this.name = 'NewMeetingApiError';
  }
}

export async function createMeetingSession(attempt: MeetingSessionAttempt): Promise<MeetingSession> {
  let response: Response;
  try {
    response = await apiFetch(`${baseUrl()}/api/v1/meeting-sessions`, {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'Idempotency-Key': attempt.idempotencyKey },
      body: JSON.stringify({
        title: attempt.title,
        meetingDate: attempt.meetingDate,
        templateId: attempt.templateId,
        participantIds: [...attempt.participantIds],
        timezone: attempt.timezone,
        documentConnectionVersion: attempt.documentConnectionVersion,
        recoveryKey: attempt.recoveryKey,
      }),
    });
  } catch {
    throw new NewMeetingApiError('NETWORK_ERROR', true);
  }

  let body: { data?: MeetingSession; error?: { code?: string } } | undefined;
  try { body = await response.json() as typeof body; } catch { /* invalid response is handled below */ }
  if (!response.ok) throw new NewMeetingApiError(body?.error?.code ?? 'REQUEST_FAILED', response.status >= 500);
  if (!body?.data?.sessionId || !body.data.uploadPolicy) throw new NewMeetingApiError('INVALID_SESSION_RESPONSE', true);
  return body.data;
}

function baseUrl(): string {
  return getApiBaseUrl();
}

async function get<T>(path: string): Promise<T> {
  let response: Response;
  try {
    response = await apiFetch(`${baseUrl()}/api/v1${path}`, { headers: { Accept: 'application/json' } });
  } catch {
    throw new NewMeetingApiError('NETWORK_ERROR');
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch {
    body = undefined;
  }
  if (!response.ok) {
    const apiError = body as ApiErrorBody | undefined;
    throw new NewMeetingApiError(apiError?.error?.code ?? 'REQUEST_FAILED');
  }
  return body as T;
}

export async function getCompanyConfig(): Promise<CompanyConfig> {
  return (await getDocumentAppConfig()).company;
}

export async function getDocumentAppConfig(): Promise<DocumentAppConfig> {
  const result = await get<{ data: DocumentAppConfig }>('/app-config');
  if (!result.data?.company?.timezone) throw new NewMeetingApiError('INVALID_APP_CONFIG');
  return result.data;
}

export async function listMeetingTemplates(): Promise<MeetingTemplate[]> {
  const result = await get<{ data: { items: MeetingTemplate[] } }>('/templates');
  if (!Array.isArray(result.data?.items)) throw new NewMeetingApiError('INVALID_TEMPLATE_LIST');
  return result.data.items;
}

export async function listMeetingParticipants(): Promise<Participant[]> {
  const result = await get<{ data: { items: Participant[] } }>('/participants');
  if (!Array.isArray(result.data?.items)) throw new NewMeetingApiError('INVALID_PARTICIPANT_LIST');
  return result.data.items;
}

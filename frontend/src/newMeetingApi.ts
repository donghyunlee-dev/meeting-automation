import type { Participant } from './participantsApi';

export type MeetingTemplate = { id: string; name: string; version: string };
export type CompanyConfig = { id: string; name: string; timezone: string };
export type DocumentAppConfig = { company: CompanyConfig; document?: { provider: string; configured: boolean; connectionVersion: number; rootUrl: string }; setup?: { status: string; required: boolean; canCreateMeeting: boolean; operationId: string | null } };

type ApiErrorBody = { error?: { code?: string } };

export class NewMeetingApiError extends Error {
  constructor(readonly code: string) {
    super('요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    this.name = 'NewMeetingApiError';
  }
}

function baseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
}

async function get<T>(path: string): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${baseUrl()}/api/v1${path}`, { headers: { Accept: 'application/json' } });
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

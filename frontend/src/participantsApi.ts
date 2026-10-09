export type Participant = { id: string; name: string; email: string };
export type ParticipantInput = { name: string; email: string };
export type ParticipantPatch = Partial<ParticipantInput>;
export type ParticipantField = keyof ParticipantInput;

type ApiErrorBody = {
  error?: {
    code?: string;
    details?: { fieldErrors?: Array<{ field?: string; code?: string }> };
  };
};

export class ParticipantApiError extends Error {
  readonly code: string;
  readonly fieldErrors: Partial<Record<ParticipantField, string>>;

  constructor(code: string, fieldErrors: Partial<Record<ParticipantField, string>> = {}) {
    super(safeMessage(code));
    this.name = 'ParticipantApiError';
    this.code = code;
    this.fieldErrors = fieldErrors;
  }
}

function safeMessage(code: string): string {
  if (code === 'PARTICIPANT_NOT_FOUND') return '이 참석자는 목록에서 사라졌습니다. 목록을 새로고침해 주세요.';
  if (code === 'VALIDATION_FAILED') return '입력 내용을 확인해 주세요.';
  if (code === 'PARTICIPANT_LIST_FAILED') return '참석자 목록을 불러오지 못했습니다.';
  return '요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.';
}

function baseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${baseUrl()}/api/v1${path}`, {
      ...init,
      headers: { Accept: 'application/json', ...init?.headers },
    });
  } catch {
    throw new ParticipantApiError('NETWORK_ERROR');
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch {
    body = undefined;
  }
  if (!response.ok) {
    const error = body as ApiErrorBody | undefined;
    const code = error?.error?.code || 'REQUEST_FAILED';
    const fieldErrors: Partial<Record<ParticipantField, string>> = {};
    for (const fieldError of error?.error?.details?.fieldErrors ?? []) {
      if (fieldError.field === 'name' || fieldError.field === 'email') {
        fieldErrors[fieldError.field] = fieldError.code || 'INVALID';
      }
    }
    throw new ParticipantApiError(code, fieldErrors);
  }
  return body as T;
}

export async function listParticipants(): Promise<Participant[]> {
  const result = await request<{ data: { items: Participant[] } }>('/participants');
  return result.data.items;
}

export async function createParticipant(input: ParticipantInput, key: string): Promise<Participant> {
  const result = await request<{ data: Participant }>('/participants', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
    body: JSON.stringify(input),
  });
  return result.data;
}

export async function updateParticipant(id: string, patch: ParticipantPatch): Promise<Participant> {
  const result = await request<{ data: Participant }>(`/participants/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  return result.data;
}

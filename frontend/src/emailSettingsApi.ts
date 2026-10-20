import { getApiBaseUrl } from './apiBase';
import { apiFetch } from './apiFetch';
import { clearStoredEmailSettings, loadStoredEmailSettings, saveStoredEmailSettings, type StoredEmailSettings } from './emailSettingsStorage';

export type EmailSettings = {
  configured: boolean;
  connectionVerified: boolean;
  maskedClientId: string | null;
  maskedClientSecret: string | null;
  maskedRefreshToken: string | null;
  senderAddress: string | null;
  verifiedAt: string | null;
};

export class EmailSettingsApiError extends Error {
  constructor(readonly code: string) {
    super(code === 'EMAIL_SETTINGS_INVALID'
      ? 'Google 메일 설정과 발신 이메일 주소를 확인해 주세요.'
      : '이메일 설정 요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    this.name = 'EmailSettingsApiError';
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await apiFetch(`${getApiBaseUrl()}/api/v1/integrations/email${path}`, {
      ...init,
      headers: { Accept: 'application/json', ...init?.headers },
    });
  } catch {
    throw new EmailSettingsApiError('NETWORK_ERROR');
  }
  let body: unknown;
  try { body = await response.json(); } catch { body = undefined; }
  if (!response.ok) {
    const code = (body as { error?: { code?: string } } | undefined)?.error?.code ?? 'REQUEST_FAILED';
    throw new EmailSettingsApiError(code);
  }
  return body as T;
}

export async function getEmailSettings(): Promise<EmailSettings> {
  return (await request<{ data: EmailSettings }>('')).data;
}

export async function saveEmailSettings(settings: StoredEmailSettings): Promise<EmailSettings> {
  const result = await request<{ data: EmailSettings }>('', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
  });
  saveStoredEmailSettings(settings);
  return result.data;
}

export async function deleteEmailSettings(): Promise<EmailSettings> {
  const result = await request<{ data: EmailSettings }>('', { method: 'DELETE' });
  clearStoredEmailSettings();
  return result.data;
}

export async function restoreEmailSettings(): Promise<EmailSettings> {
  const current = await getEmailSettings();
  if (current.configured) return current;
  const stored = loadStoredEmailSettings();
  return stored ? saveEmailSettings(stored) : current;
}

export async function verifyEmailConnection(): Promise<{ connected: boolean; message: string }> {
  return (await request<{ data: { connected: boolean; message: string } }>('/test', { method: 'POST' })).data;
}

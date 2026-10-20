import { getApiBaseUrl } from './apiBase';
import { clearStoredSlackWebhook, loadStoredSlackWebhook, saveStoredSlackWebhook } from './slackWebhookStorage';

export type SlackWebhookSettings = {
  configured: boolean;
  connectionVerified: boolean;
  maskedWebhookUrl: string | null;
  verifiedAt: string | null;
};

export class SlackSettingsApiError extends Error {
  constructor(readonly code: string) {
    super(code === 'SLACK_WEBHOOK_INVALID'
      ? 'Slack 알림 주소를 확인해 주세요.'
      : 'Slack 설정 요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    this.name = 'SlackSettingsApiError';
  }
}

let restorePromise: Promise<SlackWebhookSettings> | null = null;

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${getApiBaseUrl()}/api/v1/integrations/slack${path}`, {
      ...init,
      headers: { Accept: 'application/json', ...init?.headers },
    });
  } catch {
    throw new SlackSettingsApiError('NETWORK_ERROR');
  }
  let body: unknown;
  try { body = await response.json(); } catch { body = undefined; }
  if (!response.ok) {
    const code = (body as { error?: { code?: string } } | undefined)?.error?.code ?? 'REQUEST_FAILED';
    throw new SlackSettingsApiError(code);
  }
  return body as T;
}

export async function getSlackWebhookSettings(): Promise<SlackWebhookSettings> {
  const result = await request<{ data: SlackWebhookSettings }>('');
  return result.data;
}

export async function saveSlackWebhook(webhookUrl: string): Promise<SlackWebhookSettings> {
  const result = await request<{ data: SlackWebhookSettings }>('', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ webhookUrl }),
  });
  saveStoredSlackWebhook(webhookUrl);
  return result.data;
}

export async function deleteSlackWebhook(): Promise<SlackWebhookSettings> {
  const result = await request<{ data: SlackWebhookSettings }>('', { method: 'DELETE' });
  clearStoredSlackWebhook();
  return result.data;
}

/** Restore the browser-held webhook into backend settings after a backend reset. */
export async function restoreSlackWebhookSettings(): Promise<SlackWebhookSettings> {
  if (restorePromise) return restorePromise;
  restorePromise = restoreStoredSlackWebhook().finally(() => { restorePromise = null; });
  return restorePromise;
}

async function restoreStoredSlackWebhook(): Promise<SlackWebhookSettings> {
  const current = await getSlackWebhookSettings();
  if (current.configured) return current;
  const storedWebhook = loadStoredSlackWebhook();
  if (!storedWebhook) return current;

  await saveSlackWebhook(storedWebhook);
  try {
    await testSlackWebhook();
  } catch {
    // Keep the restored configuration visible; health will report it as unverified.
  }
  return getSlackWebhookSettings();
}

export async function testSlackWebhook(): Promise<{ sent: boolean; message: string }> {
  const result = await request<{ data: { sent: boolean; message: string } }>('/test', { method: 'POST' });
  return result.data;
}

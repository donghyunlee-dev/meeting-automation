import { afterEach, describe, expect, it, vi } from 'vitest';
import { restoreSlackWebhookSettings } from './slackSettingsApi';
import { SLACK_WEBHOOK_STORAGE_KEY } from './slackWebhookStorage';

function response(data: unknown, status = 200) {
  return new Response(JSON.stringify({ data }), { status, headers: { 'Content-Type': 'application/json' } });
}

afterEach(() => {
  localStorage.clear();
  vi.unstubAllGlobals();
});

describe('Slack webhook restoration', () => {
  it('rechecks a restored webhook and returns the verified state', async () => {
    localStorage.setItem(SLACK_WEBHOOK_STORAGE_KEY, 'https://hooks.slack.com/services/test');
    const calls: Array<{ method: string; path: string }> = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input), window.location.origin).pathname;
      const method = init?.method ?? 'GET';
      calls.push({ method, path });
      if (path.endsWith('/integrations/slack') && method === 'GET') {
        return response({ configured: calls.some((call) => call.method === 'PUT'), connectionVerified: calls.some((call) => call.method === 'POST'), maskedWebhookUrl: '••••••••', verifiedAt: null });
      }
      if (path.endsWith('/integrations/slack') && method === 'PUT') {
        return response({ configured: true, connectionVerified: false, maskedWebhookUrl: '••••••••', verifiedAt: null });
      }
      if (path.endsWith('/integrations/slack/test') && method === 'POST') {
        return response({ sent: true, message: 'Slack 채널에 연결 확인 메시지를 보냈습니다.' });
      }
      throw new Error(`Unexpected request: ${method} ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    const restored = await restoreSlackWebhookSettings();

    expect(restored).toMatchObject({ configured: true, connectionVerified: true });
    expect(calls.map(({ method, path }) => `${method} ${path}`)).toEqual([
      'GET /api/v1/integrations/slack',
      'PUT /api/v1/integrations/slack',
      'POST /api/v1/integrations/slack/test',
      'GET /api/v1/integrations/slack',
    ]);
  });
});

import { afterEach, describe, expect, it, vi } from 'vitest';
import { getAppConfig, getIntegrationHealth, SettingsApiError } from './settingsApi';

afterEach(() => vi.unstubAllGlobals());

describe('settings API queries', () => {
  it('reads the public app config and integration health from independent GET endpoints', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(init?.method).toBeUndefined();
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return new Response(JSON.stringify({ data: { company: { id: 'c1', name: 'Acme', timezone: 'Asia/Seoul' } } }));
      if (path.endsWith('/integrations/health')) return new Response(JSON.stringify({ data: { document: null } }));
      throw new Error(`Unexpected URL ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    const [config, health] = await Promise.all([getAppConfig(), getIntegrationHealth()]);

    expect(config.company).toEqual({ id: 'c1', name: 'Acme', timezone: 'Asia/Seoul' });
    expect(health.document).toBeNull();
    expect(fetchMock.mock.calls.map(([url]) => new URL(String(url)).pathname).sort()).toEqual([
      '/api/v1/app-config', '/api/v1/integrations/health',
    ]);
  });

  it('hides server error bodies and reports a stable safe error for a failed query', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: { detail: 'private-token-marker' } }), { status: 503 })));

    await expect(getIntegrationHealth()).rejects.toMatchObject({ name: 'SettingsApiError', code: 'REQUEST_FAILED' });
    try { await getAppConfig(); } catch (error) {
      expect(error).toBeInstanceOf(SettingsApiError);
      expect((error as Error).message).not.toContain('private-token-marker');
    }
  });
});

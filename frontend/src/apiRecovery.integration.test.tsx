import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiFetch } from './apiFetch';
import { getApiBaseUrl } from './apiBase';
import { BackendRecoveryOverlay } from './components/BackendRecoveryOverlay';
import { dismissApiRecovery, getApiRecoveryState } from './apiRecovery';

afterEach(() => {
  dismissApiRecovery();
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
});

describe('backend recovery integration', () => {
  it('recognizes same-origin proxied API failures and keeps the read pending while the backend is unavailable', async () => {
    vi.stubEnv('VITE_API_BASE_URL', '/api/backend');
    const fetchMock = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch'));
    let requestSettled = false;
    render(<BackendRecoveryOverlay />);

    const request = apiFetch(
      `${getApiBaseUrl()}/api/v1/document-setup`,
      { headers: { Accept: 'application/json' } },
      {},
      fetchMock,
    ).finally(() => { requestSettled = true; });
    void request.catch(() => undefined);

    const dialog = await screen.findByRole('dialog', { name: '서버 연결을 확인하고 있어요' });
    expect(dialog).toBeTruthy();
    expect(getApiRecoveryState()).toMatchObject({ status: 'recovering', visible: true });
    expect(requestSettled).toBe(false);
    expect(fetchMock.mock.calls.map(([input]) => String(input))).toEqual([
      '/api/backend/api/v1/document-setup',
      '/api/backend/api/v1/system/health',
      '/api/backend/api/v1/system/health',
    ]);

    dismissApiRecovery();
    await expect(request).rejects.toBeInstanceOf(TypeError);
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

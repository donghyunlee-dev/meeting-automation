import { afterEach, describe, expect, it, vi } from 'vitest';
import { recoverBackendAvailability, dismissApiRecovery } from './apiRecovery';

afterEach(() => {
  dismissApiRecovery();
  vi.restoreAllMocks();
});

describe('backend availability recovery', () => {
  it('uses Render Actuator health when the application health route is not deployed', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(null, { status: 404 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ status: 'UP' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }));

    await expect(recoverBackendAvailability(fetchMock)).resolves.toBe('already-ready');
    expect(fetchMock.mock.calls.map(([input]) => new URL(String(input)).pathname)).toEqual([
      '/api/v1/system/health',
      '/actuator/health',
    ]);
  });
});

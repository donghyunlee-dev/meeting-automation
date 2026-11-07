import { afterEach, describe, expect, it, vi } from 'vitest';
import { getAuthSession, recordAuthActivity, requestOtp, verifyOtp } from './authApi';

afterEach(() => vi.unstubAllGlobals());

describe('authApi', () => {
  it('uses credentialed auth-only endpoints for OTP and idle activity', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: { expiresInSeconds: 60 } }), { status: 202 }));
    vi.stubGlobal('fetch', fetchMock);
    await requestOtp('person@example.com');
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/auth/otp-requests');
    expect(init.credentials).toBe('include');
    expect(init.body).toBe(JSON.stringify({ email: 'person@example.com' }));
  });

  it('reads session state without mutating it and reports lock retry time', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { authenticated: true, sessionId: 'uuid', remainingIdleSeconds: 90 } }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ retryAfterSeconds: 300 }), { status: 429, headers: { 'Retry-After': '300' } }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(getAuthSession()).resolves.toMatchObject({ remainingIdleSeconds: 90 });
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe('GET');
    expect(init.body).toBeUndefined();
    await expect(verifyOtp('person@example.com', '000001')).rejects.toMatchObject({ status: 429, retryAfterSeconds: 300 });
  });

  it('posts explicit activity only through the auth endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: { authenticated: true, remainingIdleSeconds: 7200 } }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    await recordAuthActivity();
    expect(fetchMock.mock.calls[0]?.[0]).toContain('/api/v1/auth/session/activity');
  });
});

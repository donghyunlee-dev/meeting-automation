import { apiFetch } from './apiFetch';
import { getApiBaseUrl } from './apiBase';

export type AuthSession = { authenticated: true; sessionId: string; remainingIdleSeconds: number };

export class AuthApiError extends Error {
  constructor(readonly status: number, readonly retryAfterSeconds?: number, readonly expired = false) {
    super(status === 400 ? '인증 코드가 올바르지 않습니다. 이메일을 확인하고 다시 입력해 주세요.'
      : status === 429 && (retryAfterSeconds ?? 0) >= 300 ? '인증 실패가 누적되어 5분간 인증이 제한됩니다. 잠시 후 다시 시도해 주세요.'
        : status === 429 ? '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.'
      : status === 410 ? '인증 코드가 만료되었습니다. 새 코드를 요청해 주세요.'
        : status === 401 ? '인증이 만료되었습니다.' : '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    this.name = 'AuthApiError';
  }
}

async function call<T>(path: string, body?: unknown): Promise<T> {
  const response = await apiFetch(`${getApiBaseUrl()}/api/v1/auth${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    credentials: 'include',
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const payload = await response.json().catch(() => ({})) as { data?: T; retryAfterSeconds?: number };
  if (!response.ok) {
    const retryHeader = Number(response.headers.get('Retry-After'));
    throw new AuthApiError(response.status, Number.isFinite(retryHeader) && retryHeader > 0
      ? retryHeader : payload.retryAfterSeconds, response.status === 410 || response.status === 401);
  }
  return payload.data as T;
}

export function requestOtp(email: string) {
  return call<{ expiresInSeconds: number; resendAfterSeconds: number }>('/otp-requests', { email });
}

export function verifyOtp(email: string, code: string) {
  return call<{ verified: boolean; sessionId?: string }>('/otp-verifications', { email, code });
}

export function getAuthSession() {
  return call<AuthSession>('/session');
}

export function recordAuthActivity() {
  return call<{ authenticated: boolean; remainingIdleSeconds: number }>('/session/activity', {});
}

export function logoutAuthSession() {
  return call<{ loggedOut: boolean }>('/logout', {});
}

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EMAIL_SETTINGS_STORAGE_KEY } from './emailSettingsStorage';
import { EmailOtpEntry } from './EmailOtpEntry';
import type { EmailSettings } from './emailSettingsApi';
import { withHealthyBackend } from './testUtils/withHealthyBackend';

const verifiedEmailSettings = {
  configured: true,
  connectionVerified: true,
  maskedClientId: '••••••••',
  maskedClientSecret: '••••••••',
  maskedRefreshToken: '••••••••',
  senderAddress: 'sender@example.com',
  verifiedAt: '2026-11-07T00:00:00Z',
};

const missingEmailSettings = {
  configured: false,
  connectionVerified: false,
  maskedClientId: null,
  maskedClientSecret: null,
  maskedRefreshToken: null,
  senderAddress: null,
  verifiedAt: null,
};

function jsonResponse(data: unknown, status = 200) {
  return new Response(JSON.stringify(status >= 400 ? data : { data }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function installFetch(settings: EmailSettings, ...responses: Response[]) {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(jsonResponse(settings))
    .mockImplementation(async () => responses.shift() ?? jsonResponse({}));
  vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
  return fetchMock;
}

afterEach(() => {
  cleanup();
  window.localStorage.clear();
  vi.unstubAllGlobals();
});

describe('EmailOtpEntry', () => {
  it('shows email settings and disables OTP while the sender is not configured', async () => {
    installFetch(missingEmailSettings);
    render(<EmailOtpEntry onAuthenticated={() => undefined} />);

    expect(await screen.findByRole('button', { name: '이메일 설정' })).toBeTruthy();
    fireEvent.change(screen.getByLabelText('이메일 주소'), { target: { value: 'person@example.com' } });
    expect(screen.getByRole('button', { name: '인증 코드 받기' })).toHaveProperty('disabled', true);
    fireEvent.click(screen.getByRole('button', { name: '이메일 설정' }));
    expect(await screen.findByRole('dialog', { name: '이메일 발송 설정' })).toBeTruthy();
  });

  it('offers the settings dialog when the configured sender is not verified', async () => {
    installFetch({ ...verifiedEmailSettings, connectionVerified: false, verifiedAt: null });
    render(<EmailOtpEntry onAuthenticated={() => undefined} />);

    expect(await screen.findByRole('button', { name: '이메일 설정' })).toBeTruthy();
  });

  it('offers the settings dialog when the saved sender status cannot be read', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ error: { code: 'REQUEST_FAILED' } }, 503));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<EmailOtpEntry onAuthenticated={() => undefined} />);

    expect(await screen.findByRole('button', { name: '이메일 설정' })).toBeTruthy();
  });

  it('closes the settings dialog with Escape', async () => {
    installFetch(missingEmailSettings);
    render(<EmailOtpEntry onAuthenticated={() => undefined} />);
    fireEvent.click(await screen.findByRole('button', { name: '이메일 설정' }));
    const dialog = await screen.findByRole('dialog', { name: '이메일 발송 설정' });

    fireEvent.keyDown(dialog, { key: 'Escape' });

    await waitFor(() => expect(screen.queryByRole('dialog', { name: '이메일 발송 설정' })).toBeNull());
  });

  it('masks credential values and blocks copying or cutting them', async () => {
    installFetch(missingEmailSettings);
    render(<EmailOtpEntry onAuthenticated={() => undefined} />);
    fireEvent.click(await screen.findByRole('button', { name: '이메일 설정' }));

    for (const label of ['Google 클라이언트 ID', 'Google 클라이언트 보안 키', '메일 전송용 연결 토큰']) {
      const input = screen.getByLabelText(label) as HTMLInputElement;
      expect(input.type).toBe('password');
      const copyEvent = new Event('copy', { bubbles: true, cancelable: true });
      const cutEvent = new Event('cut', { bubbles: true, cancelable: true });
      input.dispatchEvent(copyEvent);
      input.dispatchEvent(cutEvent);
      expect(copyEvent.defaultPrevented).toBe(true);
      expect(cutEvent.defaultPrevented).toBe(true);
    }
  });

  it('keeps the dialog open when the saved credentials fail their connection test', async () => {
    installFetch(missingEmailSettings,
      jsonResponse({ configured: true, connectionVerified: false, maskedClientId: '••••••••', maskedClientSecret: '••••••••', maskedRefreshToken: '••••••••', senderAddress: 'sender@example.com', verifiedAt: null }),
      jsonResponse({ data: { connected: false, message: '연결 실패' } }, 502));
    render(<EmailOtpEntry onAuthenticated={() => undefined} />);
    fireEvent.click(await screen.findByRole('button', { name: '이메일 설정' }));
    fireEvent.change(screen.getByLabelText('발신 이메일 주소'), { target: { value: 'sender@example.com' } });
    fireEvent.change(screen.getByLabelText('Google 클라이언트 ID'), { target: { value: 'client-id' } });
    fireEvent.change(screen.getByLabelText('Google 클라이언트 보안 키'), { target: { value: 'client-secret' } });
    fireEvent.change(screen.getByLabelText('메일 전송용 연결 토큰'), { target: { value: 'refresh-token' } });
    fireEvent.click(screen.getByRole('button', { name: '설정 저장 및 연결 확인' }));

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByRole('dialog', { name: '이메일 발송 설정' })).toBeTruthy();
  });

  it('saves credentials only on the server and enables OTP after connection succeeds', async () => {
    const fetchMock = installFetch(missingEmailSettings,
      jsonResponse({ ...verifiedEmailSettings, connectionVerified: false, verifiedAt: null }),
      jsonResponse({ connected: true, message: '연결됨' }),
      jsonResponse({ expiresInSeconds: 60, resendAfterSeconds: 60 }, 202));
    render(<EmailOtpEntry onAuthenticated={() => undefined} />);
    fireEvent.change(await screen.findByLabelText('이메일 주소'), { target: { value: 'person@example.com' } });
    fireEvent.click(await screen.findByRole('button', { name: '이메일 설정' }));
    fireEvent.change(screen.getByLabelText('발신 이메일 주소'), { target: { value: 'sender@example.com' } });
    fireEvent.change(screen.getByLabelText('Google 클라이언트 ID'), { target: { value: 'client-id' } });
    fireEvent.change(screen.getByLabelText('Google 클라이언트 보안 키'), { target: { value: 'client-secret' } });
    fireEvent.change(screen.getByLabelText('메일 전송용 연결 토큰'), { target: { value: 'refresh-token' } });
    fireEvent.click(screen.getByRole('button', { name: '설정 저장 및 연결 확인' }));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: '이메일 발송 설정' })).toBeNull());
    expect(screen.getByRole('button', { name: '인증 코드 받기' })).toHaveProperty('disabled', false);
    expect(window.localStorage.getItem(EMAIL_SETTINGS_STORAGE_KEY)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '인증 코드 받기' }));
    expect(await screen.findByLabelText('6자리 인증 코드')).toBeTruthy();
    expect(fetchMock.mock.calls.map(([input]) => new URL(String(input)).pathname)).toEqual([
      '/api/v1/integrations/email',
      '/api/v1/integrations/email',
      '/api/v1/integrations/email/test',
      '/api/v1/auth/otp-requests',
    ]);
    const [, saveRequest] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(saveRequest.method).toBe('PUT');
    expect(saveRequest.body).toBe(JSON.stringify({
      senderAddress: 'sender@example.com',
      clientId: 'client-id',
      clientSecret: 'client-secret',
      refreshToken: 'refresh-token',
    }));
  });

  it('reveals email settings after an OTP delivery failure', async () => {
    installFetch(verifiedEmailSettings, jsonResponse({ error: { code: 'EMAIL_DELIVERY_FAILED' } }, 502));
    render(<EmailOtpEntry onAuthenticated={() => undefined} />);
    fireEvent.change(await screen.findByLabelText('이메일 주소'), { target: { value: 'person@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '인증 코드 받기' }));

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(await screen.findByRole('button', { name: '이메일 설정' })).toBeTruthy();
  });

  it('requests a code, accepts a pasted six digit code, and completes verification', async () => {
    const fetchMock = installFetch(verifiedEmailSettings,
      jsonResponse({ expiresInSeconds: 60, resendAfterSeconds: 60 }, 202),
      jsonResponse({ verified: true, sessionId: 'device-session' }));
    const onAuthenticated = vi.fn();
    render(<EmailOtpEntry onAuthenticated={onAuthenticated} />);

    fireEvent.change(await screen.findByLabelText('이메일 주소'), { target: { value: 'person@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '인증 코드 받기' }));
    const codeInput = await screen.findByLabelText('6자리 인증 코드');
    fireEvent.paste(codeInput, { clipboardData: { getData: () => '123456' } });
    fireEvent.change(codeInput, { target: { value: '12a34567' } });
    expect((codeInput as HTMLInputElement).value).toBe('123456');
    fireEvent.submit(codeInput.closest('form')!);
    await waitFor(() => expect(onAuthenticated).toHaveBeenCalledOnce());
    expect(new URL(String(fetchMock.mock.calls[2]?.[0])).pathname).toContain('/auth/otp-verifications');
  });

  it('shows wrong code and lock responses without dropping the email', async () => {
    installFetch(verifiedEmailSettings,
      jsonResponse({ expiresInSeconds: 60, resendAfterSeconds: 60 }, 202),
      jsonResponse({ data: { verified: false } }, 400));
    render(<EmailOtpEntry onAuthenticated={() => undefined} />);
    fireEvent.change(await screen.findByLabelText('이메일 주소'), { target: { value: 'person@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '인증 코드 받기' }));
    const code = await screen.findByLabelText('6자리 인증 코드');
    fireEvent.change(code, { target: { value: '000001' } });
    fireEvent.submit(code.closest('form')!);
    expect((await screen.findByRole('alert')).textContent).toContain('인증 코드가 올바르지 않습니다');
    expect(screen.getByText(/person@example.com/)).toBeTruthy();
  });

  it('explains the five minute verification lock', async () => {
    installFetch(verifiedEmailSettings,
      jsonResponse({ expiresInSeconds: 60, resendAfterSeconds: 60 }, 202),
      jsonResponse({ retryAfterSeconds: 300 }, 429));
    render(<EmailOtpEntry onAuthenticated={() => undefined} />);
    fireEvent.change(await screen.findByLabelText('이메일 주소'), { target: { value: 'person@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '인증 코드 받기' }));
    const code = await screen.findByLabelText('6자리 인증 코드');
    fireEvent.change(code, { target: { value: '123456' } });
    fireEvent.submit(code.closest('form')!);
    expect((await screen.findByRole('alert')).textContent).toContain('5분간 인증이 제한됩니다');
    expect(screen.getByRole('button', { name: '300초 후 다시 보내기' })).toBeTruthy();
  });
});

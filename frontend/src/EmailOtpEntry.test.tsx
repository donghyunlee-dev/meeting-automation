import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EmailOtpEntry } from './EmailOtpEntry';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('EmailOtpEntry', () => {
  it('requests a code, accepts a pasted six digit code, and completes verification', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { expiresInSeconds: 60, resendAfterSeconds: 60 } }), { status: 202 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { verified: true, sessionId: 'device-session' } }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const onAuthenticated = vi.fn();
    render(<EmailOtpEntry onAuthenticated={onAuthenticated} />);

    fireEvent.change(screen.getByLabelText('이메일 주소'), { target: { value: 'person@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '인증 코드 받기' }));
    const codeInput = await screen.findByLabelText('6자리 인증 코드');
    fireEvent.paste(codeInput, { clipboardData: { getData: () => '123456' } });
    fireEvent.change(codeInput, { target: { value: '12a34567' } });
    expect((codeInput as HTMLInputElement).value).toBe('123456');
    fireEvent.submit(codeInput.closest('form')!);
    await waitFor(() => expect(onAuthenticated).toHaveBeenCalledOnce());
    expect(fetchMock.mock.calls[1]?.[0]).toContain('/auth/otp-verifications');
  });

  it('shows wrong code and lock responses without dropping the email', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { expiresInSeconds: 60, resendAfterSeconds: 60 } }), { status: 202 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { verified: false } }), { status: 400 }));
    vi.stubGlobal('fetch', fetchMock);
    render(<EmailOtpEntry onAuthenticated={() => undefined} />);
    fireEvent.change(screen.getByLabelText('이메일 주소'), { target: { value: 'person@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '인증 코드 받기' }));
    const code = await screen.findByLabelText('6자리 인증 코드');
    fireEvent.change(code, { target: { value: '000001' } });
    fireEvent.submit(code.closest('form')!);
    expect((await screen.findByRole('alert')).textContent).toContain('인증 코드가 올바르지 않습니다');
    expect(screen.getByText(/person@example.com/)).toBeTruthy();
  });

  it('explains the five minute verification lock', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { expiresInSeconds: 60, resendAfterSeconds: 60 } }), { status: 202 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ retryAfterSeconds: 300 }), { status: 429, headers: { 'Retry-After': '300' } }));
    vi.stubGlobal('fetch', fetchMock);
    render(<EmailOtpEntry onAuthenticated={() => undefined} />);
    fireEvent.change(screen.getByLabelText('이메일 주소'), { target: { value: 'person@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '인증 코드 받기' }));
    const code = await screen.findByLabelText('6자리 인증 코드');
    fireEvent.change(code, { target: { value: '123456' } });
    fireEvent.submit(code.closest('form')!);
    expect((await screen.findByRole('alert')).textContent).toContain('5분간 인증이 제한됩니다');
    expect(screen.getByRole('button', { name: '300초 후 다시 보내기' })).toBeTruthy();
  });
});

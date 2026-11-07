import { useEffect, useState, type FormEvent } from 'react';
import { AuthApiError, requestOtp, verifyOtp } from './authApi';
import { getEmailSettings } from './emailSettingsApi';
import { EmailSettingsDialog } from './EmailSettingsDialog';

export function EmailOtpEntry({ onAuthenticated }: { onAuthenticated: () => void }) {
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [codeSent, setCodeSent] = useState(false);
  const [countdown, setCountdown] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [emailSettingsState, setEmailSettingsState] = useState<'checking' | 'ready' | 'needs-setup'>('checking');
  const [showEmailSettings, setShowEmailSettings] = useState(false);

  useEffect(() => {
    let active = true;
    void getEmailSettings().then((settings) => {
      if (active) setEmailSettingsState(settings.configured && settings.connectionVerified ? 'ready' : 'needs-setup');
    }).catch(() => {
      if (active) setEmailSettingsState('needs-setup');
    });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (countdown <= 0) return undefined;
    const timer = window.setTimeout(() => setCountdown((value) => value - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [countdown]);

  async function sendCode() {
    if (busy || emailSettingsState !== 'ready' || !email.trim()) return;
    setBusy(true);
    setError('');
    try {
      const result = await requestOtp(email.trim());
      setCodeSent(true);
      setCountdown(result.resendAfterSeconds);
      setCode('');
    } catch (reason) {
      const failure = reason instanceof AuthApiError ? reason : undefined;
      setError(failure?.message ?? '인증 코드를 보내지 못했습니다. 잠시 후 다시 시도해 주세요.');
      if (failure?.retryAfterSeconds) setCountdown(failure.retryAfterSeconds);
      if (failure?.status === 502) setEmailSettingsState('needs-setup');
    } finally {
      setBusy(false);
    }
  }

  async function confirmCode(event: FormEvent) {
    event.preventDefault();
    if (busy || code.length !== 6) return;
    setBusy(true);
    setError('');
    try {
      const result = await verifyOtp(email.trim(), code);
      if (!result.verified) {
        setError('인증 코드가 올바르지 않습니다. 이메일을 확인하고 다시 입력해 주세요.');
        return;
      }
      onAuthenticated();
    } catch (reason) {
      const failure = reason instanceof AuthApiError ? reason : undefined;
      setError(failure?.message ?? '인증하지 못했습니다. 코드를 확인해 주세요.');
      if (failure?.retryAfterSeconds) setCountdown(failure.retryAfterSeconds);
      if (failure?.expired) {
        setCodeSent(false);
        setCode('');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth-entry-shell">
      <section className="auth-entry-card" aria-labelledby="auth-entry-title">
        <div className="auth-entry-mark" aria-hidden="true">W</div>
        <p className="auth-entry-eyebrow">MEETING AUTOMATION</p>
        <h1 id="auth-entry-title">이메일로 시작하기</h1>
        <p className="auth-entry-description">회의 기록을 안전하게 이어갈 수 있도록 이메일 인증을 진행해 주세요.</p>
        {!codeSent ? (
          <form className="auth-entry-form" onSubmit={(event) => { event.preventDefault(); void sendCode(); }}>
            <label htmlFor="auth-email">이메일 주소</label>
            <input id="auth-email" type="email" autoComplete="email" required maxLength={254}
              placeholder="name@example.com" value={email} onChange={(event) => setEmail(event.target.value)} />
            <button className="button button-primary auth-entry-submit" type="submit"
              disabled={busy || emailSettingsState !== 'ready' || !email.trim()}>
              {busy ? '보내는 중…' : emailSettingsState === 'checking' ? '이메일 설정 확인 중…' : '인증 코드 받기'}
            </button>
          </form>
        ) : (
          <form className="auth-entry-form" onSubmit={confirmCode}>
            <p className="auth-entry-sent">인증 코드를 <strong>{email.trim()}</strong> 주소로 보냈습니다.</p>
            <label htmlFor="auth-code">6자리 인증 코드</label>
            <input id="auth-code" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}"
              maxLength={6} required placeholder="000000" value={code}
              onChange={(event) => setCode(event.target.value.replace(/\D/g, '').slice(0, 6))} />
            <button className="button button-primary auth-entry-submit" type="submit" disabled={busy || code.length !== 6}>
              {busy ? '확인 중…' : '인증하고 계속하기'}
            </button>
            <button className="auth-entry-resend" type="button"
              disabled={busy || emailSettingsState !== 'ready' || countdown > 0}
              onClick={() => void sendCode()}>
              {countdown > 0 ? `${countdown}초 후 다시 보내기` : '인증 코드 다시 받기'}
            </button>
          </form>
        )}
        {emailSettingsState === 'needs-setup' && (
          <div className="auth-entry-settings">
            <p role="status">이메일 발송 설정이 필요하거나 연결을 확인할 수 없습니다.</p>
            <button className="button button-secondary" type="button" onClick={() => setShowEmailSettings(true)}>
              이메일 설정
            </button>
          </div>
        )}
        {error && <p className="auth-entry-error" role="alert">{error}</p>}
        <p className="auth-entry-footnote">인증 코드는 발송 후 60초 동안 사용할 수 있습니다.</p>
      </section>
      {showEmailSettings && <EmailSettingsDialog onClose={() => setShowEmailSettings(false)} onConfigured={() => {
        setEmailSettingsState('ready');
        setShowEmailSettings(false);
        setCodeSent(false);
        setCode('');
        setCountdown(0);
        setError('');
      }} />}
    </main>
  );
}

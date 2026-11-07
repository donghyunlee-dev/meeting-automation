import { useState, type ClipboardEvent, type FormEvent, type KeyboardEvent } from 'react';
import { saveEmailSettingsOnServer, verifyEmailConnection } from './emailSettingsApi';
import type { StoredEmailSettings } from './emailSettingsStorage';

const emptySettings: StoredEmailSettings = {
  senderAddress: '',
  clientId: '',
  clientSecret: '',
  refreshToken: '',
};

export function EmailSettingsDialog({ onClose, onConfigured }: {
  onClose: () => void;
  onConfigured: () => void;
}) {
  const [form, setForm] = useState<StoredEmailSettings>(emptySettings);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || Object.values(form).some((value) => !value.trim())) return;
    setBusy(true);
    setError('');
    try {
      await saveEmailSettingsOnServer(form);
      const result = await verifyEmailConnection();
      if (!result.connected) {
        setError(result.message || 'Google 메일 연결을 확인하지 못했습니다. 입력한 설정을 확인해 주세요.');
        return;
      }
      onConfigured();
    } catch (failure) {
      setError(failure instanceof Error
        ? failure.message
        : '이메일 설정을 저장하거나 연결을 확인하지 못했습니다.');
    } finally {
      setBusy(false);
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === 'Escape' && !busy) onClose();
  }

  function blockCredentialClipboard(event: ClipboardEvent<HTMLInputElement>) {
    event.preventDefault();
  }

  return (
    <div className="auth-email-settings-overlay">
      <section className="auth-email-settings-dialog" role="dialog" aria-modal="true"
        aria-labelledby="auth-email-settings-title" aria-describedby="auth-email-settings-description"
        onKeyDown={handleKeyDown}>
        <header className="auth-email-settings-header">
          <div>
            <p className="auth-email-settings-eyebrow">EMAIL DELIVERY</p>
            <h2 id="auth-email-settings-title">이메일 발송 설정</h2>
          </div>
          <button className="auth-email-settings-close" type="button" aria-label="이메일 설정 닫기"
            disabled={busy} onClick={onClose}>×</button>
        </header>
        <p id="auth-email-settings-description" className="auth-email-settings-description">
          인증 코드를 보낼 Gmail 계정과 Google OAuth 연결 정보를 입력해 주세요.
        </p>
        <form className="auth-email-settings-form" onSubmit={(event) => void submit(event)}>
          <label>발신 이메일 주소
            <input type="email" autoComplete="email" required autoFocus maxLength={254}
              value={form.senderAddress}
              onChange={(event) => setForm((current) => ({ ...current, senderAddress: event.target.value }))}
              placeholder="name@gmail.com" />
          </label>
          <label>Google 클라이언트 ID
            <input type="password" autoComplete="new-password" spellCheck={false} required
              onCopy={blockCredentialClipboard} onCut={blockCredentialClipboard}
              value={form.clientId}
              onChange={(event) => setForm((current) => ({ ...current, clientId: event.target.value }))}
              placeholder="Google Cloud의 클라이언트 ID" />
          </label>
          <label>Google 클라이언트 보안 키
            <input type="password" autoComplete="new-password" spellCheck={false} required
              onCopy={blockCredentialClipboard} onCut={blockCredentialClipboard}
              value={form.clientSecret}
              onChange={(event) => setForm((current) => ({ ...current, clientSecret: event.target.value }))}
              placeholder="Google Cloud의 클라이언트 보안 키" />
          </label>
          <label>메일 전송용 연결 토큰
            <input type="password" autoComplete="new-password" spellCheck={false} required
              onCopy={blockCredentialClipboard} onCut={blockCredentialClipboard}
              value={form.refreshToken}
              onChange={(event) => setForm((current) => ({ ...current, refreshToken: event.target.value }))}
              placeholder="Gmail 전송 권한으로 발급된 Refresh Token" />
          </label>
          {error && <p className="auth-email-settings-error" role="alert">{error}</p>}
          <div className="auth-email-settings-actions">
            <button className="button button-secondary" type="button" disabled={busy} onClick={onClose}>취소</button>
            <button className="button button-primary" type="submit"
              disabled={busy || Object.values(form).some((value) => !value.trim())}>
              {busy ? '저장 및 연결 확인 중…' : '설정 저장 및 연결 확인'}
            </button>
          </div>
        </form>
        <p className="auth-email-settings-footnote">연결 키는 입력 중 마스킹되며, 화면에서 복사하거나 잘라낼 수 없습니다.</p>
      </section>
    </div>
  );
}

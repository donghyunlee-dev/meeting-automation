import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { AsyncState } from './components/async-state/AsyncState';
import { normalizeApiError } from './lib/api/normalizeApiError';
import { getAppConfig, getIntegrationHealth, selectSettingsSummary, type AppConfig, type IntegrationHealth, type IntegrationState } from './settingsApi';
import { deleteEmailSettings, getEmailSettings, restoreEmailSettings, saveEmailSettings, EmailSettingsApiError, verifyEmailConnection, type EmailSettings } from './emailSettingsApi';
import type { StoredEmailSettings } from './emailSettingsStorage';
import { deleteSlackWebhook, getSlackWebhookSettings, restoreSlackWebhookSettings, saveSlackWebhook, SlackSettingsApiError, testSlackWebhook, type SlackWebhookSettings } from './slackSettingsApi';

type QueryState<T> = { status: 'loading' | 'ready' | 'error'; data: T | null };
const SAFE_SETTINGS_QUERY_ERROR = normalizeApiError({ error: { code: 'REQUEST_FAILED' } });

const stateCopy: Record<IntegrationState, string> = {
  'not-selected': '문서 서비스를 선택해 연결할 수 있습니다.',
  'not-configured': '설정 확인이 필요합니다.',
  unreachable: '연결 상태를 확인하지 못했습니다.',
  'root-inaccessible': '서비스 연결은 확인했지만 필수 Meetings 또는 Participants 구조에 접근할 수 없습니다.',
  available: '공개 연결 상태가 확인되었습니다.',
  'not-confirmed': '최근 알림 전달 상태가 확인되지 않았습니다.',
  'health-unknown': '연결 상태를 불러오지 못했습니다.',
};

function ProviderStatus({ state, configuredLabel = '설정됨' }: { state: IntegrationState; configuredLabel?: string }) {
  const label = state === 'available' ? configuredLabel : stateCopy[state];
  return <p className="settings-status" data-state={state}>{label}</p>;
}

function StaleHealthNotice({ onRetry }: { onRetry: () => void }) {
  return <AsyncState state={{ status: 'ready', data: null, warning: SAFE_SETTINGS_QUERY_ERROR, onRetry, retryLabel: '연결 상태 다시 확인' }} renderReady={() => null} />;
}

function SettingsQueryLoading({ label }: { label: string }) {
  return <AsyncState state={{ status: 'loading', label }} renderReady={() => null} />;
}

function SettingsQueryError({ title, description, retryLabel, onRetry }: { title: string; description: string; retryLabel: string; onRetry: () => void }) {
  return <AsyncState state={{ status: 'error', title, error: SAFE_SETTINGS_QUERY_ERROR, description, retryLabel, onRetry }} renderReady={() => null} />;
}

function EmailSettingsForm({ onChanged }: { onChanged: () => void }) {
  const mounted = useRef(false);
  const [settings, setSettings] = useState<EmailSettings | null>(null);
  const [form, setForm] = useState<StoredEmailSettings>({ clientId: '', clientSecret: '', refreshToken: '', senderAddress: '' });
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const refresh = useCallback(async () => {
    if (!mounted.current) return;
    setLoading(true);
    setError('');
    try {
      const current = await restoreEmailSettings();
      if (!mounted.current) return;
      setSettings(current);
      setEditing(!current.configured);
    } catch (failure) {
      if (!mounted.current) return;
      setEditing(true);
      setError(failure instanceof EmailSettingsApiError ? failure.message : '이메일 설정을 불러오지 못했습니다.');
    } finally { if (mounted.current) setLoading(false); }
  }, []);

  useEffect(() => {
    mounted.current = true;
    void Promise.resolve().then(refresh).finally(() => { if (mounted.current) onChanged(); });
    return () => { mounted.current = false; };
  }, [onChanged, refresh]);

  function clearForm() {
    setForm({ clientId: '', clientSecret: '', refreshToken: '', senderAddress: '' });
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (saving || !editing) return;
    setSaving(true);
    setError('');
    let saved: EmailSettings;
    try {
      saved = await saveEmailSettings(form);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '이메일 설정을 저장하지 못했습니다.');
      setSaving(false);
      return;
    }
    setSettings(saved);
    clearForm();
    setEditing(false);
    try {
      const result = await verifyEmailConnection();
      if (!result.connected) throw new Error(result.message);
      setSettings(await getEmailSettings());
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Google 메일 연결을 확인하지 못했습니다.');
      try { setSettings(await getEmailSettings()); } catch { /* Keep the saved settings summary. */ }
    }
    setSaving(false);
    onChanged();
  }

  async function remove() {
    if (saving) return;
    setSaving(true);
    setError('');
    try {
      setSettings(await deleteEmailSettings());
      clearForm();
      setEditing(true);
      onChanged();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '이메일 설정을 삭제하지 못했습니다.');
    } finally { setSaving(false); }
  }

  return <div className="email-settings-form">
    {loading ? <p className="settings-note" role="status">이메일 설정을 불러오는 중…</p> : <>
      <form onSubmit={(event) => void submit(event)}>
        <div className="email-settings-fields">
          <label>발신 이메일 주소
            <input type="email" autoComplete="email" readOnly={!editing}
              value={editing ? form.senderAddress : settings?.senderAddress ?? ''}
              onChange={(event) => setForm((current) => ({ ...current, senderAddress: event.target.value }))}
              placeholder={editing ? 'name@gmail.com' : ''} required />
          </label>
          <label>Google 클라이언트 ID
            <input type="password" autoComplete="new-password" spellCheck={false} readOnly={!editing}
              value={editing ? form.clientId : settings?.maskedClientId ?? ''}
              onChange={(event) => setForm((current) => ({ ...current, clientId: event.target.value }))}
              placeholder={editing ? 'Google Cloud의 클라이언트 ID' : ''} required />
          </label>
          <label>Google 클라이언트 보안 키
            <input type="password" autoComplete="new-password" spellCheck={false} readOnly={!editing}
              value={editing ? form.clientSecret : settings?.maskedClientSecret ?? ''}
              onChange={(event) => setForm((current) => ({ ...current, clientSecret: event.target.value }))}
              placeholder={editing ? 'Google Cloud의 클라이언트 보안 키' : ''} required />
          </label>
          <label>메일 전송용 연결 토큰
            <input type="password" autoComplete="new-password" spellCheck={false} readOnly={!editing}
              value={editing ? form.refreshToken : settings?.maskedRefreshToken ?? ''}
              onChange={(event) => setForm((current) => ({ ...current, refreshToken: event.target.value }))}
              placeholder={editing ? 'Google 인증에서 발급된 연결 토큰' : ''} required />
          </label>
        </div>
        <div className="email-settings-actions">
          {editing ? <>
            <button className="button button-secondary" type="submit" disabled={saving || Object.values(form).some((value) => !value.trim())}>{saving ? '연결 확인 중…' : '저장'}</button>
            {settings?.configured && <button className="button button-secondary" type="button" disabled={saving} onClick={() => { clearForm(); setEditing(false); setError(''); }}>취소</button>}
          </> : <>
            <button className="button button-secondary" type="button" disabled={saving} onClick={() => { clearForm(); setEditing(true); setError(''); }}>수정</button>
            <button className="button button-secondary" type="button" disabled={saving} onClick={() => void remove()}>삭제</button>
          </>}
        </div>
      </form>
      {error && <p className="slack-settings-feedback is-error" role="alert">{error}</p>}
    </>}
  </div>;
}

function SlackWebhookSettingsForm({ onChanged }: { onChanged: () => void }) {
  const mounted = useRef(false);
  const [settings, setSettings] = useState<SlackWebhookSettings | null>(null);
  const [webhookUrl, setWebhookUrl] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState('');

  const refresh = useCallback(async () => {
    if (!mounted.current) return;
    setLoading(true);
    setError('');
    try {
      const restored = await restoreSlackWebhookSettings();
      if (!mounted.current) return;
      setSettings(restored);
      setEditing(!restored.configured);
    }
    catch (failure) {
      if (!mounted.current) return;
      setError(failure instanceof SlackSettingsApiError ? failure.message : 'Slack 설정을 불러오지 못했습니다.');
    }
    finally { if (mounted.current) setLoading(false); }
  }, []);

  useEffect(() => {
    mounted.current = true;
    void Promise.resolve().then(refresh).finally(() => { if (mounted.current) onChanged(); });
    return () => { mounted.current = false; };
  }, [onChanged, refresh]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (saving || !editing || !webhookUrl.trim()) return;
    setSaving(true);
    setError('');
    let saved: SlackWebhookSettings;
    try {
      saved = await saveSlackWebhook(webhookUrl.trim());
    } catch (failure) {
      setError(failure instanceof SlackSettingsApiError || failure instanceof Error
        ? failure.message
        : 'Slack 알림 주소를 저장하지 못했습니다.');
      setSaving(false);
      return;
    }
    setSettings(saved);
    setWebhookUrl('');
    setEditing(false);
    try {
      const result = await testSlackWebhook();
      if (!result.sent) throw new Error(result.message);
      setSettings(await getSlackWebhookSettings());
    } catch (failure) {
      setError(failure instanceof SlackSettingsApiError || failure instanceof Error
        ? failure.message
        : 'Webhook 저장 또는 연결 확인에 실패했습니다.');
      try { setSettings(await getSlackWebhookSettings()); } catch { /* Keep the last known settings. */ }
    }
    setSaving(false);
    onChanged();
  }

  async function removeWebhook() {
    if (saving) return;
    setSaving(true);
    setError('');
    try {
      const cleared = await deleteSlackWebhook();
      setSettings(cleared);
      setWebhookUrl('');
      setEditing(true);
      onChanged();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Slack 알림 주소를 삭제하지 못했습니다.');
    } finally { setSaving(false); }
  }

  return <div className="slack-settings-form">
    {loading ? <p className="settings-note" role="status">Slack 설정을 불러오는 중…</p> : <>
      <form onSubmit={(event) => void submit(event)}>
        <label htmlFor="slack-webhook-url">Slack 알림 주소</label>
        <div className="slack-webhook-row">
          <input id="slack-webhook-url" type="password" autoComplete="new-password" spellCheck={false}
            readOnly={!editing} value={editing ? webhookUrl : settings?.configured ? '••••••••' : ''}
            onChange={(event) => setWebhookUrl(event.target.value)}
            placeholder={editing ? 'Slack에서 복사한 알림 주소를 입력해 주세요' : ''} />
          {editing ? <>
            <div className="slack-webhook-actions">
              <button className="button button-secondary" type="submit" disabled={saving || !webhookUrl.trim()}>{saving ? '확인 중…' : '저장'}</button>
              {settings?.configured && <button className="button button-secondary" type="button" disabled={saving} onClick={() => { setWebhookUrl(''); setEditing(false); setError(''); }}>취소</button>}
            </div>
          </> : <div className="slack-webhook-actions">
            <button className="button button-secondary" type="button" disabled={saving} onClick={() => { setWebhookUrl(''); setEditing(true); setError(''); }}>수정</button>
            <button className="button button-secondary" type="button" disabled={saving} onClick={() => void removeWebhook()}>삭제</button>
          </div>}
        </div>
      </form>
      {error && <p className="slack-settings-feedback is-error" role="alert">{error}</p>}
    </>}
  </div>;
}

export function SettingsPage() {
  const [config, setConfig] = useState<QueryState<AppConfig>>({ status: 'loading', data: null });
  const [health, setHealth] = useState<QueryState<IntegrationHealth>>({ status: 'loading', data: null });
  const refreshConfig = useCallback(async () => {
    setConfig((current) => ({ status: 'loading', data: current.data }));
    try { setConfig({ status: 'ready', data: await getAppConfig() }); }
    catch { setConfig((current) => ({ status: 'error', data: current.data })); }
  }, []);
  const refreshHealth = useCallback(async () => {
    setHealth((current) => ({ status: 'loading', data: current.data }));
    try { setHealth({ status: 'ready', data: await getIntegrationHealth() }); }
    catch { setHealth((current) => ({ status: 'error', data: current.data })); }
  }, []);

  useEffect(() => {
    let live = true;
    void getAppConfig().then((data) => { if (live) setConfig({ status: 'ready', data }); })
      .catch(() => { if (live) setConfig({ status: 'error', data: null }); });
    void getIntegrationHealth().then((data) => { if (live) setHealth({ status: 'ready', data }); })
      .catch(() => { if (live) setHealth({ status: 'error', data: null }); });
    return () => { live = false; };
  }, []);

  const summaryConfig: Pick<AppConfig, 'document' | 'email' | 'notification'> | null = config.data ?? (health.data ? {
    document: health.data.document ? {
      provider: health.data.document.provider,
      configured: health.data.document.configured,
      connectionVersion: 0,
      rootUrl: null,
    } : undefined,
    email: health.data.email ? { enabled: health.data.email.configured, configured: health.data.email.configured } : undefined,
    notification: health.data.notification ? { provider: health.data.notification.provider, enabled: health.data.notification.configured } : undefined,
  } : null);
  const summary = summaryConfig ? selectSettingsSummary(summaryConfig, health.data) : null;
  const bothQueriesFailed = config.status === 'error' && health.status === 'error';
  const documentName = summary?.document.provider ?? (config.data?.document?.provider ? '선택된 문서 서비스' : '문서 서비스');
  const emailStatus = summary?.email.state;
  const notificationStatus = summary?.notification.state;

  return <main className="settings-page">
    <header className="settings-heading"><h1><svg className="settings-heading-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h8m4 0h4M4 12h3m4 0h9M4 18h10m4 0h2" /><circle cx="14" cy="6" r="2" /><circle cx="9" cy="12" r="2" /><circle cx="16" cy="18" r="2" /></svg>설정</h1></header>
    {bothQueriesFailed && <SettingsQueryError title="설정과 연결 상태를 불러오지 못했습니다." description="오류 세부 정보는 표시하지 않습니다." retryLabel="전체 설정 다시 불러오기" onRetry={() => { void refreshConfig(); void refreshHealth(); }} />}
    {health.status === 'loading' && !health.data && <SettingsQueryLoading label="연결 상태를 불러오는 중…" />}
    {health.status === 'error' && !health.data && !bothQueriesFailed && <SettingsQueryError title="연결 상태를 불러오지 못했습니다." description="서비스 연결 상태를 다시 확인해 주세요." retryLabel="연결 상태 다시 확인" onRetry={() => void refreshHealth()} />}
    {health.status === 'error' && health.data && <StaleHealthNotice onRetry={() => void refreshHealth()} />}
    {config.status === 'error' && config.data && <AsyncState state={{ status: 'ready', data: config.data, warning: SAFE_SETTINGS_QUERY_ERROR, onRetry: () => void refreshConfig(), retryLabel: '공개 설정 다시 불러오기' }} renderReady={() => null} />}
    {config.status === 'error' && !config.data && health.data && <SettingsQueryError title="공개 설정을 불러오지 못했습니다." description="확인 가능한 연결 상태를 표시합니다." retryLabel="공개 설정 다시 불러오기" onRetry={() => void refreshConfig()} />}
    <div className="settings-sections">
      {summary && <>
        <section className="settings-card" aria-labelledby="settings-document-heading">
          <div className="settings-card-heading"><h2 id="settings-document-heading">문서 연결</h2><ProviderStatus state={summary.document.state} configuredLabel="연결 상태 확인됨" /></div>
          <p className="settings-method">{documentName}에 회의록과 기본 템플릿을 저장합니다.</p>
          <Link className="button button-secondary" to="/settings/document">문서 연결 설정</Link>
        </section>
        <section className="settings-card" aria-labelledby="settings-email-heading">
          <div className="settings-card-heading"><h2 id="settings-email-heading">이메일</h2><ProviderStatus state={emailStatus ?? 'health-unknown'} configuredLabel="연결 확인됨" /></div>
          <EmailSettingsForm onChanged={() => { void refreshConfig(); void refreshHealth(); }} />
        </section>
        <section className="settings-card" aria-labelledby="settings-notification-heading">
          <div className="settings-card-heading"><h2 id="settings-notification-heading">알림</h2><ProviderStatus state={notificationStatus ?? 'health-unknown'} configuredLabel="연결 확인됨" /></div>
          <SlackWebhookSettingsForm onChanged={() => { void refreshConfig(); void refreshHealth(); }} />
        </section>
      </>}
      <section className="settings-card" aria-labelledby="settings-participants-heading">
        <div className="settings-card-heading"><h2 id="settings-participants-heading">참석자</h2></div>
        <p className="settings-method">회의에서 선택할 참석자 목록을 관리합니다.</p>
        <Link className="button button-secondary" to="/settings/participants">참석자 관리</Link>
      </section>
    </div>
    {config.status === 'ready' && health.status === 'ready' && <button className="button button-secondary settings-refresh" type="button" onClick={() => { void refreshConfig(); void refreshHealth(); }}>모든 연결 상태 새로고침</button>}
  </main>;
}

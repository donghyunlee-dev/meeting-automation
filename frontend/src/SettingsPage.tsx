import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router';
import { AsyncState } from './components/async-state/AsyncState';
import { normalizeApiError } from './lib/api/normalizeApiError';
import { getAppConfig, getIntegrationHealth, selectSettingsSummary, type AppConfig, type IntegrationHealth, type IntegrationState } from './settingsApi';

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
  const notificationName = summary?.notification.provider ?? '알림';
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
          <p className="settings-method"><strong>연결 방법</strong> · {documentName} 연결 정보로 회의록 구조를 만듭니다.</p>
          <Link className="button button-secondary" to="/settings/document">문서 연결 설정</Link>
        </section>
        <section className="settings-card" aria-labelledby="settings-email-heading">
          <div className="settings-card-heading"><h2 id="settings-email-heading">이메일</h2><ProviderStatus state={emailStatus ?? 'health-unknown'} configuredLabel="연결 확인됨" /></div>
          <p className="settings-method"><strong>연결 방법</strong> · Gmail OAuth 정보를 백엔드 환경에 설정합니다.</p>
        </section>
        <section className="settings-card" aria-labelledby="settings-notification-heading">
          <div className="settings-card-heading"><h2 id="settings-notification-heading">알림</h2><ProviderStatus state={notificationStatus ?? 'health-unknown'} configuredLabel="연결 확인됨" /></div>
          <p className="settings-method"><strong>연결 방법</strong> · {notificationName} Webhook URL을 백엔드 환경에 설정합니다.</p>
          <p className="settings-note">최근 전달 결과를 표시하며 실제 전달 성공을 보장하지 않습니다.</p>
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

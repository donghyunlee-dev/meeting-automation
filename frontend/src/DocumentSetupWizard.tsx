import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { getSetupOperation, getSetupSnapshot, initializeSetup, saveSetupDraft, testSetupDraft, DocumentSetupError, type DocumentProvider, type DraftInput, type SetupOperation, type SetupSnapshot, type TestedDraft } from './documentSetupApi';
import { getCompanyConfig, listMeetingParticipants } from './newMeetingApi';

type Credentials = { token: string; accountEmail: string; apiToken: string };
type Location = { parentPageId: string; baseUrl: string; spaceId: string; parentPageIdConfluence: string };
const EMPTY_CREDENTIALS: Credentials = { token: '', accountEmail: '', apiToken: '' };
const EMPTY_LOCATION: Location = { parentPageId: '', baseUrl: '', spaceId: '', parentPageIdConfluence: '' };

export function DocumentSetupWizard({ settings = false }: { settings?: boolean }) {
  const navigate = useNavigate();
  const [snapshot, setSnapshot] = useState<SetupSnapshot | null>(null);
  const [provider, setProvider] = useState<DocumentProvider | null>(null);
  const [credentials, setCredentials] = useState(EMPTY_CREDENTIALS);
  const [location, setLocation] = useState(EMPTY_LOCATION);
  const [draft, setDraft] = useState<{ id: string; revision: number; version: number } | null>(null);
  const [tested, setTested] = useState<TestedDraft | null>(null);
  const [operation, setOperation] = useState<SetupOperation | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const formRef = useRef<HTMLFormElement | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const initializing = useRef(false);

  async function refresh() {
    const result = await getSetupSnapshot();
    setSnapshot(result.snapshot);
    if (result.snapshot.operation) {
      try { setOperation(await getSetupOperation(result.snapshot.operation.operationId)); }
      catch { setError('진행 상태를 불러오지 못했습니다. 다시 확인해 주세요.'); }
    }
    return result.snapshot;
  }

  useEffect(() => { let live = true; void getSetupSnapshot().then(({ snapshot: current }) => {
    if (!live) return; setSnapshot(current);
    if (current.draft) { setProvider(current.draft.provider); setDraft({ id: current.draft.draftId, revision: current.draft.revision, version: current.version ?? 0 }); }
    if (current.operation) void getSetupOperation(current.operation.operationId).then((state) => { if (live) setOperation(state); }).catch(() => { if (live) setError('진행 상태를 불러오지 못했습니다. 다시 확인해 주세요.'); });
  }).catch(() => { if (live) setError('문서 설정 상태를 불러오지 못했습니다. 다시 시도해 주세요.'); });
    return () => { live = false; };
  }, []);

  useEffect(() => {
    const operationId = snapshot?.operation?.operationId;
    if (!operationId || ['SUCCEEDED', 'FAILED', 'INTERRUPTED', 'CANCELLED', 'RECONCILIATION_REQUIRED'].includes(operation?.status ?? '')) return;
    let live = true; let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const current = await getSetupOperation(operationId);
        if (!live) return; setOperation(current);
        if (['SUCCEEDED', 'FAILED', 'INTERRUPTED', 'CANCELLED', 'RECONCILIATION_REQUIRED'].includes(current.status)) {
          const updated = await refresh();
          if (current.status === 'SUCCEEDED' && updated.status === 'READY') {
            await Promise.allSettled([getCompanyConfig(), listMeetingParticipants()]);
            setNotice('문서 서비스 연결이 준비되었습니다.'); setCredentials(EMPTY_CREDENTIALS);
          }
          return;
        }
      } catch { /* transient polling failures are recovered by the next public status read */ }
      if (live) timer = setTimeout(poll, 1800);
    };
    timer = setTimeout(poll, 900);
    return () => { live = false; clearTimeout(timer); };
  }, [snapshot?.operation?.operationId, operation?.status]);

  function chooseProvider(next: DocumentProvider) {
    setProvider(next); setCredentials(EMPTY_CREDENTIALS); setLocation(EMPTY_LOCATION); setDraft(null); setTested(null); setOperation(null); setError(''); setNotice('');
  }
  function updateCredential(field: keyof Credentials, value: string) {
    setCredentials((current) => ({ ...current, [field]: value })); setTested(null); setError('');
    const id = field === 'token' ? 'notion-token' : field === 'accountEmail' ? 'confluence-email' : 'confluence-token';
    setFieldErrors((current) => ({ ...current, [id]: '' }));
  }
  function updateLocation(field: keyof Location, value: string) {
    setLocation((current) => ({ ...current, [field]: value })); setTested(null); setError('');
    const id = field === 'parentPageId' ? 'notion-parent' : field === 'baseUrl' ? 'confluence-url' : field === 'spaceId' ? 'confluence-space' : 'confluence-parent';
    setFieldErrors((current) => ({ ...current, [id]: '' }));
  }
  function credentialsValid() {
    return provider === 'NOTION' ? Boolean(credentials.token.trim() && location.parentPageId.trim())
      : Boolean(credentials.accountEmail.trim() && credentials.apiToken.trim() && location.baseUrl.trim() && location.spaceId.trim());
  }
  function inputError(errors: Record<string, string>) {
    setFieldErrors(errors); setError('필수 입력을 확인해 주세요.');
    requestAnimationFrame(() => formRef.current?.querySelector<HTMLInputElement>('[aria-invalid="true"]')?.focus());
  }

  async function handleTest(event: FormEvent) {
    event.preventDefault(); if (busy) return;
    if (!provider) { setError('문서 서비스를 선택해 주세요.'); return; }
    if (!credentialsValid()) {
      const errors: Record<string, string> = {};
      if (provider === 'NOTION') {
        if (!credentials.token.trim()) errors['notion-token'] = 'Notion 내부 연결 토큰을 입력해 주세요.';
        if (!location.parentPageId.trim()) errors['notion-parent'] = '부모 페이지 URL 또는 ID를 입력해 주세요.';
      } else {
        if (!location.baseUrl.trim()) errors['confluence-url'] = 'Confluence 사이트 URL을 입력해 주세요.';
        if (!credentials.accountEmail.trim()) errors['confluence-email'] = 'Atlassian 계정 이메일을 입력해 주세요.';
        if (!credentials.apiToken.trim()) errors['confluence-token'] = 'Atlassian API token을 입력해 주세요.';
        if (!location.spaceId.trim()) errors['confluence-space'] = 'Space ID를 입력해 주세요.';
      }
      inputError(errors); return;
    }
    setBusy(true); setError('');
    try {
      let current = snapshot;
      if (!current) current = await refresh();
      if (current.version == null) throw new DocumentSetupError('STORAGE_UNAVAILABLE', 503);
      const payload: DraftInput = provider === 'NOTION'
        ? { provider, location: { parentPageId: location.parentPageId.trim() }, credentials: { token: credentials.token } }
        : { provider, location: { baseUrl: location.baseUrl.trim(), spaceId: location.spaceId.trim(), ...(location.parentPageIdConfluence.trim() ? { parentPageId: location.parentPageIdConfluence.trim() } : {}) }, credentials: { accountEmail: credentials.accountEmail.trim(), apiToken: credentials.apiToken } };
      const saved = await saveSetupDraft(payload, current.version);
      setDraft({ id: saved.data.draftId, revision: saved.data.revision, version: saved.data.version });
      setCredentials(EMPTY_CREDENTIALS);
      const test = await testSetupDraft(saved.data.draftId, saved.data.revision, saved.data.version);
      setTested(test.data); setSnapshot(await refresh()); setNotice('연결을 확인했습니다. 자격 증명은 화면에서 지웠습니다.');
    } catch (reason) {
      if (reason instanceof DocumentSetupError && reason.status === 412) {
        await refresh(); setTested(null); setError('설정이 다른 브라우저에서 변경되었습니다. 최신 상태를 읽었습니다. 자격 증명을 다시 입력해 연결을 테스트해 주세요.'); setCredentials(EMPTY_CREDENTIALS);
      } else if (reason instanceof DocumentSetupError && reason.code === 'DOCUMENT_FAILED') setError('연결을 확인하지 못했습니다. 입력과 Provider 권한을 확인한 뒤 다시 시도해 주세요.');
      else if (reason instanceof DocumentSetupError && reason.code === 'STORAGE_UNAVAILABLE') setError('저장소 설정을 확인할 수 없습니다. 관리자에게 저장소 설정을 확인해 달라고 요청해 주세요.');
      else setError('연결 테스트에 실패했습니다. 입력값과 네트워크를 확인하고 다시 시도해 주세요.');
    } finally { setBusy(false); }
  }

  async function finish() {
    if (busy || initializing.current) return;
    if (!draft || !tested || tested.revision !== draft.revision || Date.parse(tested.expiresAt) <= Date.now()) { setError('완료 전에 같은 설정 버전의 연결 테스트를 다시 진행해 주세요.'); setTested(null); return; }
    initializing.current = true; setBusy(true); setError('');
    try {
      const current = await refresh();
      if (current.version == null) throw new DocumentSetupError('STORAGE_UNAVAILABLE', 503);
      const result = await initializeSetup(draft.id, draft.revision, current.version);
      setSnapshot(await refresh());
      const state = await getSetupOperation(result.data.operationId); setOperation(state);
      setNotice('문서 구조를 준비하고 있습니다. 이 화면을 다시 열어도 진행 상태를 확인할 수 있습니다.');
    } catch (reason) {
      if (reason instanceof DocumentSetupError && reason.status === 412) { await refresh(); setTested(null); setError('설정 버전이 변경되었습니다. 최신 설정을 다시 읽었습니다. 연결 테스트를 다시 진행해 주세요.'); }
      else setError('문서 구조 초기화를 시작하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.');
    } finally { setBusy(false); initializing.current = false; }
  }

  if (!snapshot) return <main className="setup-page"><p role="status">문서 설정을 불러오는 중…</p></main>;
  if (snapshot.status === 'STORAGE_UNAVAILABLE') return <main className="setup-page"><h1>문서 서비스 연결</h1><section className="setup-card" role="alert"><h2>저장소 설정을 확인할 수 없습니다</h2><p>문서 설정 저장소가 준비되지 않아 문서 기능을 사용할 수 없습니다. 관리자에게 저장소 설정을 확인해 달라고 요청해 주세요.</p><button className="button button-secondary" type="button" onClick={() => void refresh()}>다시 확인</button></section></main>;
  if (snapshot.status === 'READY' && settings && !provider) return <main className="setup-page"><h1>설정</h1><section className="setup-card"><h2>문서 서비스</h2><p>현재 연결: {snapshot.active?.provider === 'NOTION' ? 'Notion' : 'Confluence Cloud'}</p><button className="button button-primary" type="button" onClick={() => chooseProvider(snapshot.active?.provider ?? 'NOTION')}>문서 서비스 변경</button></section><section className="setup-card"><h2>참석자</h2><p>회의에서 사용할 참석자 정보를 관리합니다.</p><Link className="button button-secondary" to="/settings/participants">참석자 관리</Link></section></main>;

  return <main className="setup-page">
    <header className="setup-heading"><p className="eyebrow">DOCUMENT CONNECTION</p><h1>문서 서비스 연결</h1><p>회의록과 참석자를 저장할 문서 서비스를 연결합니다.</p></header>
    {error && <p className="setup-error" role="alert">{error}</p>}{notice && <p className="setup-notice" role="status">{notice}</p>}
    {snapshot.operation && <section className="setup-card" aria-labelledby="operation-heading"><h2 id="operation-heading">문서 구조 준비 상태</h2><p role="status">{operation?.stage ?? snapshot.operation.status} · {operation?.status ?? '상태 확인 중'}</p>{operation && <p>참석자 {operation.counts.participantsCopied}/{operation.counts.participantsTotal}명 · 회의 {operation.counts.meetingsCopied}/{operation.counts.meetingsTotal}건</p>}<button type="button" className="button button-secondary" onClick={() => void refresh()}>진행 상태 새로고침</button></section>}
    {snapshot.status === 'ATTENTION_REQUIRED' && <section className="setup-card" role="status"><h2>이전 작업을 확인해 주세요</h2><p>이전에 시작한 설정 작업이 완료되지 않았습니다. 공개 진행 상태를 새로고침해 확인할 수 있습니다.</p></section>}
    {!snapshot.operation && <>
      {!provider ? <section className="setup-card"><h2>문서 서비스 선택</h2><div className="setup-choice"><button className="button button-secondary" type="button" onClick={() => chooseProvider('NOTION')}>Notion 선택</button><button className="button button-secondary" type="button" onClick={() => chooseProvider('CONFLUENCE')}>Confluence Cloud 선택</button></div></section> : <>
        <section className="setup-card" aria-labelledby="guide-title"><div className="setup-card-heading"><div><h2 id="guide-title">{provider === 'NOTION' ? 'Notion 연결 가이드' : 'Confluence Cloud 연결 가이드'}</h2><p>{provider === 'NOTION' ? '내부 연결을 만들고 대상 부모 페이지에 연결 접근 권한을 부여해 주세요. 읽기·페이지 생성·갱신 권한이 필요합니다.' : '내부 운영용 Atlassian 계정과 API token을 준비하고, 공간 읽기·페이지 생성·갱신 권한을 확인해 주세요.'}</p></div><button className="button button-secondary" type="button" onClick={() => chooseProvider(provider === 'NOTION' ? 'CONFLUENCE' : 'NOTION')}>{provider === 'NOTION' ? 'Confluence Cloud 선택' : 'Notion 선택'}</button></div></section>
        <form ref={formRef} className="setup-card setup-form" onSubmit={(event) => void handleTest(event)} noValidate>
          {provider === 'NOTION' ? <>
            <div className="setup-field"><label htmlFor="notion-token">Notion 내부 연결 토큰</label><input id="notion-token" type="password" autoComplete="new-password" value={credentials.token} onChange={(event) => updateCredential('token', event.target.value)} aria-invalid={Boolean(fieldErrors['notion-token'])} aria-describedby={`notion-token-help${fieldErrors['notion-token'] ? ' notion-token-error' : ''}`} /><small id="notion-token-help">연결 테스트 후 입력한 토큰은 화면에서 지우며 브라우저 저장소에는 저장하지 않습니다.</small>{fieldErrors['notion-token'] && <small className="setup-error" id="notion-token-error" role="alert">{fieldErrors['notion-token']}</small>}</div>
            <div className="setup-field"><label htmlFor="notion-parent">부모 페이지 URL 또는 ID</label><input id="notion-parent" value={location.parentPageId} onChange={(event) => updateLocation('parentPageId', event.target.value)} aria-invalid={Boolean(fieldErrors['notion-parent'])} aria-describedby={fieldErrors['notion-parent'] ? 'notion-parent-error' : undefined} />{fieldErrors['notion-parent'] && <small className="setup-error" id="notion-parent-error" role="alert">{fieldErrors['notion-parent']}</small>}</div>
          </> : <>
            <div className="setup-field"><label htmlFor="confluence-url">Confluence 사이트 URL</label><input id="confluence-url" type="url" placeholder="https://example.atlassian.net" value={location.baseUrl} onChange={(event) => updateLocation('baseUrl', event.target.value)} aria-invalid={Boolean(fieldErrors['confluence-url'])} aria-describedby={fieldErrors['confluence-url'] ? 'confluence-url-error' : undefined} />{fieldErrors['confluence-url'] && <small className="setup-error" id="confluence-url-error" role="alert">{fieldErrors['confluence-url']}</small>}</div>
            <div className="setup-field"><label htmlFor="confluence-email">Atlassian 계정 이메일</label><input id="confluence-email" type="email" autoComplete="off" value={credentials.accountEmail} onChange={(event) => updateCredential('accountEmail', event.target.value)} aria-invalid={Boolean(fieldErrors['confluence-email'])} aria-describedby={fieldErrors['confluence-email'] ? 'confluence-email-error' : undefined} />{fieldErrors['confluence-email'] && <small className="setup-error" id="confluence-email-error" role="alert">{fieldErrors['confluence-email']}</small>}</div>
            <div className="setup-field"><label htmlFor="confluence-token">Atlassian API token</label><input id="confluence-token" type="password" autoComplete="new-password" value={credentials.apiToken} onChange={(event) => updateCredential('apiToken', event.target.value)} aria-invalid={Boolean(fieldErrors['confluence-token'])} aria-describedby={fieldErrors['confluence-token'] ? 'confluence-token-error' : undefined} />{fieldErrors['confluence-token'] && <small className="setup-error" id="confluence-token-error" role="alert">{fieldErrors['confluence-token']}</small>}</div>
            <div className="setup-field"><label htmlFor="confluence-space">Space ID</label><input id="confluence-space" value={location.spaceId} onChange={(event) => updateLocation('spaceId', event.target.value)} aria-invalid={Boolean(fieldErrors['confluence-space'])} aria-describedby={fieldErrors['confluence-space'] ? 'confluence-space-error' : undefined} />{fieldErrors['confluence-space'] && <small className="setup-error" id="confluence-space-error" role="alert">{fieldErrors['confluence-space']}</small>}</div>
            <div className="setup-field"><label htmlFor="confluence-parent">부모 페이지 ID (선택)</label><input id="confluence-parent" value={location.parentPageIdConfluence} onChange={(event) => updateLocation('parentPageIdConfluence', event.target.value)} /></div>
          </>}
          <button className="button button-primary" type="submit" disabled={busy || Boolean(tested)}>{busy ? '연결 확인 중…' : tested ? '연결 확인 완료' : '읽기 전용 연결 테스트'}</button>
        </form>
        {tested && <section className="setup-card"><h2>생성될 구조</h2><p>Meeting Automation 아래 Meetings와 Participants 페이지를 준비합니다. 초기화 완료 전까지 기존 활성 연결은 유지됩니다.</p><p>인증 및 부모 위치 접근을 확인했습니다. 쓰기 권한은 최종 페이지 생성 중 확인됩니다.</p><button className="button button-primary" type="button" disabled={busy} onClick={() => void finish()}>{busy ? '준비 시작 중…' : '구조 생성 및 연결 완료'}</button></section>}
      </>}
    </>}
    {snapshot.status === 'READY' && <p><Link to="/settings/document">문서 서비스 설정으로 돌아가기</Link></p>}
    <button className="setup-back" type="button" onClick={() => { setCredentials(EMPTY_CREDENTIALS); navigate(settings ? '/settings/participants' : '/'); }}>나중에 하기</button>
  </main>;
}

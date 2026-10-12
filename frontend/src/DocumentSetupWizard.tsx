import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { actOnSetupOperation, getSetupOperation, getSetupSnapshot, initializeSetup, saveSetupDraft, switchSetup, testSetupDraft, DocumentSetupError, type DocumentProvider, type DraftInput, type SetupOperation, type SetupSnapshot, type SwitchMode, type TestedDraft } from './documentSetupApi';
import { getDocumentAppConfig, listMeetingParticipants } from './newMeetingApi';

type Credentials = { token: string; accountEmail: string; apiToken: string };
type Location = { parentPageId: string; baseUrl: string; spaceId: string; parentPageIdConfluence: string };
const EMPTY_CREDENTIALS: Credentials = { token: '', accountEmail: '', apiToken: '' };
const EMPTY_LOCATION: Location = { parentPageId: '', baseUrl: '', spaceId: '', parentPageIdConfluence: '' };
const TERMINAL_OPERATION_STATUSES = ['SUCCEEDED', 'FAILED', 'INTERRUPTED', 'CANCELLED', 'RECONCILIATION_REQUIRED'];

function operationTitle(status: string) {
  switch (status) {
    case 'SUCCEEDED': return '문서 구조 준비 완료';
    case 'FAILED': return '문서 구조 준비 실패';
    case 'INTERRUPTED': return '문서 구조 준비 중단';
    case 'CANCELLED': return '문서 구조 준비 취소';
    case 'RECONCILIATION_REQUIRED': return 'Provider 상태 확인 필요';
    default: return '문서 구조 준비 상태';
  }
}

function allowedActionLabel(action: string) {
  switch (action) {
    case 'RETRY': return '재시도 허용';
    case 'CANCEL': return '작업 취소';
    case 'REVIEW_PROVIDER': return 'Provider 확인';
    default: return action;
  }
}

function operationGuidance(operation: SetupOperation) {
  if (operation.type === 'SWITCH') {
    if (operation.status === 'SUCCEEDED') return '새 문서 서비스로 전환했습니다. 원본 자료는 원본 서비스에 보존됩니다.';
    if (operation.status === 'CANCELLED') return '변경을 취소했습니다. 원본 연결이 활성 상태이며 부분 복사본은 대상에 보존됩니다.';
    if (operation.status === 'RECONCILIATION_REQUIRED') return 'Provider 생성 결과가 불명확합니다. 대상 구조를 확인해 주세요. 자동 재시도하지 않습니다.';
    if (operation.cancelRequested) return '취소를 요청했습니다. 안전한 중단 지점에서 멈출 때까지 진행 상태를 확인해 주세요.';
    return '전환 완료 전까지 원본 연결이 유지됩니다. 작업 재개와 취소는 서버가 허용한 경우에만 가능합니다.';
  }
  switch (operation.status) {
    case 'SUCCEEDED': return '문서 구조 준비가 완료되었습니다. 문서 서비스 상태를 새로고침해 확인할 수 있습니다.';
    case 'FAILED': return operation.allowedActions.includes('RETRY')
      ? 'API-027에서 재시도 가능 상태를 확인했습니다. 작업 ID와 오류 코드를 관리자에게 전달해 안전한 재시도를 요청해 주세요. 이 화면은 초기화 요청을 다시 보내지 않습니다.'
      : 'Provider 연결 권한과 대상 페이지 상태를 확인한 뒤 작업 ID와 오류 코드를 관리자에게 전달해 주세요.';
    case 'INTERRUPTED': return operation.allowedActions.includes('RETRY')
      ? '작업이 중단되었습니다. 작업 ID를 관리자에게 전달해 안전한 재개를 요청해 주세요. 이 화면은 새 초기화 요청을 보내지 않습니다.'
      : '작업 ID를 관리자에게 전달해 현재 Provider 구조와 작업 상태를 확인해 주세요.';
    case 'RECONCILIATION_REQUIRED': return 'Provider에서 생성된 Meetings와 Participants 구조를 확인해 주세요. 상태를 확인하기 전에는 자동 재시도하지 말고 작업 ID를 관리자에게 전달해 주세요.';
    case 'CANCELLED': return '초기화 작업이 취소되었습니다. 공개 작업 상태를 새로고침해 확인해 주세요.';
    default: return '공개 작업 상태를 새로고침해 진행 상황을 확인해 주세요.';
  }
}

export function DocumentSetupWizard({ settings = false }: { settings?: boolean }) {
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
  const [initializationUncertain, setInitializationUncertain] = useState(false);
  const formRef = useRef<HTMLFormElement | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const initializing = useRef(false);
  const [changing, setChanging] = useState(false);
  const [mode, setMode] = useState<SwitchMode>('COPY_ALL');
  const [confirmEmpty, setConfirmEmpty] = useState(false);
  const [preparedInvalid, setPreparedInvalid] = useState(false);
  const refreshedConnection = useRef<number | null>(null);
  const progressRefreshRef = useRef<HTMLButtonElement | null>(null);
  const [consumerRefreshAttempt, setConsumerRefreshAttempt] = useState(0);
  const [consumerRefreshFailed, setConsumerRefreshFailed] = useState(false);

  async function refresh() {
    const result = await getSetupSnapshot();
    setSnapshot(result.snapshot);
    if (result.snapshot.operation) {
      try { setOperation(await getSetupOperation(result.snapshot.operation.operationId)); }
      catch { setError('진행 상태를 불러오지 못했습니다. 다시 확인해 주세요.'); }
    } else setOperation(null);
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
    const version = snapshot?.active?.connectionVersion;
    if (operation?.status !== 'SUCCEEDED' || snapshot?.status !== 'READY' || snapshot.draft?.state === 'PREPARED' || version == null || refreshedConnection.current === version) return;
    refreshedConnection.current = version;
    let live = true;
    void Promise.all([getDocumentAppConfig(), listMeetingParticipants()]).then(([config]) => {
      if (!live) return;
      if (config.document && config.document.connectionVersion !== version) throw new Error('STALE_DOCUMENT_CONFIG');
      window.dispatchEvent(new CustomEvent('document-connection-changed', { detail: { connectionVersion: version } }));
      setNotice('문서 서비스 연결이 준비되었습니다.'); setCredentials(EMPTY_CREDENTIALS);
    }).catch(() => { if (live) { refreshedConnection.current = null; setConsumerRefreshFailed(true); setError('새 연결의 설정 또는 참석자 목록을 불러오지 못했습니다. 연결 데이터를 다시 확인해 주세요.'); } });
    return () => { live = false; };
  }, [operation?.status, snapshot?.status, snapshot?.active?.connectionVersion, snapshot?.draft?.state, consumerRefreshAttempt]);

  useEffect(() => {
    const operationId = snapshot?.operation?.operationId;
    if (!operationId) return;
    const status = operation?.operationId === operationId ? operation.status : snapshot?.operation?.status ?? '';
    if (TERMINAL_OPERATION_STATUSES.includes(status) && snapshot?.operation?.status === status) return;
    let live = true; let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const current = await getSetupOperation(operationId);
        if (!live) return; setOperation(current);
        if (TERMINAL_OPERATION_STATUSES.includes(current.status)) {
          await refresh();
          return;
        }
      } catch { if (live) setError('진행 상태를 불러오지 못했습니다. 진행 상태 새로고침으로 다시 확인해 주세요.'); }
      if (live) timer = setTimeout(poll, 1800);
    };
    timer = setTimeout(poll, TERMINAL_OPERATION_STATUSES.includes(status) ? 0 : 900);
    return () => { live = false; clearTimeout(timer); };
  }, [snapshot?.operation?.operationId, snapshot?.operation?.status, operation?.operationId, operation?.status]);

  function chooseProvider(next: DocumentProvider) {
    setProvider(next); setCredentials(EMPTY_CREDENTIALS); setLocation(EMPTY_LOCATION); setDraft(null); setTested(null); setOperation(null); setError(''); setNotice(''); setFieldErrors({});
    requestAnimationFrame(() => formRef.current?.querySelector<HTMLInputElement>('input')?.focus());
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
      setTested(test.data); setPreparedInvalid(false); setSnapshot(await refresh()); setNotice('연결을 확인했습니다. 자격 증명은 화면에서 지웠습니다.');
    } catch (reason) {
      if (reason instanceof DocumentSetupError && reason.status === 412) {
        const latest = await refresh(); setTested(null); setPreparedInvalid(true); setError(`설정이 다른 브라우저에서 변경되었습니다. 최신 상태를 읽었습니다. 설정 버전: ${latest.version}, draft revision: ${latest.draft?.revision ?? '없음'}. 자격 증명을 다시 입력해 연결을 테스트해 주세요.`); setCredentials(EMPTY_CREDENTIALS);
      } else if (reason instanceof DocumentSetupError && reason.code === 'DOCUMENT_FAILED') setError('연결을 확인하지 못했습니다. 입력과 Provider 권한을 확인한 뒤 다시 시도해 주세요.');
      else if (reason instanceof DocumentSetupError && reason.code === 'STORAGE_UNAVAILABLE') setError('저장소 설정을 확인할 수 없습니다. 관리자에게 저장소 설정을 확인해 달라고 요청해 주세요.');
      else setError('연결 테스트에 실패했습니다. 입력값과 네트워크를 확인하고 다시 시도해 주세요.');
    } finally { setBusy(false); }
  }

  async function finish() {
    if (busy || initializing.current) return;
    if (!draft || !tested || tested.revision !== draft.revision || Date.parse(tested.expiresAt) <= Date.now()) { setError('완료 전에 같은 설정 버전의 연결 테스트를 다시 진행해 주세요.'); setTested(null); return; }
    initializing.current = true; setBusy(true); setError('');
    let initializationSent = false;
    let acceptedOperationId: string | null = null;
    try {
      const current = await refresh();
      if (current.version == null) throw new DocumentSetupError('STORAGE_UNAVAILABLE', 503);
      initializationSent = true;
      const result = await initializeSetup(draft.id, draft.revision, current.version);
      acceptedOperationId = result.data.operationId;
      setSnapshot(await refresh());
      const state = await getSetupOperation(result.data.operationId); setOperation(state);
      setNotice('문서 구조를 준비하고 있습니다. 이 화면을 다시 열어도 진행 상태를 확인할 수 있습니다.');
    } catch (reason) {
      if (reason instanceof DocumentSetupError && reason.status === 412) {
        await refresh(); setTested(null); setError('설정 버전이 변경되었습니다. 최신 설정을 다시 읽었습니다. 연결 테스트를 다시 진행해 주세요.');
      } else if (initializationSent) {
        setTested(null); setCredentials(EMPTY_CREDENTIALS);
        try {
          const latest = await refresh();
          if (latest.operation) {
            setInitializationUncertain(false);
            setNotice('이미 진행 중인 문서 구조 준비 작업을 다시 확인했습니다.');
            setError('');
          } else if (acceptedOperationId) {
            const accepted = await getSetupOperation(acceptedOperationId);
            setOperation(accepted);
            setSnapshot({ ...latest, operation: { operationId: accepted.operationId, type: accepted.type, status: accepted.status } });
            setInitializationUncertain(false);
            setNotice('응답에서 확인한 작업 ID로 진행 상태를 다시 불러왔습니다.');
          } else {
            setInitializationUncertain(true);
            setError('완료 요청 결과가 불명확합니다. 다시 확인을 눌러 서버의 공개 작업 상태를 확인해 주세요.');
          }
        } catch {
          setInitializationUncertain(true);
          setError('완료 요청 결과를 확인할 수 없습니다. 다시 확인을 눌러 서버의 공개 작업 상태를 확인해 주세요.');
        }
      } else setError('문서 구조 초기화를 시작하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.');
    } finally { setBusy(false); initializing.current = false; }
  }

  async function recheckUncertainInitialization() {
    setBusy(true); setError('');
    try {
      const latest = await refresh();
      if (latest.operation && (!changing || latest.operation.type === 'SWITCH')) {
        setInitializationUncertain(false);
        setNotice('공개 작업 상태를 다시 확인했습니다.');
      } else {
        setInitializationUncertain(false);
        setPreparedInvalid(true); setTested(null); setProvider(null);
        setNotice(changing ? '진행 중인 변경 작업이 없습니다. 연결 테스트를 다시 진행해 주세요.' : '진행 중인 초기화 작업이 없습니다. 연결 테스트를 다시 진행해 주세요.');
      }
    } catch {
      setError('공개 작업 상태를 불러오지 못했습니다. 잠시 후 다시 확인해 주세요.');
    } finally { setBusy(false); }
  }

  function beginChange() {
    setChanging(true); setProvider(null); setDraft(null); setTested(null); setCredentials(EMPTY_CREDENTIALS); setLocation(EMPTY_LOCATION); setError(''); setNotice(''); setMode('COPY_ALL'); setConfirmEmpty(false);
  }

  async function reloadConflict() {
    const latest = await refresh(); setTested(null); setPreparedInvalid(true); setCredentials(EMPTY_CREDENTIALS); setProvider(null);
    setError(`설정 버전이 변경되었습니다. 최신 설정 버전: ${latest.version}, draft revision: ${latest.draft?.revision ?? '없음'}. 연결 테스트를 다시 진행해 주세요.`);
  }

  async function startSwitch() {
    if (busy || initializing.current || initializationUncertain || preparedInvalid || snapshot?.draft?.state !== 'PREPARED' || snapshot.version == null || mode === 'START_EMPTY' && !confirmEmpty) return;
    initializing.current = true; setBusy(true); setError('');
    let acceptedId: string | null = null;
    try {
      const accepted = await switchSetup(snapshot.draft.draftId, snapshot.draft.revision, mode, snapshot.version);
      acceptedId = accepted.data.operationId;
      const latest = await refresh();
      if (!latest.operation || latest.operation.operationId !== acceptedId) {
        const state = await getSetupOperation(acceptedId); setOperation(state); setSnapshot({ ...latest, operation: { operationId: state.operationId, type: state.type, status: state.status } });
      }
      setNotice('문서 서비스 변경을 시작했습니다. 전환 완료 전까지 원본 연결을 유지합니다.');
    } catch (reason) {
      if (reason instanceof DocumentSetupError && reason.status === 412) {
        try { await reloadConflict(); } catch { setError('최신 설정 상태를 불러오지 못했습니다. 진행 상태를 다시 확인해 주세요.'); }
      } else {
        setInitializationUncertain(true);
        try {
          const latest = await refresh();
          if (latest.operation?.type === 'SWITCH' && !['SUCCEEDED', 'CANCELLED'].includes(latest.operation.status)) setInitializationUncertain(false);
          else if (acceptedId) {
            const state = await getSetupOperation(acceptedId); setOperation(state); setSnapshot({ ...latest, operation: { operationId: state.operationId, type: state.type, status: state.status } }); setInitializationUncertain(false);
          }
        } catch { /* Require an explicit status read when dispatch outcome is unknown. */ }
        setError('변경 요청 결과를 확인해 주세요. 새 전환을 보내기 전에 공개 작업 상태를 다시 확인해야 합니다.');
      }
    } finally { initializing.current = false; setBusy(false); }
  }

  async function runOperationAction(action: 'RETRY' | 'CANCEL') {
    if (busy || initializing.current || operation?.type !== 'SWITCH' || !operation.allowedActions.includes(action) || action === 'RETRY' && operation.status === 'RECONCILIATION_REQUIRED') return;
    initializing.current = true; setBusy(true); setError(''); setChanging(true);
    try {
      await actOnSetupOperation(operation.operationId, action, operation.version);
      await refresh(); setNotice(action === 'RETRY' ? '같은 작업을 재개했습니다.' : '작업 취소를 요청했습니다.');
    } catch (reason) {
      if (reason instanceof DocumentSetupError && reason.status === 412) {
        try { await reloadConflict(); } catch { setError('최신 작업 상태를 불러오지 못했습니다. 다시 확인해 주세요.'); }
      } else {
        try { await refresh(); } catch { /* Preserve the visible operation and recovery action. */ }
        setError('작업 요청 결과를 확인하지 못했습니다. 진행 상태를 새로고침해 확인해 주세요.');
      }
    } finally { setBusy(false); initializing.current = false; requestAnimationFrame(() => progressRefreshRef.current?.focus()); }
  }

  async function safeRefresh() {
    setError('');
    try { await refresh(); } catch { setError('진행 상태를 불러오지 못했습니다. 다시 확인해 주세요.'); }
  }

  const retryConsumerRefresh = <button className="button button-secondary" type="button" onClick={() => { setError(''); setConsumerRefreshFailed(false); setConsumerRefreshAttempt((attempt) => attempt + 1); }}>연결 데이터 다시 확인</button>;

  if (!snapshot) return <main className="setup-page">{error ? <section role="alert"><p>{error}</p><button className="button button-secondary" type="button" onClick={() => void safeRefresh()}>다시 확인</button></section> : <p role="status">문서 설정을 불러오는 중…</p>}</main>;
  if (snapshot.status === 'STORAGE_UNAVAILABLE') return <main className="setup-page"><h1>문서 서비스 연결</h1><section className="setup-card" role="alert"><h2>저장소 설정을 확인할 수 없습니다</h2><p>문서 설정 저장소가 준비되지 않아 문서 기능을 사용할 수 없습니다. 관리자에게 저장소 설정을 확인해 달라고 요청해 주세요.</p><button className="button button-secondary" type="button" onClick={() => void refresh()}>다시 확인</button></section></main>;
  const activeSummary = snapshot.active && <section className="setup-card" aria-label="현재 활성 문서 연결"><h2>문서 서비스</h2><p>현재 연결: {snapshot.active.provider === 'NOTION' ? 'Notion' : 'Confluence Cloud'}</p><p>연결 버전: {snapshot.active.connectionVersion}</p><a href={snapshot.active.rootUrl} target="_blank" rel="noreferrer">활성 문서 루트 열기</a></section>;
  if (snapshot.status === 'READY' && settings && !changing) return <main className="setup-page"><h1>설정</h1>{activeSummary}{error && <p role="alert" className="setup-error">{error}</p>}{consumerRefreshFailed && retryConsumerRefresh}{notice && <p role="status">{notice}</p>}<button className="button button-primary" type="button" onClick={beginChange}>문서 서비스 변경</button><section className="setup-card"><h2>참석자</h2><p>회의에서 사용할 참석자 정보를 관리합니다.</p><Link className="button button-secondary" to="/settings/participants">참석자 관리</Link></section></main>;

  const operationStatus = operation?.status ?? snapshot.operation?.status ?? '';
  const preparedTarget = snapshot.active && snapshot.draft?.state === 'PREPARED' && snapshot.status === 'READY' && !preparedInvalid;
  const hasBlockingOperation = snapshot.operation && !['SUCCEEDED', 'CANCELLED'].includes(operationStatus);

  return <main className="setup-page">
    <header className="setup-heading"><p className="eyebrow">DOCUMENT CONNECTION</p><h1>문서 서비스 연결</h1><p>회의록과 참석자를 저장할 문서 서비스를 연결합니다.</p></header>
    {activeSummary}
    {error && <p className="setup-error" role="alert">{error}</p>}{notice && <p className="setup-notice" role="status">{notice}</p>}
    {consumerRefreshFailed && retryConsumerRefresh}
    {snapshot.operation && <section className="setup-card" aria-labelledby="operation-heading"><h2 id="operation-heading">{operation?.type === 'SWITCH' ? `문서 서비스 변경 · ${operationStatus}` : operationTitle(operationStatus)}</h2><p role="status">{operation?.stage ?? snapshot.operation.status} · {operation?.status ?? '상태 확인 중'}</p><p>작업 ID: <code>{snapshot.operation.operationId}</code></p>{operation && <><p role="status">참석자 {operation.counts.participantsCopied}/{operation.counts.participantsTotal}명 · 회의 {operation.counts.meetingsCopied}/{operation.counts.meetingsTotal}건</p>{operation.errorCode && <p>오류 코드: <code>{operation.errorCode}</code></p>}<p>{operationGuidance(operation)}</p>{operation.type === 'SWITCH' ? <div className="setup-choice">{operation.allowedActions.includes('RETRY') && operation.status !== 'RECONCILIATION_REQUIRED' && <button className="button button-secondary" type="button" disabled={busy} onClick={() => void runOperationAction('RETRY')}>작업 재개</button>}{operation.allowedActions.includes('CANCEL') && <button className="button button-secondary" type="button" disabled={busy} onClick={() => void runOperationAction('CANCEL')}>작업 취소</button>}{operation.allowedActions.includes('REVIEW_PROVIDER') && <p>Provider 확인: 대상 구조를 확인한 뒤 공개 상태를 다시 읽어 주세요.</p>}</div> : <><h3>서버가 허용한 작업 (이 화면에서 실행되지 않음)</h3>{operation.allowedActions.length > 0 ? <ul>{operation.allowedActions.map((action) => <li key={action}>{allowedActionLabel(action)}</li>)}</ul> : <p>가능한 추가 작업이 없습니다.</p>}</>}</>}<button ref={progressRefreshRef} type="button" className="button button-secondary" disabled={busy} onClick={() => void safeRefresh()}>진행 상태 새로고침</button></section>}
    {snapshot.status === 'ATTENTION_REQUIRED' && <section className="setup-card" role="status"><h2>이전 작업을 확인해 주세요</h2><p>이전에 시작한 설정 작업이 완료되지 않았습니다. 공개 진행 상태를 새로고침해 확인할 수 있습니다.</p></section>}
    {preparedTarget && !initializationUncertain && <section className="setup-card" aria-labelledby="migration-heading"><h2 id="migration-heading">자료 이전 방식</h2><p>대상 연결: {snapshot.draft?.provider === 'NOTION' ? 'Notion' : 'Confluence Cloud'}</p><p>참석자와 회의록의 Transcript, Minutes, 참석자 참조를 전체 복사합니다. 첨부·댓글·버전 이력은 포함되지 않으며 이메일과 Slack 알림을 다시 보내지 않습니다.</p><p>원본 자료는 원본 서비스에 보존됩니다. 전환이 완료된 뒤 새 서비스의 목록을 사용합니다.</p><fieldset className="setup-mode"><legend>이전 방식 선택</legend><label><input type="radio" name="switch-mode" checked={mode === 'COPY_ALL'} onChange={() => { setMode('COPY_ALL'); setConfirmEmpty(false); }} />전체 복사</label><label><input type="radio" name="switch-mode" checked={mode === 'START_EMPTY'} onChange={() => { setMode('START_EMPTY'); setConfirmEmpty(false); }} />빈 구조로 새 시작</label></fieldset>{mode === 'START_EMPTY' && <label className="setup-confirm"><input type="checkbox" checked={confirmEmpty} onChange={(event) => setConfirmEmpty(event.target.checked)} />기존 자료가 새 서비스 목록에 나타나지 않음을 확인했습니다. 자료는 원본에 보존됩니다.</label>}<button className="button button-primary" type="button" disabled={busy || mode === 'START_EMPTY' && !confirmEmpty} onClick={() => void startSwitch()}>{busy ? '전환 시작 중…' : '문서 서비스 전환'}</button></section>}
    {!hasBlockingOperation && !preparedTarget && !initializationUncertain && <>
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
    {snapshot.status === 'READY' && <p><Link to="/settings/document" onClick={() => { setChanging(false); setCredentials(EMPTY_CREDENTIALS); }}>문서 서비스 설정으로 돌아가기</Link></p>}
    {initializationUncertain && <section className="setup-card" aria-labelledby="uncertain-operation-title"><h2 id="uncertain-operation-title">요청 상태 확인</h2><p>요청 결과가 불명확하므로 새 요청을 보내지 않습니다. 서버의 공개 작업 상태를 먼저 확인해 주세요.</p><button className="button button-secondary" type="button" onClick={() => void recheckUncertainInitialization()} disabled={busy}>{busy ? '확인 중…' : '다시 확인'}</button></section>}
  </main>;
}

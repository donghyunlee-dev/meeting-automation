import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { actOnSetupOperation, getSetupOperation, getSetupSnapshot, initializeSetup, saveSetupDraft, switchSetup, testSetupDraft, DocumentSetupError, type DocumentProvider, type DraftInput, type SetupOperation, type SetupSnapshot, type SwitchMode, type TestedDraft } from './documentSetupApi';
import { savePendingDocumentConnection, saveReadyDocumentConnection } from './documentConnectionStorage';
import { getDocumentAppConfig, listMeetingParticipants } from './newMeetingApi';
import { Toast } from './components/Toast';
import { useToast } from './components/useToast';

type Credentials = { token: string; accountEmail: string; apiToken: string };
type Location = { parentPageId: string; baseUrl: string; spaceId: string };
const EMPTY_CREDENTIALS: Credentials = { token: '', accountEmail: '', apiToken: '' };
const EMPTY_LOCATION: Location = { parentPageId: '', baseUrl: '', spaceId: '' };
const TERMINAL_OPERATION_STATUSES = ['SUCCEEDED', 'FAILED', 'INTERRUPTED', 'CANCELLED', 'RECONCILIATION_REQUIRED'];

function ProviderIcon({ provider }: { provider: DocumentProvider }) {
  return <span className={`setup-provider-icon setup-provider-icon-${provider.toLowerCase()}`} aria-hidden="true">
    <img src={provider === 'NOTION' ? '/assets/notion.ico' : '/assets/confluence.svg'} alt="" />
  </span>;
}

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

function operationStageLabel(stage: string | undefined) {
  switch (stage) {
    case 'PREFLIGHT': return '연결 확인';
    case 'STRUCTURE': return '문서 생성';
    case 'VERIFY': return '생성 문서 확인';
    case 'ACTIVATE': return '연결 완료';
    default: return '작업 상태';
  }
}

function operationStatusLabel(status: string | undefined) {
  switch (status) {
    case 'PENDING': return '대기 중';
    case 'RUNNING': return '진행 중';
    case 'SUCCEEDED': return '완료';
    case 'FAILED': return '실패';
    case 'INTERRUPTED': return '중단됨';
    case 'CANCELLED': return '종료됨';
    case 'RECONCILIATION_REQUIRED': return '확인 필요';
    default: return '상태 확인 중';
  }
}

function providerLabel(provider: DocumentProvider | null | undefined) {
  return provider === 'NOTION' ? 'Notion' : provider === 'CONFLUENCE' ? 'Confluence' : '문서 서비스';
}

function structureStepLabel(step: string | null | undefined) {
  switch (step) {
    case 'ROOT': return 'Meeting Automation';
    case 'MEETINGS': return 'Meetings';
    case 'PARTICIPANTS': return 'Participants';
    case 'MEETING_DATA': return 'Meeting Data';
    case 'TEMPLATES': return 'Templates';
    default: return '문서 구조';
  }
}

function initializationProgressMessage(operation: SetupOperation | null, provider: DocumentProvider | null) {
  const service = providerLabel(provider);
  if (!operation) return `${service} 연결 작업 상태를 확인하고 있습니다.`;
  if (operation.status === 'PENDING') return `${service} 연결 작업을 시작하고 있습니다.`;
  if (operation.stage === 'PREFLIGHT') return `${service} 연결 상태를 다시 확인하고 있습니다.`;
  if (operation.stage === 'STRUCTURE') return `${service}에 ${structureStepLabel(operation.currentStep)} 페이지를 생성하고 있습니다.`;
  if (operation.stage === 'VERIFY') return `${service}에 생성된 페이지 구조를 확인하고 있습니다.`;
  if (operation.stage === 'ACTIVATE') return `${service} 연결을 완료하고 있습니다.`;
  return `${service} 연결 작업을 진행하고 있습니다.`;
}

const STRUCTURE_STEPS = ['ROOT', 'MEETINGS', 'PARTICIPANTS', 'MEETING_DATA', 'TEMPLATES'];

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
      ? '작업을 재개할 수 있습니다. 재개 후 문서 서비스의 기존 페이지를 확인해 중복 생성을 막습니다.'
      : 'Provider 연결 권한과 대상 페이지 상태를 확인한 뒤 작업 ID와 오류 코드를 관리자에게 전달해 주세요.';
    case 'INTERRUPTED': return '연결 작업이 중단되었습니다. 일부 페이지가 생성되었을 수 있으니 문서 서비스에서 확인한 뒤 작업을 재개하거나 종료해 주세요.';
    case 'RECONCILIATION_REQUIRED': return '문서 서비스에 생성된 페이지가 있는지 확인한 뒤 작업을 재개하거나 종료해 주세요. 확인 전에는 새 페이지를 자동으로 만들지 않습니다.';
    case 'CANCELLED': return '초기화 작업이 취소되었습니다. 공개 작업 상태를 새로고침해 확인해 주세요.';
    default: return '공개 작업 상태를 새로고침해 진행 상황을 확인해 주세요.';
  }
}

export function DocumentSetupWizard({ settings = false }: { settings?: boolean }) {
  const { toastMessage, showToast } = useToast();
  const route = useLocation();
  const navigate = useNavigate();
  const returnState = route.state as { returnTo?: unknown; returnState?: unknown } | null;
  const returnTo = typeof returnState?.returnTo === 'string' && returnState.returnTo.startsWith('/meetings/') && !returnState.returnTo.startsWith('//')
    ? returnState.returnTo : null;
  const [snapshot, setSnapshot] = useState<SetupSnapshot | null>(null);
  const [provider, setProvider] = useState<DocumentProvider | null>(null);
  const [credentials, setCredentials] = useState(EMPTY_CREDENTIALS);
  const [location, setLocation] = useState(EMPTY_LOCATION);
  const [draft, setDraft] = useState<{ id: string; revision: number; version: number } | null>(null);
  const [tested, setTested] = useState<TestedDraft | null>(null);
  const [setupProgressStarted, setSetupProgressStarted] = useState(false);
  const [operation, setOperation] = useState<SetupOperation | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [operationReadFailed, setOperationReadFailed] = useState(false);
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
  const [guideOpen, setGuideOpen] = useState(false);
  const guideDialogRef = useRef<HTMLDialogElement | null>(null);

  useEffect(() => {
    const dialog = guideDialogRef.current;
    if (!dialog) return;
    if (guideOpen && !dialog.open) dialog.showModal();
    else if (!guideOpen && dialog.open) dialog.close();
  }, [guideOpen]);

  async function refresh() {
    const previousOperation = snapshot?.operation;
    const result = await getSetupSnapshot();
    setSnapshot(result.snapshot);
    if (result.snapshot.operation) {
      try {
        setOperation(await getSetupOperation(result.snapshot.operation.operationId));
        setOperationReadFailed(false);
        setError((current) => current.startsWith('진행 상세 정보를 불러오지 못했습니다.') ? '' : current);
      } catch {
        setOperationReadFailed(true);
        setError('진행 상세 정보를 불러오지 못했습니다. 백엔드 연결을 확인한 뒤 상태를 다시 불러와 주세요.');
      }
    } else {
      setOperation(null); setOperationReadFailed(false);
      if (previousOperation?.type === 'INITIALIZE') {
        setProvider(null); setCredentials(EMPTY_CREDENTIALS); setLocation(EMPTY_LOCATION);
        setDraft(null); setTested(null); setSetupProgressStarted(false); setInitializationUncertain(false);
        setError(''); setNotice(''); setFieldErrors({});
      }
    }
    return result.snapshot;
  }

  useEffect(() => { let live = true; void getSetupSnapshot().then(({ snapshot: current }) => {
    if (!live) return; setSnapshot(current);
    if (current.draft) { setProvider(current.draft.provider); setDraft({ id: current.draft.draftId, revision: current.draft.revision, version: current.version ?? 0 }); }
    if (current.operation) void getSetupOperation(current.operation.operationId).then((state) => { if (live) { setOperation(state); setOperationReadFailed(false); } }).catch(() => { if (live) { setOperationReadFailed(true); setError('진행 상세 정보를 불러오지 못했습니다. 백엔드 연결을 확인한 뒤 상태를 다시 불러와 주세요.'); } });
  }).catch(() => { if (live) setError('문서 설정 상태를 불러오지 못했습니다. 다시 시도해 주세요.'); });
    return () => { live = false; };
  }, []);

  useEffect(() => {
    const operationId = snapshot?.operation?.operationId;
    const status = operation?.operationId === operationId ? operation?.status : snapshot?.operation?.status;
    if (!operationId || !status || !TERMINAL_OPERATION_STATUSES.includes(status)) return;
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'hidden') return;
      void refresh().catch(() => setError('문서 설정 상태를 불러오지 못했습니다. 다시 시도해 주세요.'));
    };
    window.addEventListener('focus', refreshWhenVisible);
    document.addEventListener('visibilitychange', refreshWhenVisible);
    return () => {
      window.removeEventListener('focus', refreshWhenVisible);
      document.removeEventListener('visibilitychange', refreshWhenVisible);
    };
  }, [snapshot?.operation?.operationId, snapshot?.operation?.status, operation?.operationId, operation?.status]);

  useEffect(() => {
    const version = snapshot?.active?.connectionVersion;
    const rootId = snapshot?.active?.rootId;
    if (operation?.status !== 'SUCCEEDED' || snapshot?.status !== 'READY' || snapshot.draft?.state === 'PREPARED'
      || version == null || !rootId || refreshedConnection.current === version) return;
    refreshedConnection.current = version;
    let live = true;
    void (async () => {
      try {
        saveReadyDocumentConnection(rootId, version);
      } catch {
        if (live) {
          refreshedConnection.current = null;
          setError('연결 정보가 이 브라우저에 저장되지 않았습니다. 브라우저 저장소를 확인해 주세요.');
        }
        return;
      }
      try {
        const [config] = await Promise.all([getDocumentAppConfig(), listMeetingParticipants()]);
        if (!live) return;
        if (config.document && config.document.connectionVersion !== version) throw new Error('STALE_DOCUMENT_CONFIG');
        window.dispatchEvent(new CustomEvent('document-connection-changed', { detail: { connectionVersion: version } }));
        showToast('문서 서비스 연결이 준비되었습니다.'); setCredentials(EMPTY_CREDENTIALS);
      } catch {
        if (live) { refreshedConnection.current = null; setConsumerRefreshFailed(true); setError('새 연결의 설정 또는 참석자 목록을 불러오지 못했습니다. 연결 데이터를 다시 확인해 주세요.'); }
      }
    })();
    return () => { live = false; };
  }, [operation?.status, snapshot?.status, snapshot?.active?.connectionVersion, snapshot?.active?.rootId,
    snapshot?.draft?.state, consumerRefreshAttempt, showToast]);

  useEffect(() => {
    const operationId = snapshot?.operation?.operationId;
    if (!operationId) return;
    const status = operation?.operationId === operationId ? operation?.status : snapshot?.operation?.status ?? '';
    if (TERMINAL_OPERATION_STATUSES.includes(status) && snapshot?.operation?.status === status) return;
    let live = true; let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const current = await getSetupOperation(operationId);
        if (!live) return; setOperation(current); setOperationReadFailed(false);
        setError((message) => message.startsWith('진행 상세 정보를 불러오지 못했습니다.') ? '' : message);
        if (TERMINAL_OPERATION_STATUSES.includes(current.status)) {
          await refresh();
          return;
        }
      } catch { if (live) { setOperationReadFailed(true); setError('진행 상세 정보를 불러오지 못했습니다. 백엔드 연결을 확인한 뒤 상태를 다시 불러와 주세요.'); } }
      if (live) timer = setTimeout(poll, 1800);
    };
    timer = setTimeout(poll, TERMINAL_OPERATION_STATUSES.includes(status) ? 0 : 900);
    return () => { live = false; clearTimeout(timer); };
  }, [snapshot?.operation?.operationId, snapshot?.operation?.status, operation?.operationId, operation?.status]);

  function chooseProvider(next: DocumentProvider | null) {
    setProvider(next); setCredentials(EMPTY_CREDENTIALS); setLocation(EMPTY_LOCATION); setDraft(null); setTested(null); setOperation(null); setSetupProgressStarted(false); setError(''); setNotice(''); setFieldErrors({}); setGuideOpen(false);
    if (next) requestAnimationFrame(() => formRef.current?.querySelector<HTMLInputElement>('input')?.focus());
  }
  function updateCredential(field: keyof Credentials, value: string) {
    setCredentials((current) => ({ ...current, [field]: value })); setTested(null); setError('');
    const id = field === 'token' ? 'notion-token' : field === 'accountEmail' ? 'confluence-email' : 'confluence-token';
    setFieldErrors((current) => ({ ...current, [id]: '' }));
  }
  function updateLocation(field: keyof Location, value: string) {
    setLocation((current) => ({ ...current, [field]: value })); setTested(null); setError('');
    const id = field === 'parentPageId' ? 'notion-parent' : field === 'baseUrl' ? 'confluence-url' : 'confluence-space';
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
        : { provider, location: { baseUrl: location.baseUrl.trim(), spaceId: location.spaceId.trim() }, credentials: { accountEmail: credentials.accountEmail.trim(), apiToken: credentials.apiToken } };
      const saved = await saveSetupDraft(payload, current.version);
      setDraft({ id: saved.data.draftId, revision: saved.data.revision, version: saved.data.version });
      setCredentials(EMPTY_CREDENTIALS);
      const test = await testSetupDraft(saved.data.draftId, saved.data.revision, saved.data.version);
      if (test.data.draftId !== saved.data.draftId || test.data.revision !== saved.data.revision
        || !test.data.authenticated || !test.data.parentAccessible
        || !Number.isFinite(Date.parse(test.data.expiresAt))) {
        setTested(test.data);
        setError('연결 확인 결과가 현재 설정과 일치하지 않습니다. 다시 연결 확인해 주세요.');
        return;
      }
      try { savePendingDocumentConnection(payload); }
      catch { throw new DocumentSetupError('BROWSER_STORAGE_UNAVAILABLE', 0); }
      setTested(test.data); setPreparedInvalid(false);
      const latest = await refresh();
      showToast('연결을 확인했습니다. 자격 증명은 화면에서 지웠습니다.');
      if (!latest.active || (settings && changing)) {
        setSetupProgressStarted(true);
        await finish({ id: saved.data.draftId, revision: saved.data.revision, version: saved.data.version }, test.data, true);
      }
    } catch (reason) {
      if (reason instanceof DocumentSetupError && reason.status === 412) {
        const latest = await refresh(); setTested(null); setPreparedInvalid(true); setError(`설정이 다른 브라우저에서 변경되었습니다. 최신 상태를 읽었습니다. 설정 버전: ${latest.version}, draft revision: ${latest.draft?.revision ?? '없음'}. 자격 증명을 다시 입력해 연결을 확인해 주세요.`); setCredentials(EMPTY_CREDENTIALS);
      } else if (reason instanceof DocumentSetupError && reason.code === 'DOCUMENT_FAILED') setError('연결을 확인하지 못했습니다. 입력과 Provider 권한을 확인한 뒤 다시 시도해 주세요.');
      else if (reason instanceof DocumentSetupError && reason.code === 'STORAGE_UNAVAILABLE') setError('저장소 설정을 확인할 수 없습니다. 관리자에게 저장소 설정을 확인해 달라고 요청해 주세요.');
      else if (reason instanceof DocumentSetupError && reason.code === 'BROWSER_STORAGE_UNAVAILABLE') setError('연결은 확인했지만 브라우저에 연결 정보를 저장하지 못했습니다. 브라우저 저장소를 허용하고 다시 연결해 주세요.');
      else setError('연결을 확인하지 못했습니다. 입력값과 네트워크를 확인하고 다시 시도해 주세요.');
    } finally { setBusy(false); }
  }

  async function finish(draftToInitialize = draft, testedDraft = tested, allowWhileBusy = false) {
    if (busy && !allowWhileBusy || initializing.current) return;
    if (!draftToInitialize || !testedDraft || testedDraft.revision !== draftToInitialize.revision || Date.parse(testedDraft.expiresAt) <= Date.now()) { setError('완료 전에 같은 설정 버전으로 연결을 다시 확인해 주세요.'); setTested(null); setSetupProgressStarted(false); return; }
    initializing.current = true; setBusy(true); setError('');
    let initializationSent = false;
    let acceptedOperationId: string | null = null;
    try {
      const current = await refresh();
      if (current.version == null) throw new DocumentSetupError('STORAGE_UNAVAILABLE', 503);
      initializationSent = true;
      const result = await initializeSetup(draftToInitialize.id, draftToInitialize.revision, current.version);
      acceptedOperationId = result.data.operationId;
      setSnapshot(await refresh());
      const state = await getSetupOperation(result.data.operationId); setOperation(state);
      setNotice('');
    } catch (reason) {
      if (reason instanceof DocumentSetupError && reason.status === 412) {
        await refresh(); setTested(null); setError('설정 버전이 변경되었습니다. 최신 설정을 다시 읽었습니다. 연결 확인을 다시 진행해 주세요.');
      } else if (initializationSent) {
        setTested(null); setCredentials(EMPTY_CREDENTIALS);
        try {
          const latest = await refresh();
          if (latest.operation) {
            setInitializationUncertain(false);
            showToast('이미 진행 중인 문서 구조 준비 작업을 다시 확인했습니다.');
            setError('');
          } else if (acceptedOperationId) {
            const accepted = await getSetupOperation(acceptedOperationId);
            setOperation(accepted);
            setSnapshot({ ...latest, operation: { operationId: accepted.operationId, type: accepted.type, status: accepted.status } });
            setInitializationUncertain(false);
            showToast('응답에서 확인한 작업 ID로 진행 상태를 다시 불러왔습니다.');
          } else {
            setInitializationUncertain(true);
            setError('완료 요청 결과가 불명확합니다. 다시 확인을 눌러 서버의 공개 작업 상태를 확인해 주세요.');
          }
        } catch {
          setInitializationUncertain(true);
          setError('완료 요청 결과를 확인할 수 없습니다. 다시 확인을 눌러 서버의 공개 작업 상태를 확인해 주세요.');
        }
      } else setError('문서 구조 초기화를 시작하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.');
      if (!initializationSent) setSetupProgressStarted(false);
    } finally { setBusy(false); initializing.current = false; }
  }

  async function recheckUncertainInitialization() {
    setBusy(true); setError('');
    try {
      const latest = await refresh();
      if (latest.operation && (!changing || latest.operation.type === 'SWITCH')) {
        setInitializationUncertain(false);
        showToast('공개 작업 상태를 다시 확인했습니다.');
      } else {
        setInitializationUncertain(false);
        setPreparedInvalid(true); setTested(null); setProvider(null);
        setNotice(changing ? '진행 중인 변경 작업이 없습니다. 연결 확인을 다시 진행해 주세요.' : '진행 중인 초기화 작업이 없습니다. 연결 확인을 다시 진행해 주세요.');
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
    setError(`설정 버전이 변경되었습니다. 최신 설정 버전: ${latest.version}, draft revision: ${latest.draft?.revision ?? '없음'}. 연결 확인을 다시 진행해 주세요.`);
  }

  async function startSwitch() {
    if (busy || initializing.current || initializationUncertain || preparedInvalid || snapshot?.draft?.state !== 'PREPARED' || snapshot.version == null || mode === 'START_EMPTY' && !confirmEmpty) return;
    initializing.current = true; setBusy(true); setError(''); setSetupProgressStarted(false);
    let acceptedId: string | null = null;
    try {
      const accepted = await switchSetup(snapshot.draft.draftId, snapshot.draft.revision, mode, snapshot.version);
      acceptedId = accepted.data.operationId;
      const latest = await refresh();
      if (!latest.operation || latest.operation.operationId !== acceptedId) {
        const state = await getSetupOperation(acceptedId); setOperation(state); setSnapshot({ ...latest, operation: { operationId: state.operationId, type: state.type, status: state.status } });
      }
      showToast('문서 서비스 변경을 시작했습니다.');
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
    const initializationOperation = operation?.type === 'INITIALIZE';
    if (busy || initializing.current || !operation || operation.type !== 'SWITCH' && !(initializationOperation && action === 'CANCEL' || initializationOperation && action === 'RETRY') || !operation.allowedActions.includes(action) || action === 'RETRY' && operation.status === 'RECONCILIATION_REQUIRED') return;
    initializing.current = true; setBusy(true); setError(''); setChanging(operation.type === 'SWITCH');
    try {
      await actOnSetupOperation(operation.operationId, action, operation.version);
      await refresh();
      if (initializationOperation && action === 'CANCEL') {
        setProvider(null); setCredentials(EMPTY_CREDENTIALS); setLocation(EMPTY_LOCATION); setDraft(null); setTested(null); setSetupProgressStarted(false); setInitializationUncertain(false); setChanging(false);
        setNotice('중단된 연결 작업을 종료했습니다. 문서 서비스에 일부 페이지가 생성되었는지 확인한 뒤 다시 연결해 주세요.');
      } else showToast(action === 'RETRY' ? '같은 작업을 재개했습니다.' : '작업 취소를 요청했습니다.');
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

  async function retryOperationStatus() {
    if (busy) return;
    setBusy(true);
    try {
      const { snapshot: latest } = await getSetupSnapshot();
      setSnapshot(latest);
      if (!latest.operation) {
        setOperation(null); setOperationReadFailed(false); setError('');
        return;
      }
      const current = await getSetupOperation(latest.operation.operationId);
      setOperation(current); setOperationReadFailed(false); setError('');
    } catch (reason) {
      setOperationReadFailed(true);
      const detail = reason instanceof DocumentSetupError ? ` (${reason.code}${reason.status ? `, HTTP ${reason.status}` : ''})` : '';
      setError(`진행 상세 정보를 불러오지 못했습니다${detail}. 백엔드 연결과 문서 설정 저장소를 확인한 뒤 다시 불러와 주세요.`);
    } finally { setBusy(false); }
  }

  async function closeUnresolvedInitialization() {
    const pending = snapshot?.operation;
    if (busy || !pending || pending.type !== 'INITIALIZE' || snapshot?.version == null) return;
    setBusy(true); setError('');
    try {
      await actOnSetupOperation(pending.operationId, 'CANCEL', snapshot.version);
      const { snapshot: latest } = await getSetupSnapshot();
      setSnapshot(latest); setOperation(null); setOperationReadFailed(false);
      setProvider(null); setCredentials(EMPTY_CREDENTIALS); setLocation(EMPTY_LOCATION); setDraft(null); setTested(null);
      setSetupProgressStarted(false); setInitializationUncertain(false); setChanging(false);
      setNotice('연결 작업을 종료했습니다. 이미 생성된 페이지가 있을 수 있으니 Notion에서 확인한 뒤 새 연결을 시작해 주세요.');
    } catch (reason) {
      const detail = reason instanceof DocumentSetupError ? ` (${reason.code}${reason.status ? `, HTTP ${reason.status}` : ''})` : '';
      setError(reason instanceof DocumentSetupError && reason.status === 409
        ? '문서 페이지 생성 작업이 아직 실행 중이라 종료하지 않았습니다. 잠시 뒤 진행 상태를 다시 불러와 주세요.'
        : `연결 작업을 종료하지 못했습니다${detail}. 백엔드 연결과 문서 설정 저장소를 확인해 주세요.`);
      try {
        const latest = await getSetupSnapshot();
        setSnapshot(latest.snapshot);
        if (latest.snapshot.operation) {
          const current = await getSetupOperation(latest.snapshot.operation.operationId);
          setOperation(current); setOperationReadFailed(false);
        }
      } catch { setOperationReadFailed(true); }
    } finally { setBusy(false); }
  }

  const retryConsumerRefresh = <button className="button button-secondary" type="button" onClick={() => { setError(''); setConsumerRefreshFailed(false); setConsumerRefreshAttempt((attempt) => attempt + 1); }}>연결 데이터 다시 확인</button>;

  if (!snapshot) return <main className="setup-page page-scroll-layout">
    <header className="page-fixed-heading setup-heading"><h1>문서 서비스 연결</h1></header>
    <div className="page-scroll-content">{error ? <section className="setup-card" role="alert"><h2>문서 연결 상태를 확인할 수 없습니다</h2><p>{error} 서버 응답을 확인하기 전에는 새 문서 작업을 시작하지 않습니다. 백엔드 연결을 확인한 뒤 다시 불러와 주세요.</p><button className="button button-secondary" type="button" onClick={() => void safeRefresh()}>문서 연결 상태 다시 불러오기</button></section> : <p role="status">문서 설정을 불러오는 중…</p>}</div>
  </main>;
  if (snapshot.status === 'STORAGE_UNAVAILABLE') return <main className="setup-page page-scroll-layout">
    <header className="page-fixed-heading setup-heading"><h1>문서 서비스 연결</h1></header>
    <div className="page-scroll-content"><section className="setup-card" role="alert"><h2>저장소 설정을 확인할 수 없습니다</h2><p>문서 설정 저장소가 준비되지 않아 문서 기능을 사용할 수 없습니다. 관리자에게 저장소 설정을 확인해 달라고 요청해 주세요.</p><button className="button button-secondary" type="button" onClick={() => void refresh()}>다시 확인</button></section></div>
  </main>;
  const operationStatus = operation?.status ?? snapshot.operation?.status ?? '';
  const preparedTarget = snapshot.active && snapshot.draft?.state === 'PREPARED' && snapshot.status === 'READY' && !preparedInvalid;
  const initializationSucceeded = snapshot.operation?.type === 'INITIALIZE' && operationStatus === 'SUCCEEDED' && snapshot.status === 'READY' && snapshot.active != null && !preparedTarget;
  const initializationFailed = snapshot.operation?.type === 'INITIALIZE' && ['FAILED', 'INTERRUPTED', 'CANCELLED', 'RECONCILIATION_REQUIRED'].includes(operationStatus);
  const initializationInProgress = !preparedTarget && !initializationFailed && (setupProgressStarted || snapshot.operation?.type === 'INITIALIZE' && ['PENDING', 'RUNNING', 'SUCCEEDED'].includes(operationStatus));
  const activeSummary = snapshot.active && <section className="setup-card" aria-label="현재 활성 문서 연결"><h2>문서 서비스</h2><p>현재 연결: {snapshot.active.provider === 'NOTION' ? 'Notion' : 'Confluence Cloud'}</p><p>연결 버전: {snapshot.active.connectionVersion}</p></section>;
  if (snapshot.status === 'READY' && settings && !changing) return <main className="setup-page setup-settings-page page-scroll-layout">
    <Toast message={toastMessage} />
    <div className="page-fixed-heading"><Link className="back-link" to="/settings"><span aria-hidden="true">←</span> 설정으로 돌아가기</Link>
    <header className="setup-settings-heading">
      <h1><svg className="setup-settings-heading-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M8.5 15.5 6 18a4 4 0 0 1-5.7-5.7l4-4A4 4 0 0 1 10 8m5.5.5L18 6a4 4 0 0 1 5.7 5.7l-4 4A4 4 0 0 1 14 16m-6 0 8-8" /></svg>문서 연결</h1>
      <p>현재 연결된 문서 서비스와 저장 위치를 확인합니다.</p>
    </header>
    </div>
    <div className="page-scroll-content">
    {snapshot.active && <section className="setup-settings-connection" aria-labelledby="setup-settings-provider-heading">
      <div className="setup-settings-provider">
        <ProviderIcon provider={snapshot.active.provider} />
        <div><h2 id="setup-settings-provider-heading">{providerLabel(snapshot.active.provider)}</h2><p>회의록과 기본 템플릿이 저장되는 서비스입니다.</p></div>
        <span className="setup-settings-status">연결됨</span>
      </div>
      <a className="button button-secondary setup-settings-root-link" href={snapshot.active.rootUrl} target="_blank" rel="noreferrer">연결된 문서 열기 <span aria-hidden="true">↗</span></a>
    </section>}
    {error && <p role="alert" className="setup-error">{error}</p>}
    {consumerRefreshFailed && retryConsumerRefresh}
    {notice && <p role="status">{notice}</p>}
    {returnTo && <Link className="back-link" to={returnTo} state={returnState?.returnState}><span aria-hidden="true">←</span> 이전 화면으로 돌아가기</Link>}
    <button className="button button-primary setup-settings-change" type="button" onClick={beginChange}>문서 서비스 변경</button>
    </div>
  </main>;

  const hasBlockingOperation = snapshot.operation && !['SUCCEEDED', 'CANCELLED'].includes(operationStatus);

  const firstRun = !snapshot.active;
  const setupProvider = snapshot.draft?.provider ?? snapshot.active?.provider ?? provider;
  const completedStructureSteps = operation?.completedSteps ?? [];
  const currentStructureStep = operation?.currentStep ?? (operation?.stage === 'STRUCTURE'
    ? STRUCTURE_STEPS.find((step) => !completedStructureSteps.includes(step)) ?? null : null);
  return <main className={`setup-page page-scroll-layout${firstRun ? ' setup-first-run' : ''}`}>
    <Toast message={toastMessage} />
    <header className="page-fixed-heading setup-heading"><h1>{changing || snapshot.active && !provider ? '문서 서비스 선택' : '문서 서비스 연결'}</h1><p>{provider ? '서비스 연결 정보를 입력해 주세요.' : snapshot.active ? `현재 ${providerLabel(snapshot.active.provider)}에 연결되어 있습니다. 변경할 문서 서비스를 선택해 주세요.` : '연결된 문서 서비스가 없습니다. 회의록을 저장하려면 먼저 서비스를 연결해 주세요.'}</p></header>
    <div className="page-scroll-content">
    {provider && !hasBlockingOperation && !preparedTarget && !initializationUncertain && !setupProgressStarted && <button className="back-link setup-provider-back" type="button" aria-label="서비스 선택으로 돌아가기" onClick={() => chooseProvider(null)}><span aria-hidden="true">←</span> 서비스 선택</button>}
    {!initializationSucceeded && activeSummary}
    {error && !operationReadFailed && <p className="setup-error" role="alert">{error}</p>}{notice && <p className="setup-notice" role="status">{notice}</p>}
    {consumerRefreshFailed && retryConsumerRefresh}
    {initializationInProgress && <section className="setup-card setup-progress-card" aria-labelledby="setup-progress-heading" aria-live="polite">
      <h2 id="setup-progress-heading">{initializationSucceeded ? '문서 연결 완료' : `${providerLabel(setupProvider)} 문서를 생성하고 있습니다`}</h2>
      <div className="setup-progress-provider"><ProviderIcon provider={setupProvider ?? 'NOTION'} /><span><strong>{providerLabel(setupProvider)}</strong><small>{initializationSucceeded ? '연결 및 기본 구조 생성 완료' : '문서 서비스에 연결 중'}</small></span>{!initializationSucceeded && <span className="setup-progress-spinner" aria-hidden="true" />}</div>
      <ol className="setup-progress-steps">
        <li className="is-complete"><span className="setup-progress-marker" aria-hidden="true">✓</span><span><strong>연결 확인</strong><small>문서 서비스 연결을 확인했습니다.</small></span></li>
        <li className={initializationSucceeded ? 'is-complete' : 'is-current'} aria-current={initializationSucceeded ? undefined : 'step'}><span className="setup-progress-marker" aria-hidden="true">{initializationSucceeded ? '✓' : '2'}</span><span><strong>문서 생성</strong><small>기본 구조와 회의록 템플릿을 준비합니다.</small></span></li>
        <li className={initializationSucceeded ? 'is-complete' : ''}><span className="setup-progress-marker" aria-hidden="true">{initializationSucceeded ? '✓' : '3'}</span><span><strong>생성 완료</strong><small>{initializationSucceeded ? '기본 구조를 확인했습니다.' : '문서 생성이 끝나면 완료됩니다.'}</small></span></li>
      </ol>
      {operation?.stage === 'STRUCTURE' && <ol className="setup-page-creation-steps" aria-label="문서 페이지 생성 진행 상황">{STRUCTURE_STEPS.map((step) => {
        const complete = completedStructureSteps.includes(step);
        const current = currentStructureStep === step && !complete;
        return <li key={step} className={complete ? 'is-complete' : current ? 'is-current' : ''}><span aria-hidden="true">{complete ? '✓' : current ? <span className="setup-progress-spinner" /> : '○'}</span><strong>{structureStepLabel(step)}</strong><small>{complete ? '생성 완료' : current ? '생성 중' : '대기 중'}</small></li>;
      })}</ol>}
      {initializationSucceeded ? <><p>Meeting Automation 아래 Meetings, Participants, Meeting Data, Templates 페이지를 만들고 Templates에 기본 회의록을 넣었습니다.</p><button className="button button-primary" type="button" onClick={() => navigate('/', { state: { documentSetupCompleted: { provider: snapshot.active!.provider, rootUrl: snapshot.active!.rootUrl } } })}>확인</button></> : <>
        <p className="setup-progress-status" role="status"><span className="setup-progress-spinner" aria-hidden="true" />{initializationProgressMessage(operation, setupProvider)}</p>
        {operationReadFailed && <div className="setup-progress-recovery" role="group" aria-label="진행 상태 복구">
          <p className="setup-error" role="alert">{error || `진행 상세 정보를 불러오지 못했습니다. ${providerLabel(setupProvider)}의 현재 작업 요약은 ‘${operationStatusLabel(snapshot.operation?.status)}’입니다. 서버에서 페이지 생성이 계속될 수 있으므로 새 작업을 보내지 않고 상태를 다시 확인해 주세요.`}</p>
          <button className="button button-secondary" type="button" disabled={busy} onClick={() => void retryOperationStatus()}>{busy ? '상태 확인 중…' : '진행 상태 다시 불러오기'}</button>
          {snapshot.operation?.type === 'INITIALIZE' && <button className="button button-secondary" type="button" disabled={busy || snapshot.version == null} onClick={() => void closeUnresolvedInitialization()}>{busy ? '연결 작업 확인 중…' : '연결 작업 종료 후 다시 연결'}</button>}
        </div>}
      </>}
    </section>}
    {snapshot.operation && !initializationInProgress && !(operation?.type === 'INITIALIZE' && operation.status === 'CANCELLED') && <section className="setup-card" aria-labelledby="operation-heading">
      <h2 id="operation-heading">{operation?.type === 'SWITCH' ? `문서 서비스 변경 · ${operationStatusLabel(operationStatus)}` : operationTitle(operationStatus)}</h2>
      <p role="status">{operationStageLabel(operation?.stage)} · {operationStatusLabel(operation?.status)}</p>
      <p>작업 ID: <code>{snapshot.operation.operationId}</code></p>
      {operationReadFailed && !initializationInProgress && <div className="setup-progress-recovery" role="group" aria-label="진행 상태 복구">
        <p className="setup-error" role="alert">{error || `진행 상세 정보를 불러오지 못했습니다. ${providerLabel(setupProvider)}의 현재 작업 요약은 ‘${operationStatusLabel(snapshot.operation.status)}’입니다. 서버에서 변경 작업이 계속될 수 있으므로 새 작업을 보내지 않고 상태를 다시 확인해 주세요.`}</p>
        <button className="button button-secondary" type="button" disabled={busy} onClick={() => void retryOperationStatus()}>{busy ? '상태 확인 중…' : '진행 상태 다시 불러오기'}</button>
        {snapshot.operation.type === 'INITIALIZE' && <button className="button button-secondary" type="button" disabled={busy || snapshot.version == null} onClick={() => void closeUnresolvedInitialization()}>{busy ? '연결 작업 확인 중…' : '연결 작업 종료 후 다시 연결'}</button>}
      </div>}
      {operation && <>
        {operation.type === 'SWITCH' && <p role="status">참석자 {operation.counts.participantsCopied}/{operation.counts.participantsTotal}명 · 회의 {operation.counts.meetingsCopied}/{operation.counts.meetingsTotal}건</p>}
        {operation.errorCode && <p>오류 코드: <code>{operation.errorCode}</code></p>}
        <p>{operationGuidance(operation)}</p>
        {operation.type === 'SWITCH' ? <div className="setup-choice">
          {operation.allowedActions.includes('RETRY') && operation.status !== 'RECONCILIATION_REQUIRED' && <button className="button button-secondary" type="button" disabled={busy} onClick={() => void runOperationAction('RETRY')}>작업 재개</button>}
          {operation.allowedActions.includes('CANCEL') && <button className="button button-secondary" type="button" disabled={busy} onClick={() => void runOperationAction('CANCEL')}>작업 취소</button>}
          {operation.allowedActions.includes('REVIEW_PROVIDER') && <p>대상 문서 구조를 확인한 뒤 진행 상태를 새로고침해 주세요.</p>}
        </div> : <div className="setup-choice">
          {operation.allowedActions.includes('REVIEW_PROVIDER') && <p>문서 서비스에서 생성된 페이지 구조를 확인해 주세요.</p>}
          {operation.allowedActions.includes('RETRY') && <button className="button button-secondary" type="button" disabled={busy} onClick={() => void runOperationAction('RETRY')}>작업 재개</button>}
          {operation.allowedActions.includes('CANCEL') && <button className="button button-secondary" type="button" disabled={busy} onClick={() => void runOperationAction('CANCEL')}>중단된 연결 작업 종료</button>}
        </div>}
      </>}
      {operation?.type === 'SWITCH' && operation.status === 'SUCCEEDED' && snapshot.status === 'READY' && snapshot.active && <><p>Meetings, Participants, Meeting Data, Templates 기본 구조가 준비되었습니다.</p><button className="button button-primary" type="button" onClick={() => navigate('/', { state: { documentSetupCompleted: { provider: snapshot.active!.provider, rootUrl: snapshot.active!.rootUrl } } })}>확인</button></>}
      <button ref={progressRefreshRef} type="button" className="button button-secondary" disabled={busy} onClick={() => void safeRefresh()}>진행 상태 새로고침</button>
    </section>}
    {preparedTarget && !initializationUncertain && <section className="setup-card" aria-labelledby="migration-heading"><h2 id="migration-heading">자료 이전 방식</h2><p>대상 연결: {snapshot.draft?.provider === 'NOTION' ? 'Notion' : 'Confluence Cloud'}</p><p>참석자와 회의록의 Transcript, Minutes, 참석자 참조를 전체 복사합니다. 첨부·댓글·버전 이력은 포함되지 않으며 이메일과 Slack 알림을 다시 보내지 않습니다.</p><p>원본 자료는 원본 서비스에 보존됩니다. 전환이 완료된 뒤 새 서비스의 목록을 사용합니다.</p><fieldset className="setup-mode"><legend>이전 방식 선택</legend><label><input type="radio" name="switch-mode" checked={mode === 'COPY_ALL'} onChange={() => { setMode('COPY_ALL'); setConfirmEmpty(false); }} />전체 복사</label><label><input type="radio" name="switch-mode" checked={mode === 'START_EMPTY'} onChange={() => { setMode('START_EMPTY'); setConfirmEmpty(false); }} />빈 구조로 새 시작</label></fieldset>{mode === 'START_EMPTY' && <label className="setup-confirm"><input type="checkbox" checked={confirmEmpty} onChange={(event) => setConfirmEmpty(event.target.checked)} />기존 자료가 새 서비스 목록에 나타나지 않음을 확인했습니다. 자료는 원본에 보존됩니다.</label>}<button className="button button-primary" type="button" disabled={busy || mode === 'START_EMPTY' && !confirmEmpty} onClick={() => void startSwitch()}>{busy ? '전환 시작 중…' : '문서 서비스 전환'}</button></section>}
    {!hasBlockingOperation && !preparedTarget && !initializationUncertain && !setupProgressStarted && <>
      {!provider ? <section className="setup-provider-list" aria-label="연결할 문서 서비스 선택">
        <button className="setup-provider-card" type="button" aria-label="Notion 선택" onClick={() => chooseProvider('NOTION')}>
          <ProviderIcon provider="NOTION" />
          <span className="setup-provider-copy"><strong>Notion</strong><small>Notion에 연결</small></span>
          <span className="setup-provider-arrow" aria-hidden="true">→</span>
        </button>
        <button className="setup-provider-card" type="button" aria-label="Confluence Cloud 선택" onClick={() => chooseProvider('CONFLUENCE')}>
          <ProviderIcon provider="CONFLUENCE" />
          <span className="setup-provider-copy"><strong>Confluence</strong><small>Confluence에 연결</small></span>
          <span className="setup-provider-arrow" aria-hidden="true">→</span>
        </button>
      </section> : <>
        <div className="setup-selected-provider"><ProviderIcon provider={provider} /><strong>{provider === 'NOTION' ? 'Notion에 연결' : 'Confluence에 연결'}</strong></div>
        <form ref={formRef} className="setup-card setup-form" onSubmit={(event) => void handleTest(event)} noValidate>
          {provider === 'NOTION' ? <>
            <div className="setup-field"><label htmlFor="notion-token">Notion 내부 연결 토큰</label><input id="notion-token" type="password" autoComplete="new-password" value={credentials.token} onChange={(event) => updateCredential('token', event.target.value)} aria-invalid={Boolean(fieldErrors['notion-token'])} aria-describedby={`notion-token-help${fieldErrors['notion-token'] ? ' notion-token-error' : ''}`} /><small id="notion-token-help">연결 확인 후 입력한 토큰은 화면에서 지우며 브라우저 저장소에는 저장하지 않습니다.</small>{fieldErrors['notion-token'] && <small className="setup-error" id="notion-token-error" role="alert">{fieldErrors['notion-token']}</small>}</div>
            <div className="setup-field"><label htmlFor="notion-parent">부모 페이지 URL 또는 ID</label><input id="notion-parent" value={location.parentPageId} onChange={(event) => updateLocation('parentPageId', event.target.value)} aria-invalid={Boolean(fieldErrors['notion-parent'])} aria-describedby={fieldErrors['notion-parent'] ? 'notion-parent-error' : undefined} />{fieldErrors['notion-parent'] && <small className="setup-error" id="notion-parent-error" role="alert">{fieldErrors['notion-parent']}</small>}</div>
          </> : <>
            <div className="setup-field"><label htmlFor="confluence-url">Confluence 사이트 URL</label><input id="confluence-url" type="url" placeholder="https://example.atlassian.net" value={location.baseUrl} onChange={(event) => updateLocation('baseUrl', event.target.value)} aria-invalid={Boolean(fieldErrors['confluence-url'])} aria-describedby={fieldErrors['confluence-url'] ? 'confluence-url-error' : undefined} />{fieldErrors['confluence-url'] && <small className="setup-error" id="confluence-url-error" role="alert">{fieldErrors['confluence-url']}</small>}</div>
            <div className="setup-field"><label htmlFor="confluence-email">Atlassian 계정 이메일</label><input id="confluence-email" type="email" autoComplete="off" value={credentials.accountEmail} onChange={(event) => updateCredential('accountEmail', event.target.value)} aria-invalid={Boolean(fieldErrors['confluence-email'])} aria-describedby={fieldErrors['confluence-email'] ? 'confluence-email-error' : undefined} />{fieldErrors['confluence-email'] && <small className="setup-error" id="confluence-email-error" role="alert">{fieldErrors['confluence-email']}</small>}</div>
            <div className="setup-field"><label htmlFor="confluence-token">Atlassian API token</label><input id="confluence-token" type="password" autoComplete="new-password" value={credentials.apiToken} onChange={(event) => updateCredential('apiToken', event.target.value)} aria-invalid={Boolean(fieldErrors['confluence-token'])} aria-describedby={fieldErrors['confluence-token'] ? 'confluence-token-error' : undefined} />{fieldErrors['confluence-token'] && <small className="setup-error" id="confluence-token-error" role="alert">{fieldErrors['confluence-token']}</small>}</div>
            <div className="setup-field"><label htmlFor="confluence-space">Space ID</label><input id="confluence-space" value={location.spaceId} onChange={(event) => updateLocation('spaceId', event.target.value)} aria-invalid={Boolean(fieldErrors['confluence-space'])} aria-describedby={fieldErrors['confluence-space'] ? 'confluence-space-error' : undefined} />{fieldErrors['confluence-space'] && <small className="setup-error" id="confluence-space-error" role="alert">{fieldErrors['confluence-space']}</small>}</div>
          </>}
          <button className="button button-primary" type="submit" disabled={busy || Boolean(tested)}>{busy ? '연결 확인 중…' : tested ? '연결 확인 완료' : '연결 확인'}</button>
        </form>
        <button className="button button-secondary setup-guide-trigger" type="button" aria-haspopup="dialog" onClick={() => setGuideOpen(true)}>연결 방법 안내 <span aria-hidden="true">ⓘ</span></button>
        <dialog className="setup-guide-dialog" ref={guideDialogRef} aria-labelledby="guide-title" onCancel={() => setGuideOpen(false)} onClose={() => setGuideOpen(false)}>
          <div className="setup-guide-dialog-heading"><h2 id="guide-title">{provider === 'NOTION' ? 'Notion 연결 방법' : 'Confluence 연결 방법'}</h2><button className="icon-button" type="button" aria-label="안내 닫기" onClick={() => setGuideOpen(false)}>×</button></div>
          {provider === 'NOTION' ? <ol className="setup-guide-steps">
            <li><span><a className="setup-guide-link" href="https://www.notion.com/help/create-integrations-with-the-notion-api" target="_blank" rel="noreferrer">Notion 내부 연결 안내 <span aria-hidden="true">↗</span></a>를 열어 토큰을 준비합니다.</span></li>
            <li><span>자료를 둘 부모 페이지에 연결을 추가하고 읽기·콘텐츠 추가·수정 권한을 허용합니다.</span></li>
            <li><span>부모 페이지 URL 또는 ID를 확인합니다. 테스트 후 토큰은 화면에서 지워집니다.</span></li>
          </ol> : <ol className="setup-guide-steps">
            <li><span>사이트 URL은 <code>https://회사이름.atlassian.net</code> 형식입니다.</span></li>
            <li><span><a className="setup-guide-link" href="https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account" target="_blank" rel="noreferrer">Atlassian API token 안내 <span aria-hidden="true">↗</span></a>와 운영 계정 이메일을 준비합니다.</span></li>
            <li><span>Space ID와 페이지 생성 권한을 확인합니다. 앱 페이지는 해당 Space 최상위에 만들어집니다.</span></li>
          </ol>}
          <p className="setup-guide-note">먼저 서비스 연결을 읽기 전용으로 확인합니다. 다음 단계에서 문서 구조와 기본 회의록 템플릿을 생성합니다.</p>
        </dialog>
        {tested && <section className="setup-card"><h2>생성될 구조</h2><p>Meeting Automation 아래 Meetings, Participants, Templates 페이지를 만들고 Templates에는 기본 회의록을 넣습니다. 템플릿 페이지는 문서 서비스에서 직접 수정할 수 있습니다.</p><p>서비스 연결을 확인했습니다. 쓰기 권한은 최종 페이지 생성 중 확인됩니다.</p></section>}
      </>}
    </>}
    {snapshot.status === 'READY' && <p><Link className="back-link" to="/settings/document" onClick={() => { setChanging(false); setCredentials(EMPTY_CREDENTIALS); }}><span aria-hidden="true">←</span> 문서 서비스 설정으로 돌아가기</Link></p>}
    {initializationUncertain && <section className="setup-card" aria-labelledby="uncertain-operation-title"><h2 id="uncertain-operation-title">요청 상태 확인</h2><p>요청 결과가 불명확하므로 새 요청을 보내지 않습니다. 서버의 공개 작업 상태를 먼저 확인해 주세요.</p><button className="button button-secondary" type="button" onClick={() => void recheckUncertainInitialization()} disabled={busy}>{busy ? '확인 중…' : '다시 확인'}</button></section>}
    </div>
  </main>;
}

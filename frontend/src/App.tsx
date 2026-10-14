import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import {
  createParticipant,
  listParticipants,
  ParticipantApiError,
  type Participant,
  type ParticipantField,
  updateParticipant,
} from './participantsApi';
import './app.css';
import { Link, NavLink, Route, Routes, useLocation, useNavigate, useParams } from 'react-router';
import { NewMeetingPage, type NewMeetingPayload } from './NewMeetingPage';
import { createMeetingSession, type MeetingSession, type MeetingSessionAttempt } from './newMeetingApi';
import { RecordingPage, type RecordingViewState } from './RecordingPage';
import { DocumentSetupWizard } from './DocumentSetupWizard';
import { SharePage } from './SharePage';
import { getSetupSnapshot } from './documentSetupApi';
import { browserMediaAdapter } from './browserMediaAdapter';
import { RecordingController, type MediaRecorderAdapter, type RecordingSnapshot } from './recordingController';
import { ChunkRecorderAdapter } from './chunkRecorderAdapter';
import { ChunkRepository } from './chunkRepository';
import { getReceivedAudioStatus, putAudioChunk } from './audioUploadApi';
import { ChunkUploadCoordinator, type ChunkUploadSnapshot } from './chunkUploadCoordinator';
import { startProcessing } from './processingStartApi';
import { ProcessingAttemptStore, RecordingEndCoordinator, type RecordingEndSnapshot } from './recordingEndCoordinator';
import { ProcessingPage } from './ProcessingPage';
import { ReviewSpeakerPage } from './ReviewSpeakerPage';
import type { ReviewSpeaker, StructuredMinutes } from './reviewApi';
import type { MinutesSaveState } from './ReviewMinutesEditor';

type ViewState = 'loading' | 'ready' | 'error';
type FormState = { mode: 'create' } | { mode: 'edit'; participant: Participant };
type Fields = { name: string; email: string };

const EMPTY_FIELDS: Fields = { name: '', email: '' };

function validate(fields: Fields): Partial<Record<ParticipantField, string>> {
  const errors: Partial<Record<ParticipantField, string>> = {};
  if (!fields.name.trim()) errors.name = '이름을 입력해 주세요.';
  if (!fields.email.trim()) errors.email = '이메일을 입력해 주세요.';
  else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(fields.email.trim())) errors.email = '올바른 이메일 주소를 입력해 주세요.';
  return errors;
}

function fieldMessage(field: ParticipantField, error: string): string {
  if (error.includes(' ')) return error;
  if (field === 'name' && (error === 'NotBlank' || error === 'NotEmpty')) return '이름을 입력해 주세요.';
  if (field === 'email' && (error === 'NotBlank' || error === 'NotEmpty')) return '이메일을 입력해 주세요.';
  if (field === 'email' && error === 'Email') return '올바른 이메일 주소를 입력해 주세요.';
  return field === 'name' ? '이름을 확인해 주세요.' : '이메일을 확인해 주세요.';
}

function createKey(): string {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function ParticipantsPage() {
  const [participants, setParticipants] = useState<Participant[]>([]);
  const [viewState, setViewState] = useState<ViewState>('loading');
  const [listError, setListError] = useState('');
  const [search, setSearch] = useState('');
  const [form, setForm] = useState<FormState | null>(null);
  const [fields, setFields] = useState<Fields>(EMPTY_FIELDS);
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<ParticipantField, string>>>({});
  const [formError, setFormError] = useState('');
  const [notice, setNotice] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [viewportHeight, setViewportHeight] = useState(() => window.visualViewport?.height ?? window.innerHeight);
  const createAttempt = useRef<{ payload: string; key: string } | null>(null);
  const dialogRef = useRef<HTMLElement | null>(null);
  const nameInputRef = useRef<HTMLInputElement | null>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);

  async function refreshParticipants() {
    setViewState('loading');
    setListError('');
    try {
      setParticipants(await listParticipants());
      setViewState('ready');
    } catch {
      setViewState('error');
      setListError('참석자 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.');
    }
  }

  useEffect(() => {
    let mounted = true;
    listParticipants().then((items) => {
      if (!mounted) return;
      setParticipants(items);
      setViewState('ready');
    }).catch(() => {
      if (!mounted) return;
      setViewState('error');
      setListError('참석자 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.');
    });
    return () => { mounted = false; };
  }, []);

  useEffect(() => {
    const updateViewportHeight = () => setViewportHeight(window.visualViewport?.height ?? window.innerHeight);
    window.addEventListener('resize', updateViewportHeight);
    window.visualViewport?.addEventListener('resize', updateViewportHeight);
    return () => {
      window.removeEventListener('resize', updateViewportHeight);
      window.visualViewport?.removeEventListener('resize', updateViewportHeight);
    };
  }, []);

  const filteredParticipants = useMemo(() => {
    const query = search.trim().toLocaleLowerCase();
    if (!query) return participants;
    return participants.filter(({ name, email }) => `${name} ${email}`.toLocaleLowerCase().includes(query));
  }, [participants, search]);

  function openCreate(trigger: HTMLElement) {
    returnFocusRef.current = trigger;
    setForm({ mode: 'create' });
    setFields(EMPTY_FIELDS);
    setFieldErrors({});
    setFormError('');
    setNotice('');
  }

  function openEdit(participant: Participant, trigger: HTMLElement) {
    returnFocusRef.current = trigger;
    setForm({ mode: 'edit', participant });
    setFields({ name: participant.name, email: participant.email });
    setFieldErrors({});
    setFormError('');
    setNotice('');
  }

  function closeForm() {
    setForm(null);
    setFormError('');
    setFieldErrors({});
    createAttempt.current = null;
    returnFocusRef.current?.focus();
  }

  useLayoutEffect(() => {
    if (form) nameInputRef.current?.focus();
  }, [form]);

  function handleDialogKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === 'Escape') {
      event.preventDefault();
      if (submitting) return;
      closeForm();
      return;
    }
    if (event.key !== 'Tab') return;

    const focusable = dialogRef.current?.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    );
    if (!focusable?.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && (active === first || !dialogRef.current?.contains(active))) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && (active === last || !dialogRef.current?.contains(active))) {
      event.preventDefault();
      first.focus();
    }
  }

  function changeField(field: ParticipantField, value: string) {
    setFields((current) => ({ ...current, [field]: value }));
    setFieldErrors((current) => ({ ...current, [field]: undefined }));
    setFormError('');
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!form || submitting) return;
    const errors = validate(fields);
    if (Object.keys(errors).length) {
      setFieldErrors(errors);
      setFormError('입력 내용을 확인해 주세요.');
      return;
    }

    setSubmitting(true);
    setFormError('');
    const payload = { name: fields.name.trim(), email: fields.email.trim() };
    try {
      if (form.mode === 'create') {
        const payloadKey = JSON.stringify(payload);
        if (createAttempt.current?.payload !== payloadKey) {
          createAttempt.current = { payload: payloadKey, key: createKey() };
        }
        const created = await createParticipant(payload, createAttempt.current.key);
        setParticipants((current) => [...current, created]);
        setNotice('참석자를 추가했습니다.');
      } else {
        const patch: Partial<Fields> = {};
        if (payload.name !== form.participant.name) patch.name = payload.name;
        if (payload.email !== form.participant.email) patch.email = payload.email;
        if (!Object.keys(patch).length) {
          closeForm();
          return;
        }
        const updated = await updateParticipant(form.participant.id, patch);
        setParticipants((current) => current.map((participant) => participant.id === updated.id ? updated : participant));
        setNotice('참석자 정보를 수정했습니다.');
      }
      closeForm();
    } catch (error) {
      if (error instanceof ParticipantApiError) {
        setFieldErrors(error.fieldErrors);
        setFormError(error.code === 'DOCUMENT_CHANGE_BUSY' || error.code === 'DOCUMENT_SETTINGS_VERSION_CONFLICT'
          ? error.message
          : error.code === 'PARTICIPANT_NOT_FOUND'
          ? '이 참석자는 목록에서 사라졌습니다. 목록을 새로고침한 뒤 다시 시도해 주세요.'
          : error.code === 'VALIDATION_FAILED'
            ? '입력 내용을 확인해 주세요.'
            : form.mode === 'create'
              ? '저장하지 못했습니다. 입력값을 확인한 뒤 다시 시도해 주세요.'
              : error.message);
      } else {
        setFormError('저장하지 못했습니다. 입력값을 확인한 뒤 다시 시도해 주세요.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="participants-main">
      <header className="topbar">
        <Link className="brand" to="/" aria-label="Meeting Automation 홈">M</Link>
        <div>
          <p className="eyebrow">MEETING AUTOMATION</p>
          <h1>설정</h1>
        </div>
      </header>

      <nav className="breadcrumbs" aria-label="현재 위치">
        <span>설정</span><span aria-hidden="true">/</span><span aria-current="page">참석자</span>
      </nav>

      <section className="participants-page" id="participants" aria-labelledby="page-title">
        <div className="page-heading">
          <div>
            <p className="eyebrow">ROSTER</p>
            <h2 id="page-title">참석자</h2>
            <p className="page-description">회의에서 사용할 참석자 정보를 관리합니다.</p>
          </div>
          <button className="button button-primary add-button" type="button" aria-label="새 참석자 추가" onClick={(event) => openCreate(event.currentTarget)}>
            <span aria-hidden="true">＋</span> 참석자 추가
          </button>
        </div>

        {notice && <p className="notice" role="status">{notice}</p>}

        {viewState === 'loading' && <div className="state-card loading" role="status"><span className="spinner" aria-hidden="true" />참석자 목록을 불러오는 중…</div>}

        {viewState === 'error' && <div className="state-card error-card" role="alert"><p>{listError}</p><button className="button button-secondary" type="button" onClick={() => void refreshParticipants()}>다시 시도</button></div>}

        {viewState === 'ready' && participants.length === 0 && <div className="state-card empty-card"><div className="empty-icon" aria-hidden="true">◎</div><h3>등록된 참석자가 없습니다</h3><p>참석자를 추가하면 회의에서 선택할 수 있습니다.</p><button className="button button-primary" type="button" onClick={(event) => openCreate(event.currentTarget)}>참석자 추가</button></div>}

        {viewState === 'ready' && participants.length > 0 && <>
          <label className="search-field">
            <span aria-hidden="true" className="search-icon">⌕</span>
            <span className="visually-hidden">참석자 검색</span>
            <input aria-label="참석자 검색" type="search" placeholder="이름 또는 이메일 검색" value={search} onChange={(event) => setSearch(event.target.value)} />
            {search && <button className="clear-search" type="button" aria-label="검색 입력 지우기" onClick={() => setSearch('')}>지우기</button>}
          </label>

          {filteredParticipants.length === 0 ? <div className="state-card search-empty"><h3>검색 결과가 없습니다</h3><p>다른 이름이나 이메일로 검색해 보세요.</p><button className="button button-secondary" type="button" onClick={() => setSearch('')}>검색어 지우기</button></div> : <>
            <div className="roster-heading"><h3>참석자 목록</h3><span>{filteredParticipants.length}명</span></div>
            <div className="roster-list" aria-label="참석자 목록">
              {filteredParticipants.map((participant) => <article className="participant-card" key={participant.id} aria-label={`${participant.name} ${participant.email}`}>
                <div className="avatar" aria-hidden="true">{participant.name.trim().charAt(0).toLocaleUpperCase()}</div>
                <div className="participant-info"><h4>{participant.name}</h4><p>{participant.email}</p></div>
                <button className="button button-secondary edit-button" type="button" aria-label="수정" onClick={(event) => openEdit(participant, event.currentTarget)}>수정</button>
              </article>)}
            </div>
          </>}
        </>}

        {form && <div className="form-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !submitting) closeForm(); }}>
          <section ref={dialogRef} className="participant-form-panel" role="dialog" aria-modal="true" aria-labelledby="form-title" onKeyDown={handleDialogKeyDown} style={{ maxHeight: `calc(${viewportHeight}px - env(safe-area-inset-top) - env(safe-area-inset-bottom) - 24px)`, overflowY: 'auto' }}>
            <div className="form-heading"><div><p className="eyebrow">PARTICIPANT</p><h3 id="form-title">{form.mode === 'create' ? '참석자 추가' : '참석자 수정'}</h3></div><button type="button" className="icon-button" aria-label="닫기" onClick={closeForm} disabled={submitting}>×</button></div>
            <p className="form-description">이름과 이메일 정보를 입력해 주세요.</p>
            <form onSubmit={(event) => void handleSubmit(event)} noValidate>
              <div className="form-field"><label htmlFor="participant-name">이름</label><input ref={nameInputRef} id="participant-name" autoComplete="name" value={fields.name} onChange={(event) => changeField('name', event.target.value)} aria-invalid={Boolean(fieldErrors.name)} aria-describedby={fieldErrors.name ? 'name-error' : undefined} />{fieldErrors.name && <p className="field-error" id="name-error">{fieldMessage('name', fieldErrors.name)}</p>}</div>
              <div className="form-field"><label htmlFor="participant-email">이메일</label><input id="participant-email" type="email" autoComplete="email" value={fields.email} onChange={(event) => changeField('email', event.target.value)} aria-invalid={Boolean(fieldErrors.email)} aria-describedby={fieldErrors.email ? 'email-error' : undefined} />{fieldErrors.email && <p className="field-error" id="email-error">{fieldMessage('email', fieldErrors.email)}</p>}</div>
              {formError && <p className="form-error" role="alert">{formError}</p>}
              {formError.includes('새로고침') && <button className="button button-secondary refresh-button" type="button" onClick={() => void refreshParticipants()}>목록 새로고침</button>}
              <div className="form-actions"><button className="button button-secondary" type="button" onClick={closeForm} disabled={submitting}>취소</button><button className="button button-primary" type="submit" disabled={submitting}>{submitting ? '저장 중…' : '저장'}</button></div>
            </form>
          </section>
        </div>}
      </section>
    </main>
  );
}

function HomePage() {
  return (
    <main className="home-page">
      <div className="home-content">
        <p className="eyebrow">MEETING AUTOMATION</p>
        <h1>Meeting Automation</h1>
        <div className="home-message">
          <h2>회의를 시작할까요?</h2>
          <p>회의 내용을 녹음하고 자동으로 정리합니다.</p>
        </div>
        <Link className="button button-primary home-cta" to="/meetings/new">새 회의 시작</Link>
      </div>
    </main>
  );
}

function FutureRouteNotice() {
  return <main className="future-page"><h1>화면 준비 중</h1><p>이 화면은 다음 작업에서 제공됩니다.</p></main>;
}

function SetupGate({ children }: { children: React.ReactNode }) {
  const location = useLocation();
  const navigate = useNavigate();
  const [allowed, setAllowed] = useState(false);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let live = true;
    void getSetupSnapshot().then(({ snapshot }) => {
      if (!live) return;
      if (snapshot.status === 'READY' && snapshot.canCreateMeeting) setAllowed(true);
      else navigate(snapshot.status === 'STORAGE_UNAVAILABLE' ? '/settings/document' : '/setup/document', { replace: true, state: { from: location.pathname } });
    }).catch(() => { if (live) setFailed(true); });
    return () => { live = false; };
  }, [location.pathname, navigate]);
  if (failed) return <main className="setup-page"><section className="setup-card" role="alert"><h1>문서 설정 상태를 확인할 수 없습니다</h1><p>연결을 확인한 뒤 다시 시도해 주세요.</p></section></main>;
  return allowed ? <>{children}</> : <main className="setup-page"><p role="status">문서 연결 상태를 확인하는 중…</p></main>;
}

type RecordingSession = MeetingSession & { title: string };
const RECORDING_SESSION_STORAGE_KEY = 'meeting-automation.recording-session';

function restoreRecordingSession(): RecordingSession | null {
  try {
    const value = sessionStorage.getItem(RECORDING_SESSION_STORAGE_KEY);
    if (!value) return null;
    const parsed = JSON.parse(value) as RecordingSession;
    if (!parsed.sessionId || !parsed.title || !parsed.uploadPolicy || !Number.isFinite(parsed.uploadPolicy.maxChunkBytes)) return null;
    return parsed;
  } catch {
    return null;
  }
}

function saveRecordingSession(session: RecordingSession): void {
  try { sessionStorage.setItem(RECORDING_SESSION_STORAGE_KEY, JSON.stringify(session)); } catch { /* reload recovery is unavailable if browser storage is disabled */ }
}

function NewMeetingRoute({ onCreated, onSubmitted }: {
  onCreated: (session: RecordingSession) => void;
  onSubmitted?: (payload: NewMeetingPayload) => void;
}) {
  const navigate = useNavigate();
  async function submit(attempt: MeetingSessionAttempt) {
    const session = await createMeetingSession(attempt);
    onCreated({ ...session, title: attempt.title });
    onSubmitted?.({
      title: attempt.title,
      templateId: attempt.templateId,
      participantIds: [...attempt.participantIds],
      timezone: attempt.timezone,
      documentConnectionVersion: attempt.documentConnectionVersion,
    });
    navigate(`/meetings/${encodeURIComponent(session.sessionId)}/recording`);
  }
  return <NewMeetingPage onSubmit={submit} />;
}

function MeetingRecordingRoute({ session, setState, mediaAdapter }: {
  session: RecordingSession | null;
  setState: (state: RecordingViewState) => void;
  mediaAdapter: MediaRecorderAdapter;
}) {
  const { sessionId } = useParams();
  if (!session || session.sessionId !== sessionId) return (
    <main className="recording-unavailable">
      <h1>녹음 세션을 찾을 수 없습니다</h1>
      <p>새 회의를 시작해 녹음 세션을 만들어 주세요.</p>
      <Link className="button button-primary" to="/meetings/new">새 회의 시작</Link>
    </main>
  );
  return <ActiveMeetingRecordingRoute session={session} setState={setState} mediaAdapter={mediaAdapter} />;
}

function ActiveMeetingRecordingRoute({ session, setState, mediaAdapter }: {
  session: RecordingSession;
  setState: (state: RecordingViewState) => void;
  mediaAdapter: MediaRecorderAdapter;
}) {
  const navigate = useNavigate();
  const [snapshot, setSnapshot] = useState<RecordingSnapshot>({ state: 'Ready', elapsedMs: 0 });
  const [storageError, setStorageError] = useState('');
  const [uploadSnapshot, setUploadSnapshot] = useState<ChunkUploadSnapshot>({ status: 'idle', totalCount: 0, confirmedCount: 0, pendingCount: 0 });
  const [endSnapshot, setEndSnapshot] = useState<RecordingEndSnapshot>({ status: 'idle' });
  const controllerRef = useRef<RecordingController | null>(null);
  const persistenceTail = useRef<Promise<unknown>>(Promise.resolve());
  const persistenceError = useRef<unknown>(null);
  const finalDurationMs = useRef<number | null>(null);
  const stopPromise = useRef<Promise<number> | null>(null);
  const stopWaiter = useRef<{ resolve: (durationMs: number) => void; reject: (error: unknown) => void } | null>(null);
  const repository = useMemo(() => new ChunkRepository({
    dbName: 'meeting-automation-chunks',
    maxChunkBytes: session.uploadPolicy.maxChunkBytes,
  }), [session.uploadPolicy.maxChunkBytes]);
  const uploader = useMemo(() => {
    const fetchImpl = globalThis.fetch.bind(globalThis);
    return new ChunkUploadCoordinator({
      repository,
      api: {
        getReceivedSequences: async (sessionId) => (await getReceivedAudioStatus(sessionId, fetchImpl)).receivedSequences,
        putChunk: async (_sessionId, chunk) => putAudioChunk(chunk, fetchImpl),
      },
      onChange: setUploadSnapshot,
    });
  }, [repository]);
  const endCoordinator = useRef<RecordingEndCoordinator | null>(null);
  useEffect(() => {
    const coordinator = new RecordingEndCoordinator({
      sessionId: session.sessionId,
      stopRecorderAndWait: () => {
        if (finalDurationMs.current !== null) return Promise.resolve(finalDurationMs.current);
        if (stopPromise.current) return stopPromise.current;
        const waitingForStop = new Promise<number>((resolve, reject) => {
          const controller = controllerRef.current;
          if (!controller) { reject(new Error('Recorder is unavailable.')); return; }
          stopWaiter.current = { resolve, reject };
          controller.stop();
          const current = controller.getSnapshot();
          if (current.errorMessage && !current.completed) {
            stopWaiter.current = null;
            reject(new Error('Recorder did not stop cleanly.'));
          }
        });
        stopPromise.current = waitingForStop;
        return waitingForStop;
      },
      waitForPersistence: async () => {
        await persistenceTail.current;
        if (persistenceError.current) throw new Error('Chunk persistence did not complete.');
      },
      flushUploads: async () => {
        const result = await uploader.uploadPending(session.sessionId);
        const pending = await repository.getPending(session.sessionId);
        if (pending.chunks.length > 0 || (pending.session?.pendingCount ?? 0) > 0) {
          return { ...result, status: 'retryable', pendingCount: pending.chunks.length };
        }
        return result;
      },
      reconcileUploads: () => uploader.reconcile(session.sessionId),
      getAudioState: () => repository.getPending(session.sessionId),
      startProcessing: (request, idempotencyKey) => startProcessing(session.sessionId, request, idempotencyKey),
      clearLocalAudio: () => repository.clearSessionAudio(session.sessionId),
      navigateToProcessing: (sessionId) => navigate(`/meetings/${encodeURIComponent(sessionId)}/processing`),
      attemptStore: new ProcessingAttemptStore(),
      onChange: setEndSnapshot,
    });
    endCoordinator.current = coordinator;
    return () => { if (endCoordinator.current === coordinator) endCoordinator.current = null; };
  }, [navigate, repository, session.sessionId, uploader]);

  useEffect(() => () => repository.close(), [repository]);
  useEffect(() => {
    void uploader.uploadPending(session.sessionId).catch(() => undefined);
  }, [uploader, session.sessionId]);

  useLayoutEffect(() => {
    let active = true;
    const chunkAdapter = new ChunkRecorderAdapter({
      repository,
      sessionId: session.sessionId,
      onError: () => {
        if (!active) return;
        setStorageError('녹음 데이터를 브라우저 임시 저장소에 보관하지 못했습니다. 저장 공간이나 브라우저 상태를 확인해 주세요.');
        controllerRef.current?.stop();
      },
    });
    const current = new RecordingController({
      uploadPolicy: session.uploadPolicy,
      adapter: mediaAdapter,
      onChange: (next) => {
        setSnapshot(next);
        setState(next.state);
        if (next.errorMessage && stopWaiter.current) {
          const waiter = stopWaiter.current;
          stopWaiter.current = null;
          waiter.reject(new Error('Recorder did not stop cleanly.'));
        }
      },
      onChunk: (chunk) => {
        const persisted = persistenceTail.current.catch(() => undefined).then(() => chunkAdapter.accept(chunk));
        persistenceTail.current = persisted;
        void persisted.then(() => uploader.uploadPending(session.sessionId)).catch((error: unknown) => {
          persistenceError.current = error;
          setStorageError('녹음 데이터를 브라우저 임시 저장소에 보관하지 못했습니다. 저장 공간이나 브라우저 상태를 확인해 주세요.');
          controllerRef.current?.stop();
        });
      },
      onStopComplete: (elapsedMs) => {
        finalDurationMs.current = elapsedMs;
        setSnapshot((previous) => ({ ...previous, state: 'Ready', elapsedMs, completed: true }));
        setState('Ready');
        void persistenceTail.current.then(() => uploader.uploadPending(session.sessionId)).catch(() => undefined);
        stopWaiter.current?.resolve(elapsedMs);
        stopWaiter.current = null;
      },
    });
    controllerRef.current = current;
    return () => {
      active = false;
      current.dispose();
      if (controllerRef.current === current) controllerRef.current = null;
    };
  }, [session, setState, mediaAdapter, repository, uploader]);

  return <RecordingPage
    sessionId={session.sessionId}
    title={session.title}
    state={snapshot.state}
    elapsedMs={snapshot.elapsedMs}
    errorMessage={storageError || snapshot.errorMessage}
    completed={snapshot.completed}
    endFlowFailed={endSnapshot.status === 'failed'}
    uploadSnapshot={uploadSnapshot}
    endSnapshot={endSnapshot}
    onRetryUpload={() => {
      void uploader.retryManually(session.sessionId).then((result) => {
        if (endSnapshot.status === 'upload-failed' && result.status === 'complete') void endCoordinator.current?.start();
      });
    }}
    onRetryProcessing={() => { void endCoordinator.current?.start(); }}
    onStart={() => { if (controllerRef.current) void controllerRef.current.start(); }}
    onPause={() => controllerRef.current?.pause()}
    onResume={() => controllerRef.current?.resume()}
    onRequestEnd={() => undefined}
    onConfirmEnd={() => { void endCoordinator.current?.start(); }}
    onCancelEnd={() => undefined}
  />;
}

function BottomNavigation() {
  return (
    <nav className="bottom-navigation" aria-label="주요 메뉴">
      <NavLink to="/" end aria-label="홈"><span aria-hidden="true">⌂</span>홈</NavLink>
      <NavLink to="/meetings" aria-label="회의록"><span aria-hidden="true">▤</span>회의록</NavLink>
      <NavLink to="/settings" aria-label="설정"><span aria-hidden="true">⚙</span>설정</NavLink>
    </nav>
  );
}

export function App({ onNewMeetingSubmit = () => undefined, onMinutesSave, minutesSaveState, mediaAdapter = browserMediaAdapter }: {
  onNewMeetingSubmit?: (payload: NewMeetingPayload) => void;
  onMinutesSave?: (sessionId: string, minutes: StructuredMinutes) => void;
  minutesSaveState?: MinutesSaveState;
  mediaAdapter?: MediaRecorderAdapter;
} = {}) {
  const [createdSession, setCreatedSession] = useState<RecordingSession | null>(restoreRecordingSession);
  const [recordingState, setRecordingState] = useState<RecordingViewState>('Ready');
  const [speakerDrafts, setSpeakerDrafts] = useState<Record<string, Record<string, string | null>>>({});
  const location = useLocation();
  const initializeSpeakerDraft = useCallback((sessionId: string, speakers: ReviewSpeaker[]) => {
    setSpeakerDrafts((current) => {
      const sessionDraft = current[sessionId] ?? {};
      const nextDraft = { ...sessionDraft };
      for (const speaker of speakers) {
        if (!Object.prototype.hasOwnProperty.call(nextDraft, speaker.speakerId)) nextDraft[speaker.speakerId] = speaker.participantId;
      }
      return { ...current, [sessionId]: nextDraft };
    });
  }, []);
  const updateSpeakerDraft = useCallback((sessionId: string, speakerId: string, participantId: string | null) => {
    setSpeakerDrafts((current) => ({
      ...current,
      [sessionId]: { ...(current[sessionId] ?? {}), [speakerId]: participantId },
    }));
  }, []);
  const commitSpeakerMappings = useCallback((sessionId: string, speakers: ReviewSpeaker[]) => {
    setSpeakerDrafts((current) => ({
      ...current,
      [sessionId]: Object.fromEntries(speakers.map((speaker) => [speaker.speakerId, speaker.participantId])),
    }));
  }, []);
  const isActiveRecording = (/^\/meetings\/[^/]+\/recording$/.test(location.pathname) && recordingState !== 'Ready') || /^\/meetings\/[^/]+\/processing$/.test(location.pathname);
  return (
    <div className="app-shell">
      <Routes>
        <Route path="/" element={<SetupGate><HomePage /></SetupGate>} />
        <Route path="/setup/document" element={<DocumentSetupWizard />} />
        <Route path="/settings" element={<DocumentSetupWizard settings />} />
        <Route path="/settings/document" element={<DocumentSetupWizard settings />} />
        <Route path="/meetings/new" element={<SetupGate><NewMeetingRoute onCreated={(session) => { setCreatedSession(session); saveRecordingSession(session); setRecordingState('Ready'); }} onSubmitted={onNewMeetingSubmit} /></SetupGate>} />
        <Route path="/meetings/:sessionId/recording" element={<MeetingRecordingRoute session={createdSession} setState={setRecordingState} mediaAdapter={mediaAdapter} />} />
        <Route path="/meetings/:sessionId/processing" element={<ProcessingPage />} />
        <Route path="/meetings/:sessionId/review" element={<ReviewSpeakerPage drafts={speakerDrafts} onInitialize={initializeSpeakerDraft} onChange={updateSpeakerDraft} onCommit={commitSpeakerMappings} onMinutesSave={onMinutesSave} minutesSaveState={minutesSaveState} />} />
        <Route path="/meetings/:sessionId/share" element={<SharePage />} />
        <Route path="/settings/participants" element={<SetupGate><ParticipantsPage /></SetupGate>} />
        <Route path="*" element={<FutureRouteNotice />} />
      </Routes>
      {!isActiveRecording && <BottomNavigation />}
    </div>
  );
}

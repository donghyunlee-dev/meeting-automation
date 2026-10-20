import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import {
  createParticipant,
  deleteParticipant,
  listParticipants,
  ParticipantApiError,
  type Participant,
  type ParticipantField,
  updateParticipant,
} from './participantsApi';
import './app.css';
import { Link, NavLink, Route, Routes, useLocation, useNavigate, useParams } from 'react-router';
import { NewMeetingPage, type NewMeetingPayload } from './NewMeetingPage';
import { cancelMeetingSession, createMeetingSession, type MeetingDraft, type MeetingSession, type MeetingSessionAttempt } from './newMeetingApi';
import { RecordingPage, type RecordingViewState } from './RecordingPage';
import { DocumentSetupWizard } from './DocumentSetupWizard';
import { CompletePage } from './CompletePage';
import { getSetupSnapshot } from './documentSetupApi';
import { restoreSlackWebhookSettings } from './slackSettingsApi';
import { restoreEmailSettings } from './emailSettingsApi';
import { browserMediaAdapter } from './browserMediaAdapter';
import { RecordingController, type MediaRecorderAdapter, type RecordingSnapshot } from './recordingController';
import { AsyncState } from './components/async-state/AsyncState';
import { normalizeApiError } from './lib/api/normalizeApiError';
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
import { HomePage, MeetingsPage } from './MeetingHistory';
import { MeetingDetailPage } from './MeetingDetailPage';
import { SettingsPage } from './SettingsPage';
import { BackendRecoveryOverlay } from './components/BackendRecoveryOverlay';

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
  const [participantToDelete, setParticipantToDelete] = useState<Participant | null>(null);
  const [deleteError, setDeleteError] = useState('');
  const [deleting, setDeleting] = useState(false);
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

  async function confirmDeleteParticipant() {
    if (!participantToDelete || deleting) return;
    setDeleting(true);
    setDeleteError('');
    try {
      await deleteParticipant(participantToDelete.id);
      setParticipants((current) => current.filter((participant) => participant.id !== participantToDelete.id));
      setNotice('참석자를 삭제했습니다.');
      setParticipantToDelete(null);
    } catch (error) {
      setDeleteError(error instanceof ParticipantApiError
        ? error.code === 'PARTICIPANT_NOT_FOUND'
          ? '이미 삭제된 참석자입니다. 목록을 새로고침해 주세요.'
          : error.message
        : '참석자를 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    } finally {
      setDeleting(false);
    }
  }

  return (
    <main className="participants-main">
      <section className="participants-page page-scroll-layout" id="participants" aria-labelledby="page-title">
        <div className="page-heading">
          <h1 id="page-title">
            <svg className="participants-heading-icon" viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="9" cy="8" r="3.25" />
              <path d="M2.8 20v-1.4a5.2 5.2 0 0 1 5.2-5.2h2a5.2 5.2 0 0 1 5.2 5.2V20M16 5.2a3.2 3.2 0 0 1 0 6.2M17.2 13.6a4.5 4.5 0 0 1 4 4.5V20" />
            </svg>
            참석자
          </h1>
        </div>

        <div className="page-scroll-content">

        {notice && <p className="notice" role="status">{notice}</p>}

        {viewState === 'loading' && <AsyncState state={{ status: 'loading', label: '참석자 목록을 불러오는 중…', fullPage: true }} renderReady={() => null} />}

        {viewState === 'error' && <AsyncState state={{
          status: 'error', title: '참석자 목록을 불러오지 못했습니다.',
          error: normalizeApiError({ error: { code: 'PARTICIPANT_LIST_FAILED' } }),
          description: listError, onRetry: () => void refreshParticipants(), retryLabel: '다시 시도',
        }} renderReady={() => null} />}

        {viewState === 'ready' && participants.length === 0 && <AsyncState state={{
          status: 'empty', title: '등록된 참석자가 없습니다.',
          description: '참석자를 추가하면 회의에서 선택할 수 있습니다.',
        }} renderReady={() => null} />}

        {viewState === 'ready' && participants.length > 0 && <AsyncState state={{ status: 'ready', data: participants }} renderReady={() => <>
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
                <div className="participant-card-actions">
                  <button className="button button-secondary edit-button" type="button" aria-label="수정" onClick={(event) => openEdit(participant, event.currentTarget)}>수정</button>
                  <button className="button button-danger-outline delete-button" type="button" aria-label={`${participant.name} 삭제`} onClick={() => { setParticipantToDelete(participant); setDeleteError(''); }}>삭제</button>
                </div>
              </article>)}
            </div>
          </>}
        </>} />}

        {form && <div className="form-backdrop" role="presentation">
          <section ref={dialogRef} className="participant-form-panel participant-form-screen" role="dialog" aria-modal="true" aria-labelledby="form-title" onKeyDown={handleDialogKeyDown}>
            <div className="form-heading"><div className="participant-form-title"><span className="participant-form-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M16 20v-1.5a4.5 4.5 0 0 0-4.5-4.5h-4A4.5 4.5 0 0 0 3 18.5V20M9.5 10a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7ZM19 8v6M16 11h6" /></svg></span><div><p className="eyebrow">참석자 관리</p><h3 id="form-title">{form.mode === 'create' ? '참석자 추가' : '참석자 수정'}</h3></div></div><button type="button" className="icon-button" aria-label="닫기" onClick={closeForm} disabled={submitting}>×</button></div>
            <p className="form-description">이름과 이메일 정보를 입력해 주세요.</p>
            <form onSubmit={(event) => void handleSubmit(event)} noValidate>
              <div className="form-field"><label htmlFor="participant-name">이름</label><input ref={nameInputRef} id="participant-name" autoComplete="name" value={fields.name} onChange={(event) => changeField('name', event.target.value)} aria-invalid={Boolean(fieldErrors.name)} aria-describedby={fieldErrors.name ? 'name-error' : undefined} />{fieldErrors.name && <p className="field-error" id="name-error">{fieldMessage('name', fieldErrors.name)}</p>}</div>
              <div className="form-field"><label htmlFor="participant-email">이메일</label><input id="participant-email" type="email" autoComplete="email" value={fields.email} onChange={(event) => changeField('email', event.target.value)} aria-invalid={Boolean(fieldErrors.email)} aria-describedby={fieldErrors.email ? 'email-error' : undefined} />{fieldErrors.email && <p className="field-error" id="email-error">{fieldMessage('email', fieldErrors.email)}</p>}</div>
              {formError && <p className="form-error" role="alert">{formError}</p>}
              {formError.includes('새로고침') && <button className="button button-secondary refresh-button" type="button" onClick={() => void refreshParticipants()}>목록 새로고침</button>}
              <div className="form-actions participant-form-actions"><button className="button button-primary" type="submit" disabled={submitting}>{submitting ? '저장 중…' : '저장'}</button></div>
            </form>
          </section>
        </div>}
        {participantToDelete && <div className="participant-confirm-backdrop" role="presentation">
          <section className="participant-confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="participant-delete-title" aria-describedby="participant-delete-description">
            <h2 id="participant-delete-title">삭제하시겠습니까?</h2>
            <p id="participant-delete-description"><strong>{participantToDelete.name}</strong> 참석자 정보를 연결된 문서 서비스에서 삭제합니다.</p>
            {deleteError && <p className="form-error" role="alert">{deleteError}</p>}
            <div className="participant-confirm-actions">
              <button className="button button-secondary" type="button" disabled={deleting} onClick={() => setParticipantToDelete(null)}>아니요</button>
              <button className="button button-danger" type="button" disabled={deleting} onClick={() => void confirmDeleteParticipant()}>{deleting ? '삭제 중…' : '예'}</button>
            </div>
          </section>
        </div>}
        </div>
      </section>
      <div className="participants-create-dock">
        <button className="button button-primary" type="button" aria-label="참석자 추가" onClick={(event) => openCreate(event.currentTarget)}>
          <span aria-hidden="true">＋</span> 참석자 추가
        </button>
      </div>
    </main>
  );
}

function FutureRouteNotice() {
  return <main className="future-page" aria-labelledby="future-title">
    <section className="future-route-card">
      <p className="eyebrow">PAGE NOT FOUND</p>
      <h1 id="future-title">페이지를 찾을 수 없습니다</h1>
      <p>주소가 잘못되었거나 페이지가 이동되었을 수 있습니다.</p>
      <nav className="future-route-actions" aria-label="다른 페이지로 이동">
        <Link className="button button-primary" to="/">홈으로 이동</Link>
        <Link className="button button-secondary" to="/meetings">회의록 목록</Link>
      </nav>
    </section>
  </main>;
}

function SetupGate({ children }: { children: React.ReactNode }) {
  const location = useLocation();
  const navigate = useNavigate();
  const [allowed, setAllowed] = useState(false);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let live = true;
    void getSetupSnapshot().then(({ snapshot }) => {
      if (!live) return;
      if (snapshot.status === 'READY' && snapshot.canCreateMeeting) setAllowed(true);
      else navigate(snapshot.status === 'STORAGE_UNAVAILABLE' ? '/settings/document' : '/setup/document', { replace: true, state: { from: location.pathname } });
    }).catch(() => { if (live) setFailed(true); });
    return () => { live = false; };
  }, [attempt, location.pathname, navigate]);
  if (failed) return <main className="setup-page"><section className="setup-card" role="alert">
    <h1>문서 연결 상태를 확인할 수 없습니다</h1>
    <p>서버 연결을 확인한 뒤 다시 시도하거나 문서 연결 설정을 열어 주세요.</p>
    <div className="setup-gate-actions">
      <button className="button button-primary" type="button" onClick={() => { setFailed(false); setAttempt((value) => value + 1); }}>다시 확인</button>
      <Link className="button button-secondary" to="/settings/document">문서 연결 설정</Link>
    </div>
  </section></main>;
  return allowed ? <>{children}</> : <main className="setup-page"><p role="status">문서 연결 상태를 확인하는 중…</p></main>;
}

type RecordingSession = MeetingSession & { title: string; draft?: MeetingDraft };
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
  const location = useLocation();
  const initialDraft = (location.state as { draft?: MeetingDraft } | null)?.draft;
  async function submit(attempt: MeetingSessionAttempt) {
    const session = await createMeetingSession(attempt);
    const draft = { title: attempt.title, meetingDate: attempt.meetingDate, templateId: attempt.templateId, participantIds: [...attempt.participantIds] };
    onCreated({ ...session, title: attempt.title, draft });
    onSubmitted?.({
      title: attempt.title,
      meetingDate: attempt.meetingDate,
      templateId: attempt.templateId,
      participantIds: [...attempt.participantIds],
      timezone: attempt.timezone,
      documentConnectionVersion: attempt.documentConnectionVersion,
    });
    navigate(`/meetings/${encodeURIComponent(session.sessionId)}/recording`);
  }
  return <NewMeetingPage onSubmit={submit} initialDraft={initialDraft} />;
}

function MeetingRecordingRoute({ session, setState, setNavigationLocked, clearSession, mediaAdapter }: {
  session: RecordingSession | null;
  setState: (state: RecordingViewState) => void;
  setNavigationLocked: (locked: boolean) => void;
  clearSession: () => void;
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
  return <ActiveMeetingRecordingRoute session={session} setState={setState} setNavigationLocked={setNavigationLocked} clearSession={clearSession} mediaAdapter={mediaAdapter} />;
}

function ActiveMeetingRecordingRoute({ session, setState, setNavigationLocked, clearSession, mediaAdapter }: {
  session: RecordingSession;
  setState: (state: RecordingViewState) => void;
  setNavigationLocked: (locked: boolean) => void;
  clearSession: () => void;
  mediaAdapter: MediaRecorderAdapter;
}) {
  const navigate = useNavigate();
  const [snapshot, setSnapshot] = useState<RecordingSnapshot>({ state: 'Ready', elapsedMs: 0, microphoneStatus: 'checking', microphoneLevel: 0, microphoneInputDetected: false });
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
        if (next.completed) setNavigationLocked(false);
        else if (next.state === 'Recording' || next.state === 'Paused') setNavigationLocked(true);
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
    void current.prepareMicrophone();
    return () => {
      active = false;
      current.dispose();
      if (controllerRef.current === current) controllerRef.current = null;
    };
  }, [session, setState, setNavigationLocked, mediaAdapter, repository, uploader]);

  return <RecordingPage
    sessionId={session.sessionId}
    title={session.title}
    state={snapshot.state}
    elapsedMs={snapshot.elapsedMs}
    microphoneStatus={snapshot.microphoneStatus}
    microphoneLevel={snapshot.microphoneLevel}
    microphoneInputDetected={snapshot.microphoneInputDetected}
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
    onRetryProcessing={() => { setNavigationLocked(true); void endCoordinator.current?.start(); }}
    onRetryMicrophone={() => { void controllerRef.current?.prepareMicrophone(); }}
    onReturnToSetup={session.draft ? async () => {
      await cancelMeetingSession(session.sessionId);
      setNavigationLocked(false);
      setState('Ready');
      clearSession();
      try { sessionStorage.removeItem(RECORDING_SESSION_STORAGE_KEY); } catch { /* session restoration is unavailable */ }
      navigate('/meetings/new', { state: { draft: session.draft } });
    } : undefined}
    onStart={async () => {
      setNavigationLocked(true);
      const controller = controllerRef.current;
      if (!controller) { setNavigationLocked(false); return; }
      await controller.start();
      const current = controller.getSnapshot();
      if (current.state === 'Ready' && current.errorMessage && !current.completed) setNavigationLocked(false);
    }}
    onPause={() => controllerRef.current?.pause()}
    onResume={() => controllerRef.current?.resume()}
    onRequestEnd={() => undefined}
    onConfirmEnd={() => { setNavigationLocked(true); void endCoordinator.current?.start(); }}
    onCancelEnd={() => undefined}
  />;
}

function BottomNavigation() {
  return (
    <nav className="bottom-navigation" aria-label="주요 메뉴">
      <NavLink to="/" end aria-label="홈"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" /></svg>홈</NavLink>
      <NavLink to="/meetings" aria-label="회의록"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3h9l4 4v14H6zM14 3v5h5M9 12h7M9 16h7" /></svg>회의록</NavLink>
      <NavLink to="/participants" aria-label="참석자"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="9" cy="8" r="3" /><path d="M3.5 20v-1.5A4.5 4.5 0 0 1 8 14h2a4.5 4.5 0 0 1 4.5 4.5V20zM16 5.5a3 3 0 0 1 0 5.8M17 14h.5a3.5 3.5 0 0 1 3.5 3.5V20h-4" /></svg>참석자</NavLink>
      <NavLink to="/settings" aria-label="설정"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8" /><circle cx="12" cy="12" r="3" /><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4" /></svg>설정</NavLink>
    </nav>
  );
}

export function App({ onNewMeetingSubmit = () => undefined, onMinutesSave, minutesSaveState, mediaAdapter = browserMediaAdapter }: {
  onNewMeetingSubmit?: (payload: NewMeetingPayload) => void;
  onMinutesSave?: (sessionId: string, minutes: StructuredMinutes) => void;
  minutesSaveState?: MinutesSaveState;
  mediaAdapter?: MediaRecorderAdapter;
} = {}) {
  useEffect(() => {
    void restoreSlackWebhookSettings().catch(() => undefined);
    void restoreEmailSettings().catch(() => undefined);
  }, []);
  const [createdSession, setCreatedSession] = useState<RecordingSession | null>(restoreRecordingSession);
  const [recordingState, setRecordingState] = useState<RecordingViewState>('Ready');
  const [recordingNavigationLocked, setRecordingNavigationLocked] = useState(false);
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
  const isActiveRecording = (/^\/meetings\/[^/]+\/recording$/.test(location.pathname) && (recordingState !== 'Ready' || recordingNavigationLocked)) || /^\/meetings\/[^/]+\/processing$/.test(location.pathname);
  const shouldWarnBeforeLeavingRecording = /^\/meetings\/[^/]+\/recording$/.test(location.pathname) && (recordingState !== 'Ready' || recordingNavigationLocked);
  const isDocumentConnectionRoute = location.pathname === '/setup/document' || location.pathname === '/settings/document';

  useEffect(() => {
    if (!shouldWarnBeforeLeavingRecording) return;
    const guardedUrl = `${window.location.pathname}${window.location.search}${window.location.hash}`;
    const guardedState = { ...(window.history.state ?? {}), meetingAutomationRecordingGuard: true };
    window.history.pushState(guardedState, '', guardedUrl);
    const blockBackNavigation = (event: PopStateEvent) => {
      event.stopImmediatePropagation();
      window.history.pushState(guardedState, '', guardedUrl);
    };
    const warnBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('popstate', blockBackNavigation, true);
    window.addEventListener('beforeunload', warnBeforeUnload);
    return () => {
      window.removeEventListener('popstate', blockBackNavigation, true);
      window.removeEventListener('beforeunload', warnBeforeUnload);
    };
  }, [shouldWarnBeforeLeavingRecording]);

  return (
    <div className="app-shell">
      {!isDocumentConnectionRoute && <header className="product-header">
        <Link className="product-header-brand" to="/" aria-label="Windsoft Minutes 홈">
          <img src="/assets/windsoft-favicon-mark.png" alt="" />
          <span className="product-header-company"><span>Wind</span><span>soft</span></span>
          <span className="product-header-divider" aria-hidden="true" />
          <span className="product-header-product">Minutes</span>
        </Link>
      </header>}
      <div className="app-route-viewport">
        <Routes>
          <Route path="/" element={<SetupGate><HomePage /></SetupGate>} />
          <Route path="/setup/document" element={<DocumentSetupWizard />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="/settings/document" element={<DocumentSetupWizard settings />} />
          <Route path="/meetings" element={<MeetingsPage />} />
          <Route path="/meetings/new" element={<SetupGate><NewMeetingRoute onCreated={(session) => { setCreatedSession(session); saveRecordingSession(session); setRecordingState('Ready'); setRecordingNavigationLocked(false); }} onSubmitted={onNewMeetingSubmit} /></SetupGate>} />
          <Route path="/meetings/:documentId" element={<MeetingDetailPage />} />
          <Route path="/meetings/:sessionId/recording" element={<MeetingRecordingRoute session={createdSession} setState={setRecordingState} setNavigationLocked={setRecordingNavigationLocked} clearSession={() => setCreatedSession(null)} mediaAdapter={mediaAdapter} />} />
          <Route path="/meetings/:sessionId/processing" element={<ProcessingPage />} />
          <Route path="/meetings/:sessionId/review" element={<ReviewSpeakerPage drafts={speakerDrafts} onInitialize={initializeSpeakerDraft} onChange={updateSpeakerDraft} onCommit={commitSpeakerMappings} onMinutesSave={onMinutesSave} minutesSaveState={minutesSaveState} />} />
          <Route path="/meetings/:sessionId/complete" element={<CompletePage />} />
          <Route path="/participants" element={<SetupGate><ParticipantsPage /></SetupGate>} />
          <Route path="/settings/participants" element={<SetupGate><ParticipantsPage /></SetupGate>} />
          <Route path="*" element={<FutureRouteNotice />} />
        </Routes>
      </div>
      {!isActiveRecording && !isDocumentConnectionRoute && <BottomNavigation />}
      <BackendRecoveryOverlay />
    </div>
  );
}

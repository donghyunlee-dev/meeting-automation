import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import {
  createParticipant,
  listParticipants,
  ParticipantApiError,
  type Participant,
  type ParticipantField,
  updateParticipant,
} from './participantsApi';
import './app.css';

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

export function App() {
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
  const createAttempt = useRef<{ payload: string; key: string } | null>(null);

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

  function openCreate() {
    setForm({ mode: 'create' });
    setFields(EMPTY_FIELDS);
    setFieldErrors({});
    setFormError('');
    setNotice('');
  }

  function openEdit(participant: Participant) {
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
        setFormError(error.code === 'PARTICIPANT_NOT_FOUND'
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
    <main className="app-shell">
      <header className="topbar">
        <a className="brand" href="#participants" aria-label="Meeting Automation 홈">M</a>
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
          <button className="button button-primary add-button" type="button" aria-label="새 참석자 추가" onClick={openCreate}>
            <span aria-hidden="true">＋</span> 참석자 추가
          </button>
        </div>

        {notice && <p className="notice" role="status">{notice}</p>}

        {viewState === 'loading' && <div className="state-card loading" role="status"><span className="spinner" aria-hidden="true" />참석자 목록을 불러오는 중…</div>}

        {viewState === 'error' && <div className="state-card error-card" role="alert"><p>{listError}</p><button className="button button-secondary" type="button" onClick={() => void refreshParticipants()}>다시 시도</button></div>}

        {viewState === 'ready' && participants.length === 0 && <div className="state-card empty-card"><div className="empty-icon" aria-hidden="true">◎</div><h3>등록된 참석자가 없습니다</h3><p>참석자를 추가하면 회의에서 선택할 수 있습니다.</p><button className="button button-primary" type="button" onClick={openCreate}>참석자 추가</button></div>}

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
                <button className="button button-secondary edit-button" type="button" aria-label="수정" onClick={() => openEdit(participant)}>수정</button>
              </article>)}
            </div>
          </>}
        </>}

        {form && <div className="form-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !submitting) closeForm(); }}>
          <section className="participant-form-panel" role="dialog" aria-modal="true" aria-labelledby="form-title">
            <div className="form-heading"><div><p className="eyebrow">PARTICIPANT</p><h3 id="form-title">{form.mode === 'create' ? '참석자 추가' : '참석자 수정'}</h3></div><button type="button" className="icon-button" aria-label="닫기" onClick={closeForm} disabled={submitting}>×</button></div>
            <p className="form-description">이름과 이메일 정보를 입력해 주세요.</p>
            <form onSubmit={(event) => void handleSubmit(event)} noValidate>
              <div className="form-field"><label htmlFor="participant-name">이름</label><input id="participant-name" autoComplete="name" value={fields.name} onChange={(event) => changeField('name', event.target.value)} aria-invalid={Boolean(fieldErrors.name)} aria-describedby={fieldErrors.name ? 'name-error' : undefined} />{fieldErrors.name && <p className="field-error" id="name-error">{fieldMessage('name', fieldErrors.name)}</p>}</div>
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

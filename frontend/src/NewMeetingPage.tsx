import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { AsyncState } from './components/async-state/AsyncState';
import { normalizeApiError } from './lib/api/normalizeApiError';
import type { Participant } from './participantsApi';
import { MeetingParticipantSelector } from './MeetingParticipantSelector';
import {
  getDocumentAppConfig,
  listMeetingParticipants,
  listMeetingTemplates,
  NewMeetingApiError,
  type DocumentAppConfig,
  type MeetingSessionAttempt,
  type MeetingDraft,
  type MeetingTemplate,
} from './newMeetingApi';

export type NewMeetingPayload = {
  title: string;
  meetingDate: string;
  templateId: string;
  participantIds: string[];
  timezone: string;
  documentConnectionVersion: number;
};

type Resource<T> =
  | { status: 'loading'; data?: T }
  | { status: 'ready'; data: T }
  | { status: 'error'; data?: T };

const QUERY_MESSAGES = {
  config: '연결 정보를 불러오지 못했습니다. 다시 시도해 주세요.',
  templates: 'Template 목록을 불러오지 못했습니다. 다시 시도해 주세요.',
  participants: '참석자 목록을 불러오지 못했습니다. 다시 시도해 주세요.',
};
const SAFE_QUERY_ERROR = normalizeApiError({ error: { code: 'REQUEST_FAILED' } });

function localDateString(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

export function NewMeetingPage({ onSubmit, initialDraft }: { onSubmit: (attempt: MeetingSessionAttempt) => void | Promise<void>; initialDraft?: MeetingDraft }) {
  const [config, setConfig] = useState<Resource<DocumentAppConfig>>({ status: 'loading' });
  const [templates, setTemplates] = useState<Resource<MeetingTemplate[]>>({ status: 'loading' });
  const [participants, setParticipants] = useState<Resource<Participant[]>>({ status: 'loading' });
  const [title, setTitle] = useState(initialDraft?.title ?? '');
  const [meetingDate, setMeetingDate] = useState(initialDraft?.meetingDate ?? localDateString());
  const [templateId, setTemplateId] = useState(initialDraft?.templateId ?? 'default.md');
  const [participantIds, setParticipantIds] = useState<string[]>([...(initialDraft?.participantIds ?? [])]);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState('');
  const attemptRef = useRef<{ signature: string; attempt: MeetingSessionAttempt; uncertain: boolean } | null>(null);
  const submittingRef = useRef(false);

  const loadConfig = useCallback(async () => {
    setConfig((current) => ({ status: 'loading', data: current.data }));
    try {
      const result = await getDocumentAppConfig();
      setConfig({ status: 'ready', data: result });
    } catch {
      setConfig((current) => ({ status: 'error', data: current.data }));
    }
  }, []);

  const loadTemplates = useCallback(async () => {
    setTemplates((current) => ({ status: 'loading', data: current.data }));
    try {
      const items = await listMeetingTemplates();
      setTemplates({ status: 'ready', data: items });
      setTemplateId((current) => items.some((item) => item.id === current) ? current : items.find((item) => item.id === 'default.md')?.id ?? items[0]?.id ?? '');
    } catch {
      setTemplates((current) => ({ status: 'error', data: current.data }));
    }
  }, []);

  const loadParticipants = useCallback(async (clearSelection = false) => {
    if (clearSelection) {
      setParticipantIds([]);
      setPickerOpen(false);
    }
    setParticipants((current) => clearSelection ? { status: 'loading' } : { status: 'loading', data: current.data });
    try {
      const items = await listMeetingParticipants();
      setParticipants({ status: 'ready', data: items });
      setParticipantIds((current) => clearSelection ? [] : current.filter((id) => items.some((item) => item.id === id)));
    } catch {
      setParticipants((current) => clearSelection ? { status: 'error' } : { status: 'error', data: current.data });
    }
  }, []);

  useEffect(() => {
    let active = true;
    void getDocumentAppConfig().then((result) => {
      if (active) setConfig({ status: 'ready', data: result });
    }).catch(() => {
      if (active) setConfig({ status: 'error' });
    });
    void listMeetingTemplates().then((items) => {
      if (!active) return;
      setTemplates({ status: 'ready', data: items });
      setTemplateId((current) => items.some((item) => item.id === current) ? current : items.find((item) => item.id === 'default.md')?.id ?? items[0]?.id ?? '');
    }).catch(() => {
      if (active) setTemplates({ status: 'error' });
    });
    void listMeetingParticipants().then((items) => {
      if (active) setParticipants({ status: 'ready', data: items });
    }).catch(() => {
      if (active) setParticipants({ status: 'error' });
    });
    return () => { active = false; };
  }, []);

  const configLoading = config.status === 'loading';
  const templatesLoading = templates.status === 'loading';
  const participantsLoading = participants.status === 'loading';
  const anyLoading = configLoading || templatesLoading || participantsLoading;
  const availableTemplates = templates.data ?? [];
  const availableParticipants = participants.data ?? [];
  const unavailable = config.status === 'error' || templates.status === 'error' || participants.status === 'error'
    || (templates.status === 'ready' && availableTemplates.length === 0)
    || (participants.status === 'ready' && availableParticipants.length === 0)
    || (config.status === 'ready' && (!config.data.company.timezone
      || config.data.setup?.status !== 'READY' || config.data.setup.canCreateMeeting !== true
      || !Number.isInteger(config.data.document?.connectionVersion) || (config.data.document?.connectionVersion ?? 0) < 1));

  function toggleParticipant(id: string) {
    setParticipantIds((current) => current.includes(id) ? current.filter((item) => item !== id) : [...current, id]);
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submittingRef.current) return;
    setSubmitted(true);
    if (anyLoading || unavailable) return;
    const trimmedTitle = title.trim();
    const currentConfig = config.data;
    if (!trimmedTitle || !meetingDate || !templateId || participantIds.length === 0 || !currentConfig?.company.timezone
      || currentConfig.setup?.status !== 'READY' || currentConfig.setup.canCreateMeeting !== true
      || !currentConfig.document || currentConfig.document.connectionVersion < 1) return;

    const formSnapshot = {
      title: trimmedTitle,
      meetingDate,
      templateId,
      participantIds: Object.freeze([...participantIds]),
      timezone: currentConfig.company.timezone,
      documentConnectionVersion: currentConfig.document.connectionVersion,
    };
    const signature = JSON.stringify(formSnapshot);
    submittingRef.current = true;
    setSubmitting(true);
    setSubmitError('');
    let entry = attemptRef.current;
    if (!entry || entry.signature !== signature || !entry.uncertain) {
      const key = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      entry = { signature, attempt: Object.freeze({ ...formSnapshot, recoveryKey: key(), idempotencyKey: key() }), uncertain: false };
      attemptRef.current = entry;
    } else {
      try {
        const latest = await getDocumentAppConfig();
        if (latest.setup?.status !== 'READY' || latest.setup.canCreateMeeting !== true
          || latest.document?.connectionVersion !== entry.attempt.documentConnectionVersion) {
          setConfig({ status: 'ready', data: latest });
          await loadParticipants(true);
          attemptRef.current = null;
          setSubmitError('문서 연결이 바뀌었습니다. 설정과 참석자 목록을 새로 확인했습니다. 내용을 확인한 뒤 다시 시작해 주세요.');
          submittingRef.current = false;
          setSubmitting(false);
          return;
        }
      } catch {
        setSubmitError('문서 연결 상태를 확인하지 못했습니다. 다시 시도해 주세요.');
        submittingRef.current = false;
        setSubmitting(false);
        return;
      }
    }

    try {
      await onSubmit(entry.attempt);
      attemptRef.current = null;
    } catch (error) {
      if (error instanceof NewMeetingApiError && error.code === 'DOCUMENT_CONNECTION_CHANGED') {
        attemptRef.current = null;
        await Promise.all([loadConfig(), loadParticipants(true)]);
        setSubmitError('문서 연결이 바뀌었습니다. 설정과 참석자 목록을 새로 확인했습니다. 내용을 확인한 뒤 다시 시작해 주세요.');
      } else {
        const uncertain = error instanceof NewMeetingApiError && error.responseUncertain;
        if (attemptRef.current) attemptRef.current.uncertain = uncertain;
        setSubmitError(error instanceof NewMeetingApiError && error.code !== 'NETWORK_ERROR'
          ? error.message
          : '회의를 시작하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.');
      }
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  }

  const titleError = submitted && !title.trim();
  const meetingDateError = submitted && !meetingDate;
  const templateError = submitted && !templateId;
  const participantError = submitted && participantIds.length === 0;

  return (
    <main className="new-meeting-page page-scroll-layout">
      <header className="new-meeting-heading">
        <h1>
          <svg className="new-meeting-title-icon" viewBox="0 0 24 24" aria-hidden="true">
            <rect x="3.5" y="5" width="17" height="16" rx="3" />
            <path d="M8 3v4M16 3v4M3.5 10h17M12 13v5M9.5 15.5h5" />
          </svg>
          새 회의
        </h1>
      </header>

      <div className="page-scroll-content">
      <form className="new-meeting-form" onSubmit={handleSubmit} noValidate>
        <div className="meeting-field">
          <label htmlFor="meeting-title">회의 제목</label>
          <input id="meeting-title" type="text" value={title} onChange={(event) => setTitle(event.target.value)} aria-invalid={titleError} aria-describedby={titleError ? 'meeting-title-error' : undefined} />
          {titleError && <p className="meeting-field-error" id="meeting-title-error">회의 제목을 입력해 주세요.</p>}
        </div>

        <div className="meeting-field">
          <label htmlFor="meeting-date">회의 날짜</label>
          <input id="meeting-date" type="date" value={meetingDate} onChange={(event) => setMeetingDate(event.target.value)} aria-invalid={meetingDateError} aria-describedby={meetingDateError ? 'meeting-date-error' : undefined} />
          {meetingDateError && <p className="meeting-field-error" id="meeting-date-error">회의 날짜를 선택해 주세요.</p>}
        </div>

        <section className="meeting-resource" aria-label="문서 연결 정보">
          {config.status === 'loading' && !config.data && <AsyncState state={{ status: 'loading', label: '연결 정보를 확인하는 중…' }} renderReady={() => null} />}
          {config.status === 'error' && (config.data
            ? <AsyncState state={{ status: 'ready', data: config.data, warning: SAFE_QUERY_ERROR, onRetry: () => void loadConfig(), retryLabel: '연결 정보 다시 시도' }} renderReady={() => null} />
            : <AsyncState state={{ status: 'error', title: '연결 정보를 불러오지 못했습니다.', error: SAFE_QUERY_ERROR, description: QUERY_MESSAGES.config, onRetry: () => void loadConfig(), retryLabel: '연결 정보 다시 시도' }} renderReady={() => null} />)}
          {config.status === 'ready' && (config.data.setup?.status !== 'READY' || !config.data.setup.canCreateMeeting) && <p className="meeting-query-error" role="alert">문서 서비스 연결 설정을 완료한 뒤 회의를 시작할 수 있습니다.</p>}
        </section>

        <div className="meeting-field">
          <label htmlFor="meeting-template">회의록 선택</label>
          {templates.status === 'loading' && !templates.data && <AsyncState state={{ status: 'loading', label: 'Template 목록을 불러오는 중…' }} renderReady={() => null} />}
          {templates.status === 'error' && (templates.data
            ? <AsyncState state={{ status: 'ready', data: templates.data, warning: SAFE_QUERY_ERROR, onRetry: () => void loadTemplates(), retryLabel: 'Template 목록 다시 시도' }} renderReady={() => null} />
            : <AsyncState state={{ status: 'error', title: 'Template 목록 조회에 실패했습니다.', error: SAFE_QUERY_ERROR, description: QUERY_MESSAGES.templates, onRetry: () => void loadTemplates(), retryLabel: 'Template 목록 다시 시도' }} renderReady={() => null} />)}
          {templates.status === 'ready' && availableTemplates.length === 0 && <AsyncState state={{ status: 'empty', title: '사용 가능한 Template이 없습니다.', description: '나중에 다시 확인해 주세요.' }} renderReady={() => null} />}
          <div className="meeting-select-control">
            <select id="meeting-template" value={templateId} onChange={(event) => setTemplateId(event.target.value)} disabled={templates.status !== 'ready' || availableTemplates.length === 0} aria-invalid={templateError} aria-describedby={templateError ? 'meeting-template-error' : undefined}>
              {availableTemplates.map((template) => <option value={template.id} key={template.id}>{template.name}</option>)}
            </select>
            <svg className="meeting-dropdown-chevron" viewBox="0 0 20 20" aria-hidden="true"><path d="m5 7.5 5 5 5-5" /></svg>
          </div>
          {templateError && <p className="meeting-field-error" id="meeting-template-error">회의록을 선택해 주세요.</p>}
        </div>

        <section className="meeting-field participant-selection" aria-labelledby="participant-label">
          <div className="participant-heading">
            <h2 id="participant-label">참석자</h2>
          </div>
          {participants.status === 'loading' && !participants.data && <AsyncState state={{ status: 'loading', label: '참석자 목록을 불러오는 중…' }} renderReady={() => null} />}
          {participants.status === 'error' && (participants.data
            ? <AsyncState state={{ status: 'ready', data: participants.data, warning: SAFE_QUERY_ERROR, onRetry: () => void loadParticipants(), retryLabel: '참석자 목록 다시 시도' }} renderReady={() => null} />
            : <AsyncState state={{ status: 'error', title: '참석자 목록을 불러오지 못했습니다.', error: SAFE_QUERY_ERROR, description: QUERY_MESSAGES.participants, onRetry: () => void loadParticipants(), retryLabel: '참석자 목록 다시 시도' }} renderReady={() => null} />)}
          {participants.status === 'ready' && availableParticipants.length === 0 && <AsyncState state={{ status: 'empty', title: '참석자가 없습니다.', action: <Link to="/participants">참석자 관리에서 목록을 준비해 주세요.</Link> }} renderReady={() => null} />}

          <MeetingParticipantSelector
            participants={availableParticipants}
            selectedIds={participantIds}
            isDisabled={participants.status !== 'ready'}
            isReady={participants.status === 'ready'}
            pickerOpen={pickerOpen}
            participantError={participantError}
            onTogglePicker={() => setPickerOpen((open) => !open)}
            onToggleParticipant={toggleParticipant}
          />
          {participantError && <p className="meeting-field-error" id="meeting-participants-error">참석자를 한 명 이상 추가해 주세요.</p>}
        </section>

        {submitError && <p role="alert" className="meeting-query-error">{submitError}</p>}
        <div className="meeting-form-footer">
          <button className="button button-primary meeting-submit" type="submit" disabled={anyLoading || unavailable || submitting}>{submitting ? '회의 시작 중…' : '회의 시작'}</button>
        </div>
      </form>
      </div>
    </main>
  );
}

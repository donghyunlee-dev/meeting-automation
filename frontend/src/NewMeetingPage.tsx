import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import type { Participant } from './participantsApi';
import { MeetingParticipantSelector } from './MeetingParticipantSelector';
import {
  getDocumentAppConfig,
  listMeetingParticipants,
  listMeetingTemplates,
  NewMeetingApiError,
  type DocumentAppConfig,
  type MeetingSessionAttempt,
  type MeetingTemplate,
} from './newMeetingApi';

export type NewMeetingPayload = {
  title: string;
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
  config: '회사 시간대를 불러오지 못했습니다. 다시 시도해 주세요.',
  templates: 'Template 목록을 불러오지 못했습니다. 다시 시도해 주세요.',
  participants: '참석자 목록을 불러오지 못했습니다. 다시 시도해 주세요.',
};

export function NewMeetingPage({ onSubmit }: { onSubmit: (attempt: MeetingSessionAttempt) => void | Promise<void> }) {
  const [config, setConfig] = useState<Resource<DocumentAppConfig>>({ status: 'loading' });
  const [templates, setTemplates] = useState<Resource<MeetingTemplate[]>>({ status: 'loading' });
  const [participants, setParticipants] = useState<Resource<Participant[]>>({ status: 'loading' });
  const [title, setTitle] = useState('');
  const [templateId, setTemplateId] = useState('');
  const [participantIds, setParticipantIds] = useState<string[]>([]);
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
      setTemplateId((current) => current || (items.some((item) => item.id === 'default.md') ? 'default.md' : ''));
    } catch {
      setTemplates((current) => ({ status: 'error', data: current.data }));
    }
  }, []);

  const loadParticipants = useCallback(async (clearSelection = false) => {
    setParticipants((current) => ({ status: 'loading', data: current.data }));
    try {
      const items = await listMeetingParticipants();
      setParticipants({ status: 'ready', data: items });
      setParticipantIds((current) => clearSelection ? [] : current.filter((id) => items.some((item) => item.id === id)));
    } catch {
      setParticipants((current) => ({ status: 'error', data: current.data }));
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
      setTemplateId((current) => current || (items.some((item) => item.id === 'default.md') ? 'default.md' : ''));
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
    if (!trimmedTitle || !templateId || participantIds.length === 0 || !currentConfig?.company.timezone
      || currentConfig.setup?.status !== 'READY' || currentConfig.setup.canCreateMeeting !== true
      || !currentConfig.document || currentConfig.document.connectionVersion < 1) return;

    const formSnapshot = {
      title: trimmedTitle,
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
  const templateError = submitted && !templateId;
  const participantError = submitted && participantIds.length === 0;

  return (
    <main className="new-meeting-page">
      <header className="new-meeting-heading">
        <p className="eyebrow">NEW MEETING</p>
        <h1>새 회의</h1>
        <p>회의 정보를 입력하고 참석자를 선택해 주세요.</p>
      </header>

      <form className="new-meeting-form" onSubmit={handleSubmit} noValidate>
        <div className="meeting-field">
          <label htmlFor="meeting-title">회의 제목</label>
          <input id="meeting-title" type="text" value={title} onChange={(event) => setTitle(event.target.value)} aria-invalid={titleError} aria-describedby={titleError ? 'meeting-title-error' : undefined} />
          {titleError && <p className="meeting-field-error" id="meeting-title-error">회의 제목을 입력해 주세요.</p>}
        </div>

        <section className="meeting-resource" aria-labelledby="timezone-label">
          <h2 id="timezone-label">회사 시간대</h2>
          {config.status === 'loading' && <p className="meeting-query-state" role="status">회사 시간대를 불러오는 중…</p>}
          {config.status === 'error' && <div className="meeting-query-error" role="alert"><p>{QUERY_MESSAGES.config}</p><button className="button button-secondary" type="button" onClick={() => void loadConfig()} disabled={configLoading}>회사 시간대 다시 시도</button></div>}
          {config.status === 'ready' && <p className="meeting-timezone">{config.data.company.timezone}</p>}
          {config.status === 'ready' && (config.data.setup?.status !== 'READY' || !config.data.setup.canCreateMeeting) && <p className="meeting-query-error" role="alert">Document connection setup or switching must finish before a meeting can start. <Link to="/setup/document">Open document settings</Link></p>}
        </section>

        <div className="meeting-field">
          <label htmlFor="meeting-template">Template</label>
          {templates.status === 'loading' && <p className="meeting-query-state" role="status">Template 목록을 불러오는 중…</p>}
          {templates.status === 'error' && <div className="meeting-query-error" role="alert"><p>{QUERY_MESSAGES.templates}</p><button className="button button-secondary" type="button" onClick={() => void loadTemplates()} disabled={templatesLoading}>Template 목록 다시 시도</button></div>}
          {templates.status === 'ready' && availableTemplates.length === 0 && <p className="meeting-empty-state">사용 가능한 Template이 없습니다. 나중에 다시 시도해 주세요.</p>}
          <select id="meeting-template" value={templateId} onChange={(event) => setTemplateId(event.target.value)} disabled={templates.status !== 'ready' || availableTemplates.length === 0} aria-invalid={templateError} aria-describedby={templateError ? 'meeting-template-error' : undefined}>
            <option value="">Template 선택</option>
            {availableTemplates.map((template) => <option value={template.id} key={template.id}>{template.name}</option>)}
          </select>
          {templateError && <p className="meeting-field-error" id="meeting-template-error">Template을 선택해 주세요.</p>}
        </div>

        <section className="meeting-field participant-selection" aria-labelledby="participant-label">
          <h2 id="participant-label">참석자</h2>
          {participants.status === 'loading' && <p className="meeting-query-state" role="status">참석자 목록을 불러오는 중…</p>}
          {participants.status === 'error' && <div className="meeting-query-error" role="alert"><p>{QUERY_MESSAGES.participants}</p><button className="button button-secondary" type="button" onClick={() => void loadParticipants()} disabled={participantsLoading}>참석자 목록 다시 시도</button></div>}
          {participants.status === 'ready' && availableParticipants.length === 0 && <p className="meeting-empty-state">참석자가 없습니다. <Link to="/settings/participants">참석자 관리에서 목록을 준비해 주세요.</Link></p>}

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
    </main>
  );
}

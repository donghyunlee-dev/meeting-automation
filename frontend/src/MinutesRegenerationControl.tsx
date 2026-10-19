import { useEffect, useRef, useState } from 'react';
import { listMeetingTemplates, type MeetingTemplate } from './newMeetingApi';
import { MinutesRegenerationApiError, getMinutesRegenerationSnapshot, regenerateMinutes, type MinutesRegenerationSnapshot } from './minutesRegenerationApi';
import { ProcessingPoller } from './processingPoller';
import type { ReviewSession } from './reviewApi';

type Props = {
  session: ReviewSession;
  blocked?: boolean;
  prepareSnapshot: () => Promise<ReviewSession | null>;
  applySnapshot: (snapshot: ReviewSession, replaceMinutesDraft?: boolean) => boolean;
  onProcessingChange?: (processing: boolean) => void;
};

type Intent = { templateId: string; baseVersion: number; idempotencyKey: string };
type State = 'idle' | 'confirming' | 'preparing' | 'processing' | 'checking' | 'success' | 'failed' | 'unknown';

const TERMINAL_OPERATION = 'MINUTES_REGENERATION';

export function MinutesRegenerationControl({ session, blocked = false, prepareSnapshot, applySnapshot, onProcessingChange }: Props) {
  const [templates, setTemplates] = useState<{ status: 'loading' | 'ready' | 'error'; items?: MeetingTemplate[] }>({ status: 'loading' });
  const [selectedTemplateId, setSelectedTemplateId] = useState(session.minutes?.templateId ?? '');
  const [state, setState] = useState<State>('idle');
  const [message, setMessage] = useState('');
  const [intent, setIntent] = useState<Intent | null>(null);
  const [progress, setProgress] = useState<number | null>(null);
  const opener = useRef<HTMLButtonElement>(null);
  const templateSelector = useRef<HTMLSelectElement>(null);
  const confirmButton = useRef<HTMLButtonElement>(null);
  const resultStatus = useRef<HTMLParagraphElement>(null);
  const poller = useRef<ProcessingPoller | null>(null);
  const pollCleanup = useRef<(() => void) | null>(null);
  const mountedSession = useRef(session.sessionId);
  const currentTemplate = useRef(session.minutes?.templateId ?? '');

  useEffect(() => {
    let active = true;
    void listMeetingTemplates().then((items) => {
      if (active) setTemplates({ status: 'ready', items });
    }).catch(() => {
      if (active) setTemplates({ status: 'error' });
    });
    return () => { active = false; };
  }, [session.sessionId]);

  useEffect(() => {
    if (mountedSession.current !== session.sessionId) {
      mountedSession.current = session.sessionId;
      currentTemplate.current = session.minutes?.templateId ?? '';
      pollCleanup.current?.();
      setIntent(null);
      setState('idle');
      setMessage('');
      setProgress(null);
      setSelectedTemplateId(session.minutes?.templateId ?? '');
    } else if (currentTemplate.current !== (session.minutes?.templateId ?? '')) {
      currentTemplate.current = session.minutes?.templateId ?? '';
      setSelectedTemplateId(session.minutes?.templateId ?? '');
    }
  }, [session.sessionId, session.minutes?.templateId]);

  useEffect(() => () => pollCleanup.current?.(), []);

  function closeDialog() {
    setState('idle');
    setSelectedTemplateId(session.minutes?.templateId ?? '');
    requestAnimationFrame(() => templateSelector.current?.focus());
  }

  function handleDialogKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeDialog();
      return;
    }
    if (event.key !== 'Tab') return;
    const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled), select:not(:disabled)'));
    if (buttons.length === 0) return;
    const first = buttons[0];
    const last = buttons[buttons.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  }

  function beginPolling(nextIntent: Intent) {
    pollCleanup.current?.();
    setIntent(nextIntent);
    setState('processing');
    onProcessingChange?.(true);
    setMessage('Minutes를 재생성하고 있습니다. 완료될 때까지 Review 편집은 잠시 중지됩니다.');
    const currentPoller = new ProcessingPoller({
      sessionId: session.sessionId,
      getStatus: async (sessionId, signal) => getMinutesRegenerationSnapshot(sessionId, signal),
      onSnapshot: (snapshot) => handleSnapshot(snapshot, nextIntent),
      onError: (error) => {
        if (error.code === 'SESSION_NOT_FOUND') {
          setState('failed');
          setMessage('회의를 찾을 수 없습니다. 회의 상태를 확인해 주세요.');
        } else setMessage('진행 상태를 확인하지 못했습니다. 연결 상태를 확인한 뒤 다시 확인해 주세요.');
      },
    });
    poller.current = currentPoller;
    const onVisibility = () => { if (document.visibilityState === 'visible') currentPoller.refreshNow(); };
    document.addEventListener('visibilitychange', onVisibility);
    const stop = () => {
      document.removeEventListener('visibilitychange', onVisibility);
      currentPoller.stop();
      if (poller.current === currentPoller) poller.current = null;
      if (pollCleanup.current === stop) pollCleanup.current = null;
    };
    pollCleanup.current = stop;
    currentPoller.start();
  }

  function handleSnapshot(snapshot: MinutesRegenerationSnapshot, expected: Intent) {
    if (snapshot.sessionId !== session.sessionId) return;
    if (snapshot.status === 'PROCESSING') {
      if (snapshot.processing.stage === 'DRAFT_REGENERATION') {
        setState('processing');
        setProgress(snapshot.processing.progressPercent ?? null);
        setMessage(snapshot.processing.progressPercent == null
          ? 'Minutes를 재생성하고 있습니다. 완료될 때까지 Review 편집은 잠시 중지됩니다.'
          : `Minutes 재생성 진행 ${snapshot.processing.progressPercent}%`);
      }
      return;
    }
    onProcessingChange?.(false);
    pollCleanup.current?.();
    if (snapshot.status !== 'REVIEW' || !snapshot.review) {
      setState('failed');
      setMessage('재생성 결과를 확인할 수 없습니다. 최신 회의 상태를 확인해 주세요.');
      return;
    }
    const operation = snapshot.lastOperation;
    const correlated = snapshot.version > expected.baseVersion
      && operation?.type === TERMINAL_OPERATION
      && operation.requestedTemplateId === expected.templateId
      && (operation.outcome === 'SUCCEEDED' || operation.outcome === 'FAILED');
    if (!correlated) {
      setState('unknown');
      setMessage('재생성 결과를 확인할 수 없습니다. 최신 회의 상태를 다시 확인해 주세요.');
      return;
    }
    if (!applySnapshot(snapshot.review, true)) {
      setState('unknown');
      setMessage('최신 회의 정보를 적용하지 못했습니다. 다시 확인해 주세요.');
      return;
    }
    if (operation.outcome === 'SUCCEEDED') {
      setSelectedTemplateId(expected.templateId);
      setState('success');
      setMessage('Minutes를 선택한 Template으로 재생성했습니다.');
    } else {
      setSelectedTemplateId(session.minutes?.templateId ?? '');
      setState('failed');
      setMessage('Minutes를 재생성하지 못해 이전 회의록을 복구했습니다. 기존 회의록을 확인해 주세요.');
    }
    requestAnimationFrame(() => resultStatus.current?.focus());
  }

  async function confirmRegeneration() {
    if (state !== 'confirming' || blocked || !session.allowedActions.includes('REGENERATE_MINUTES')) return;
    const selected = templates.items?.find(({ id }) => id === selectedTemplateId);
    if (!selected || selected.id === session.minutes?.templateId) return;
    setState('preparing');
    setMessage('저장된 Minutes와 최신 회의 정보를 확인하고 있습니다.');
    const fresh = await prepareSnapshot();
    if (!fresh || fresh.sessionId !== session.sessionId || !fresh.allowedActions.includes('REGENERATE_MINUTES')) {
      setState('confirming');
      setMessage('최신 회의 정보를 확인하지 못했습니다. 변경 내용은 보존했습니다. 다시 확인해 주세요.');
      return;
    }
    const nextIntent: Intent = {
      templateId: selected.id,
      baseVersion: fresh.version,
      idempotencyKey: crypto.randomUUID(),
    };
    try {
      await regenerateMinutes(session.sessionId, fresh.version, selected.id, nextIntent.idempotencyKey);
      beginPolling(nextIntent);
    } catch (error) {
      if (error instanceof MinutesRegenerationApiError && error.status === 412) {
        setState('checking');
        setMessage('회의가 변경되었습니다. 최신 회의 정보를 확인하고 있습니다.');
        try {
          const current = await getMinutesRegenerationSnapshot(session.sessionId, new AbortController().signal);
          if (current.status === 'REVIEW' && current.review && current.version >= fresh.version) applySnapshot(current.review);
          setState('confirming');
          setMessage('회의 정보가 변경되었습니다. 최신 내용을 확인한 뒤 다시 확인해 주세요.');
        } catch {
          setState('confirming');
          setMessage('최신 회의 정보를 확인하지 못했습니다. 다시 확인해 주세요.');
        }
        return;
      }
      if (error instanceof MinutesRegenerationApiError && error.outcomeUnknown) {
        setMessage('요청 결과를 확인하고 있습니다. 같은 요청을 다시 보내지 않습니다.');
        beginPolling(nextIntent);
        return;
      }
      setState('confirming');
      setMessage('Minutes 재생성 요청을 시작하지 못했습니다. 변경 내용은 보존했습니다.');
    }
  }

  async function refreshOutcome() {
    if (!intent) return;
    setState('checking');
    setMessage('최신 회의 상태를 확인하고 있습니다.');
    if (poller.current) { poller.current.refreshNow(); return; }
    beginPolling(intent);
  }

  const currentTemplateId = session.minutes?.templateId ?? '';
  const canRegenerate = templates.status === 'ready' && templates.items?.some(({ id }) => id === selectedTemplateId)
    && selectedTemplateId !== currentTemplateId && session.allowedActions.includes('REGENERATE_MINUTES') && !blocked;
  const busy = state === 'preparing' || state === 'processing' || state === 'checking';

  useEffect(() => { onProcessingChange?.(state === 'processing' || state === 'checking'); }, [onProcessingChange, state]);

  return <section className="minutes-regeneration" aria-labelledby="minutes-regeneration-heading">
    <div className="minutes-regeneration-heading">
      <h2 id="minutes-regeneration-heading">다른 템플릿으로 다시 생성</h2>
      <p>현재 회의록이 있을 때 사용할 수 있습니다. 다시 생성하면 회의록 내용이 새 결과로 바뀝니다.</p>
    </div>
    {templates.status === 'loading' && <p role="status">Template 목록을 불러오는 중…</p>}
    {templates.status === 'error' && <div role="alert"><p>Template 목록을 불러오지 못했습니다. 다시 시도해 주세요.</p>
      <button type="button" className="button button-secondary" onClick={() => {
        setTemplates({ status: 'loading' });
        void listMeetingTemplates().then((items) => setTemplates({ status: 'ready', items })).catch(() => setTemplates({ status: 'error' }));
      }}>Template 목록 다시 시도</button></div>}
    {templates.status === 'ready' && <>
      <label htmlFor="minutes-regeneration-template">사용할 템플릿</label>
      <div className="meeting-select-control review-select-control"><select ref={templateSelector} id="minutes-regeneration-template" value={selectedTemplateId}
        onChange={(event) => setSelectedTemplateId(event.target.value)} disabled={busy || blocked}>
        {currentTemplateId && !templates.items?.some(({ id }) => id === currentTemplateId)
          && <option value={currentTemplateId}>현재 Template ({currentTemplateId}) — 목록에서 사용할 수 없음</option>}
        {templates.items?.map((template) => <option key={template.id} value={template.id}>{template.name} (v{template.version})</option>)}
      </select><svg className="meeting-dropdown-chevron" viewBox="0 0 20 20" aria-hidden="true"><path d="m5 7.5 5 5 5-5" /></svg></div>
      {!templates.items?.some(({ id }) => id === currentTemplateId) && <p role="status">현재 Template을 사용할 수 없어 재생성을 시작할 수 없습니다.</p>}
      <button ref={opener} type="button" className="button button-secondary" disabled={!canRegenerate || busy}
        onClick={() => { setState('confirming'); setMessage(''); requestAnimationFrame(() => confirmButton.current?.focus()); }}>
        선택한 템플릿으로 다시 생성
      </button>
    </>}
    {message && <p ref={resultStatus} tabIndex={state === 'success' || state === 'failed' ? -1 : undefined}
      role={state === 'failed' ? 'alert' : 'status'} aria-live="polite">{message}</p>}
    {state === 'processing' && progress !== null && <progress aria-label="Minutes 재생성 진행" value={progress} max={100} />}
    {state === 'unknown' && <button type="button" className="button button-secondary" onClick={() => void refreshOutcome()}>최신 결과 다시 확인</button>}
    {state === 'confirming' && <div className="minutes-regeneration-backdrop" data-testid="minutes-regeneration-backdrop"
      onMouseDown={(event) => { if (event.target === event.currentTarget) closeDialog(); }}>
      <div className="minutes-regeneration-dialog" role="dialog" aria-modal="true" aria-labelledby="minutes-regeneration-confirm-title"
        aria-describedby="minutes-regeneration-confirm-description" onKeyDown={handleDialogKeyDown}>
        <h3 id="minutes-regeneration-confirm-title">기존 회의록을 바꿀까요?</h3>
        <p id="minutes-regeneration-confirm-description">현재 회의록의 내용과 직접 수정한 부분이 {templates.items?.find(({ id }) => id === selectedTemplateId)?.name} 템플릿으로 만든 새 결과로 교체됩니다. Transcript와 화자 매핑은 유지됩니다.</p>
        <div className="minutes-regeneration-dialog-actions">
          <button type="button" className="button button-secondary" onClick={closeDialog}>취소</button>
          <button ref={confirmButton} type="button" className="button button-primary" disabled={!canRegenerate}
            onClick={() => void confirmRegeneration()}>새 회의록 만들기</button>
        </div>
      </div>
    </div>}
  </section>;
}

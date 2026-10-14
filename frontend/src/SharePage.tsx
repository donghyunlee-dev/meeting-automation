import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router';
import { getDocumentAppConfig, listMeetingParticipants, type DocumentAppConfig } from './newMeetingApi';
import type { Participant } from './participantsApi';
import { PublishApiError, confirmReview, createIdempotencyKey, getPublishSnapshot, publishSession, type PublishSnapshot, type ValidationIssue } from './publishApi';
import type { ReviewSession } from './reviewApi';

type Resource<T> = { status: 'loading' | 'ready' | 'error'; data?: T };
type RouteState = {
  review?: ReviewSession;
  speakerDrafts?: Record<string, string | null>;
  minutesDraft?: ReviewSession['minutes'];
  emailRecipientParticipantIds?: string[];
  notificationEnabled?: boolean;
  validationIssues?: ValidationIssue[];
};

function routeState(value: unknown): RouteState {
  return typeof value === 'object' && value !== null ? value as RouteState : {};
}

function safeDocumentUrl(value: string | null | undefined): string | null {
  if (!value) return null;
  try {
    const parsed = new URL(value);
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.href : null;
  } catch { return null; }
}

function errorMessage(error: unknown): string {
  if (!(error instanceof PublishApiError)) return '요청을 완료하지 못했습니다. 회의 상태를 다시 확인해 주세요.';
  if (error.code === 'SESSION_VERSION_CONFLICT' || error.status === 412) return '회의가 변경되었습니다. 최신 상태를 확인한 뒤 다시 시도해 주세요.';
  if (error.code === 'SESSION_STATE_CONFLICT' || error.status === 409) return '현재 회의 상태에서 요청을 진행할 수 없습니다. 최신 상태를 확인해 주세요.';
  if (error.code === 'DOCUMENT_CHANGE_BUSY') return '문서 설정 변경이 끝난 뒤 다시 시도해 주세요.';
  if (error.code === 'SESSION_NOT_FOUND' || error.status === 404) return '회의를 찾을 수 없습니다.';
  return '요청을 완료하지 못했습니다. 입력과 문서 설정을 확인한 뒤 다시 시도해 주세요.';
}

function issueLabel(issue: ValidationIssue, speakers: ReviewSession['speakers'] | undefined): string {
  const speakerMatch = issue.path.match(/^speakers\[(\d+)\]\.participantId$/);
  if (speakerMatch) {
    const speaker = speakers?.[Number(speakerMatch[1])];
    return `${speaker?.label ?? `화자 ${Number(speakerMatch[1]) + 1}`} 매핑을 확인해 주세요.`;
  }
  if (issue.path.startsWith('minutes.')) return '회의록 입력을 확인해 주세요.';
  return '검토 항목을 확인해 주세요.';
}

function issueAnchor(issue: ValidationIssue, speakers: ReviewSession['speakers'] | undefined): string {
  const speakerMatch = issue.path.match(/^speakers\[(\d+)\]\.participantId$/);
  if (speakerMatch) return `#speaker-${encodeURIComponent(speakers?.[Number(speakerMatch[1])]?.speakerId ?? '')}`;
  const minutesMatch = issue.path.match(/^minutes\.(summary|discussionPoints|decisions|actionItems|followUps)(?:\[(\d+)\])?/);
  if (minutesMatch) {
    const [, field, index] = minutesMatch;
    if (field === 'summary') return '#minutes-summary';
    if (field === 'actionItems') {
      const fieldName = issue.path.includes('.task') ? 'task' : issue.path.includes('.dueDate') ? 'date' : 'owner';
      return `#minutes-action-${fieldName}-${index ?? '0'}`;
    }
    if (index) return `#minutes-${field}-${index}`;
  }
  return '';
}

function providerLabel(provider: string | undefined): string {
  if (provider === 'NOTION') return 'Notion';
  if (provider === 'CONFLUENCE') return 'Confluence Cloud';
  return '문서 서비스';
}

function isPending(status: PublishSnapshot['status']): boolean {
  return status === 'PUBLISHING' || status === 'DOCUMENT_SAVED' || status === 'DELIVERING';
}

export function SharePage() {
  const { sessionId = '' } = useParams();
  const location = useLocation();
  const carriedState = routeState(location.state);
  const [config, setConfig] = useState<Resource<DocumentAppConfig>>({ status: 'loading' });
  const [participants, setParticipants] = useState<Resource<Participant[]>>({ status: 'loading' });
  const [snapshot, setSnapshot] = useState<PublishSnapshot | null>(null);
  const latestSnapshot = useRef<PublishSnapshot | null>(null);
  const [selectedRecipients, setSelectedRecipients] = useState<string[]>(carriedState.emailRecipientParticipantIds ?? []);
  const [notificationEnabled, setNotificationEnabled] = useState(carriedState.notificationEnabled ?? true);
  const initializedRecipients = useRef(carriedState.emailRecipientParticipantIds !== undefined);
  const [issues, setIssues] = useState<ValidationIssue[]>(carriedState.validationIssues ?? []);
  const [busy, setBusy] = useState(false);
  const confirmInFlight = useRef(false);
  const publishInFlight = useRef(false);
  const confirmAttempt = useRef<{ version: number; key: string } | null>(null);
  const [polling, setPolling] = useState(false);
  const [message, setMessage] = useState('');
  const [providerError, setProviderError] = useState(false);
  const refreshController = useRef<AbortController | null>(null);
  const reviewPath = `/meetings/${encodeURIComponent(sessionId)}/review`;
  const settingsState = {
    ...carriedState,
    emailRecipientParticipantIds: selectedRecipients,
    notificationEnabled,
    validationIssues: issues,
  };
  const providerReady = config.status === 'ready' && Boolean(config.data?.document?.provider)
    && config.data?.document?.configured === true;
  const documentUrl = safeDocumentUrl(snapshot?.document?.documentUrl);
  const documentSaved = Boolean(snapshot?.document?.documentId);
  const retryAllowed = snapshot?.status === 'DOCUMENT_FAILED' && snapshot.allowedActions.includes('PUBLISH');
  const carriedMappingDirty = Boolean(snapshot?.status === 'REVIEW' && carriedState.speakerDrafts
    && Object.entries(carriedState.speakerDrafts).some(([speakerId, participantId]) => {
      const currentSpeaker = snapshot.review?.speakers.find((speaker) => speaker.speakerId === speakerId);
      return !currentSpeaker || currentSpeaker.participantId !== (participantId ?? null);
    }));
  const carriedMinutesDirty = Boolean(snapshot?.status === 'REVIEW' && Object.prototype.hasOwnProperty.call(carriedState, 'minutesDraft')
    && JSON.stringify(carriedState.minutesDraft) !== JSON.stringify(snapshot.review?.minutes));
  const carriedReviewIsStale = Boolean(snapshot?.status === 'REVIEW' && carriedState.review
    && (carriedState.review.version !== snapshot.review?.version || carriedState.review.sessionId !== snapshot.sessionId));
  const shareBlockedByDraft = snapshot?.status === 'REVIEW' && (!snapshot.review || carriedMappingDirty || carriedMinutesDirty || carriedReviewIsStale);

  const acceptSnapshot = useCallback((next: PublishSnapshot) => {
    if (next.sessionId !== sessionId) return false;
    const previous = latestSnapshot.current;
    if (previous?.sessionId === next.sessionId && next.version < previous.version) return false;
    latestSnapshot.current = next;
    setSnapshot(next);
    return true;
  }, [sessionId]);

  const refreshStatus = useCallback(async (signal?: AbortSignal) => {
    const controller = new AbortController();
    refreshController.current?.abort();
    refreshController.current = controller;
    if (signal) signal.addEventListener('abort', () => controller.abort(), { once: true });
    const value = await getPublishSnapshot(sessionId, controller.signal);
    return acceptSnapshot(value) ? value : null;
  }, [acceptSnapshot, sessionId]);

  const loadResources = useCallback(() => {
    void getDocumentAppConfig().then((data) => {
      setConfig({ status: 'ready', data });
      setProviderError(false);
    }).catch(() => {
      setConfig((current) => ({ status: 'error', data: current.data }));
      setProviderError(true);
    });
    void listMeetingParticipants().then((data) => {
      setParticipants({ status: 'ready', data });
      if (!initializedRecipients.current) {
        initializedRecipients.current = true;
        setSelectedRecipients(data.map(({ id }) => id));
      }
    }).catch(() => setParticipants((current) => ({ status: 'error', data: current.data })));
  }, []);

  useEffect(() => {
    loadResources();
    const controller = new AbortController();
    void refreshStatus(controller.signal).catch(() => setMessage('회의 상태를 불러오지 못했습니다. 다시 확인해 주세요.'));
    const onFocus = () => {
      if (document.visibilityState === 'hidden') return;
      loadResources();
      void refreshStatus().catch(() => setMessage('회의 상태를 새로고침하지 못했습니다. 다시 확인해 주세요.'));
    };
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onFocus);
    return () => {
      controller.abort();
      refreshController.current?.abort();
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onFocus);
    };
  }, [loadResources, refreshStatus]);

  useEffect(() => {
    if (!polling) return undefined;
    let live = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let activeController: AbortController | undefined;
    let attempts = 0;
    const poll = async () => {
      activeController = new AbortController();
      try {
        const next = await getPublishSnapshot(sessionId, activeController.signal);
        if (!live) return;
        if (!acceptSnapshot(next)) {
          if (polling) timer = setTimeout(poll, 1000);
          return;
        }
        if (isPending(next.status)) {
          const delays = [1000, 2000, 4000, 8000, 10_000];
          timer = setTimeout(poll, delays[Math.min(attempts++, delays.length - 1)]);
        }
        else setPolling(false);
      } catch (error) {
        if (!live || activeController.signal.aborted) return;
        setMessage(errorMessage(error));
        const delays = [1000, 2000, 4000, 8000, 10_000];
        timer = setTimeout(poll, delays[Math.min(attempts++, delays.length - 1)]);
      }
    };
    void poll();
    return () => {
      live = false;
      if (timer) clearTimeout(timer);
      activeController?.abort();
    };
  }, [acceptSnapshot, polling, sessionId]);

  function withFreshKey(): string { return createIdempotencyKey(); }

  async function submitPublish(version: number) {
    if (publishInFlight.current) return;
    publishInFlight.current = true;
    setBusy(true);
    setMessage('문서 저장 요청을 보내고 있습니다.');
    try {
      await publishSession(sessionId, version, withFreshKey(), selectedRecipients, notificationEnabled);
      setPolling(true);
      setMessage('문서 저장 중입니다. 저장 결과를 확인하고 있습니다.');
    } catch (error) {
      if (error instanceof PublishApiError && error.outcomeUnknown) {
        setMessage('Publish 요청 결과를 확인하고 있습니다. 같은 요청을 자동으로 다시 보내지 않습니다.');
        setPolling(true);
      } else {
        setMessage(errorMessage(error));
        try { const latest = await refreshStatus(); if (latest) setPolling(isPending(latest.status)); } catch { /* safe status message remains */ }
      }
    } finally {
      publishInFlight.current = false;
      setBusy(false);
    }
  }

  async function reconcileConfirmUncertainty(attempt: { version: number; key: string }) {
    try {
      const current = await getPublishSnapshot(sessionId);
      acceptSnapshot(current);
      if (current.status === 'CONFIRMED') {
        await submitPublish(current.version);
      } else if (current.status === 'REVIEW') {
        const confirmedVersion = await confirmReview(sessionId, attempt.version, attempt.key);
        await submitPublish(confirmedVersion);
      } else {
        setMessage('회의 상태가 변경되어 공유를 계속할 수 없습니다. 최신 상태를 확인해 주세요.');
      }
    } catch (error) {
      setMessage(errorMessage(error));
    }
  }

  async function submit() {
    if (busy || confirmInFlight.current || !providerReady || !snapshot || shareBlockedByDraft) return;
    if (snapshot.status !== 'REVIEW' || !snapshot.allowedActions.includes('CONFIRM')) return;
    confirmInFlight.current = true;
    setBusy(true);
    setMessage('Review를 확인하고 있습니다.');
    setIssues([]);
    const attempt = confirmAttempt.current?.version === snapshot.version
      ? confirmAttempt.current : { version: snapshot.version, key: withFreshKey() };
    confirmAttempt.current = attempt;
    try {
      const version = await confirmReview(sessionId, snapshot.version, attempt.key);
      confirmAttempt.current = null;
      await submitPublish(version);
    } catch (error) {
      if (error instanceof PublishApiError && error.code === 'REVIEW_VALIDATION_FAILED') {
        setIssues(error.issues);
        setMessage('검토가 필요한 항목이 있습니다. 표시된 위치를 확인해 주세요.');
      } else if (error instanceof PublishApiError && error.outcomeUnknown) {
        setMessage('Review 확인 결과를 확인하고 있습니다.');
        await reconcileConfirmUncertainty(attempt);
      } else {
        setMessage(errorMessage(error));
        if (error instanceof PublishApiError && (error.status === 412 || error.status === 409)) {
          try { const latest = await refreshStatus(); if (latest) setSnapshot(latest); } catch { /* the existing safe message remains */ }
        }
      }
    } finally {
      confirmInFlight.current = false;
      setBusy(false);
    }
  }

  async function retryDocument() {
    if (busy || !retryAllowed || !snapshot) return;
    await submitPublish(snapshot.version);
  }

  async function retryConfirmedPublish() {
    if (busy || snapshot?.status !== 'CONFIRMED' || !snapshot.allowedActions.includes('PUBLISH')) return;
    await submitPublish(snapshot.version);
  }

  const providerSettingsState = { returnTo: location.pathname, returnState: settingsState };
  const canSubmit = providerReady && snapshot?.status === 'REVIEW' && snapshot.allowedActions.includes('CONFIRM') && !shareBlockedByDraft && !busy;

  return <main className="share-page">
    <header className="review-speaker-heading"><p className="eyebrow">SHARE</p><h1>회의록 공유</h1>
      <p>Review를 확인한 뒤 문서 저장과 공유를 시작합니다.</p></header>
    {config.status === 'loading' && <p role="status">문서 설정을 확인하고 있습니다.</p>}
    {config.status === 'error' && <section className="review-speaker-message" role="alert"><p>문서 설정을 확인하지 못했습니다.</p>
      <button className="button button-secondary" type="button" onClick={loadResources}>설정 다시 확인</button></section>}
    {config.status === 'ready' && !providerReady && <section className="review-speaker-message" role="status">
      <h2>문서 서비스를 연결해 주세요</h2><p>문서 저장을 위해 Settings에서 사용할 서비스를 설정해야 합니다.</p>
      <Link className="button button-primary" to="/settings" state={providerSettingsState}>문서 설정 열기</Link>
    </section>}
    {providerReady && <section className="share-provider-card" aria-label="문서 저장 설정">
      <h2>문서 저장</h2><p>{providerLabel(config.data?.document?.provider)}</p>
      <p>{config.data?.document?.rootUrl ?? '연결 준비 완료'}</p><p>문서 서비스가 준비되었습니다.</p>
    </section>}
    {participants.status === 'loading' && <p role="status">참석자 목록을 불러오는 중입니다.</p>}
    {participants.status === 'error' && <section role="alert"><p>참석자 목록을 불러오지 못했습니다.</p><button type="button" onClick={loadResources}>참석자 다시 불러오기</button></section>}
    {participants.status === 'ready' && <fieldset className="share-channel-card" disabled={busy}>
      <legend>Email 수신자</legend>
      {participants.data?.length ? participants.data.map((participant) => <label className="share-recipient" key={participant.id}>
        <input type="checkbox" checked={selectedRecipients.includes(participant.id)}
          onChange={(event) => setSelectedRecipients((current) => event.target.checked
            ? [...current, participant.id] : current.filter((id) => id !== participant.id))} />
        <span>{participant.name}<small>{participant.email}</small></span>
      </label>) : <p role="status">Email 수신자를 선택할 수 없습니다.</p>}
    </fieldset>}
    <fieldset className="share-channel-card" disabled={busy}><legend>Notification</legend>
      <label className="share-recipient"><input type="checkbox" aria-label="Slack 알림 사용" checked={notificationEnabled}
        onChange={(event) => setNotificationEnabled(event.target.checked)} /><span>Slack 알림 사용</span></label>
    </fieldset>
    {issues.length > 0 && <section className="review-speaker-message" role="alert" aria-live="assertive">
      <h2>Review 확인이 필요합니다</h2><ul>{issues.map((issue, index) => <li key={`${issue.path}-${index}`}>
        <Link to={{ pathname: reviewPath, hash: issueAnchor(issue, carriedState.review?.speakers) }} state={{ ...settingsState, validationIssues: issues }}>
          {issueLabel(issue, carriedState.review?.speakers)}
        </Link>
      </li>)}</ul>
    </section>}
    {snapshot?.status === 'REVIEW' && <p role="status">회의 정보를 확인한 뒤 공유를 시작할 수 있습니다.</p>}
    {snapshot?.status === 'CONFIRMED' && <p role="status">검토 확인이 완료되었습니다. 저장하고 공유를 계속할 수 있습니다.</p>}
    {snapshot?.status === 'PUBLISHING' && !documentSaved && <p role="status" aria-live="polite">문서 저장 중입니다. 완료 여부를 확인하고 있습니다.</p>}
    {documentSaved && <section className="share-result-card" aria-label="문서 저장 결과">
      <h2>문서 저장 완료</h2>
      {documentUrl ? <a href={documentUrl} target="_blank" rel="noopener noreferrer">저장된 회의록 열기<span className="visually-hidden"> (새 탭)</span></a>
        : <p role="status">문서 저장은 완료되었습니다. 문서 링크를 확인할 수 없어 상태를 새로고침해 주세요.</p>}
      {['DELIVERING', 'DOCUMENT_SAVED'].includes(snapshot?.status ?? '') && <p role="status">선택한 공유 채널의 전달 상태를 확인하고 있습니다.</p>}
      {(snapshot?.status === 'COMPLETED' || snapshot?.status === 'COMPLETED_WITH_WARNINGS') && <p role="status">문서 저장이 완료되었습니다. 채널별 결과는 상태 확인에서 갱신됩니다.</p>}
    </section>}
    {snapshot?.status === 'DOCUMENT_FAILED' && <section className="review-speaker-message" role="alert">
      <h2>문서를 저장하지 못했습니다</h2><p>문서 설정을 확인한 뒤 최신 상태에서 다시 시도해 주세요.</p>
      {retryAllowed ? <button className="button button-primary" type="button" disabled={busy} onClick={() => void retryDocument()}>문서 저장 다시 시도</button>
        : <p role="status">현재 회의 상태에서는 문서 저장을 다시 요청할 수 없습니다.</p>}
    </section>}
    {providerError && <p role="status">문서 설정을 다시 불러와 주세요.</p>}
    {message && <p role="status" aria-live="polite">{message}</p>}
    {snapshot?.status === 'CONFIRMED' && snapshot.allowedActions.includes('PUBLISH') && <section className="review-speaker-message" role="status">
      <p>Review 확인이 완료되었습니다. Publish 요청 결과를 API-010에서 확인했습니다.</p>
      <button className="button button-primary" type="button" disabled={busy} onClick={() => void retryConfirmedPublish()}>문서 저장을 시도</button>
    </section>}
    {shareBlockedByDraft && <section className="review-speaker-message" role="alert" aria-live="assertive">
      <p>화자 매핑 또는 회의록을 저장한 뒤 공유하세요.</p>
      <Link className="button button-secondary" to={reviewPath} state={settingsState}>Review에서 변경사항 저장하기</Link>
    </section>}
    <div className="share-actions">
      <button className="button button-primary" type="button" disabled={!canSubmit} onClick={() => void submit()}>
        {busy ? '처리 중…' : '저장하고 공유'}
      </button>
      <button className="button button-secondary" type="button" disabled={busy} onClick={() => {
        void refreshStatus().then((latest) => { if (latest) setPolling(isPending(latest.status)); })
          .catch((error) => setMessage(errorMessage(error)));
      }}>회의 상태 새로고침</button>
      <Link className="button button-secondary" to={reviewPath} state={settingsState}>Review로 돌아가기</Link>
    </div>
  </main>;
}

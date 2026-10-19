import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router';
import { listParticipants, type Participant } from './participantsApi';
import { getDocumentAppConfig } from './newMeetingApi';
import { createIdempotencyKey, getPublishSnapshot, PublishApiError, publishSession, retryDelivery, type PublishDelivery, type PublishSnapshot } from './publishApi';
import { AsyncState } from './components/async-state/AsyncState';
import { normalizeApiError } from './lib/api/normalizeApiError';

function statusLabel(status: string): string {
  if (status === 'SENT') return '전달 완료';
  if (status === 'PENDING' || status === 'SENDING') return '전달 진행 중';
  return '전달 실패';
}

function documentIsSaved(snapshot: PublishSnapshot): boolean {
  return Boolean(snapshot.document)
    || ['DOCUMENT_SAVED', 'DELIVERING', 'COMPLETED', 'COMPLETED_WITH_WARNINGS'].includes(snapshot.status);
}

type StepState = 'complete' | 'current' | 'waiting' | 'failed';

function stepStateLabel(state: StepState): string {
  if (state === 'complete') return '완료';
  if (state === 'current') return '진행 중';
  if (state === 'failed') return '실패';
  return '대기';
}

function deliveryStepState(rows: PublishDelivery[]): StepState {
  if (rows.some(({ status }) => status === 'FAILED')) return 'failed';
  if (rows.length > 0 && rows.every(({ status }) => status === 'SENT')) return 'complete';
  if (rows.some(({ status }) => status === 'PENDING' || status === 'SENDING')) return 'current';
  return 'waiting';
}

function CompletePageHeader() {
  return <header className="complete-page-heading"><h1>
    <svg className="workflow-heading-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 3.5h9l5 5v12H5zM14 3.5v5h5M8 13h8M8 17h5" /></svg>
    회의 결과
  </h1></header>;
}

export function CompletePage() {
  const { sessionId = '' } = useParams();
  const [snapshot, setSnapshot] = useState<PublishSnapshot | null>(null);
  const [participants, setParticipants] = useState<Participant[]>([]);
  const [loadedSessionId, setLoadedSessionId] = useState('');
  const [reloadLoading, setReloadLoading] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [connectionCheck, setConnectionCheck] = useState<{ sessionId: string; state: 'checking' | 'failed' | 'unknown' | 'complete' } | null>(null);
  const [busyRows, setBusyRows] = useState<Set<string>>(() => new Set());
  const busyRef = useRef(new Set<string>());
  const retryKeysRef = useRef(new Map<string, { key: string; accepted: boolean }>());
  const snapshotRef = useRef<PublishSnapshot | null>(null);
  const requestSequence = useRef(0);
  const activeSessionIdRef = useRef(sessionId);
  const autoPublishAttempts = useRef(new Set<string>());
  const manualPublishInFlight = useRef(false);
  const [publishing, setPublishing] = useState(false);

  const applySnapshot = useCallback((next: PublishSnapshot) => {
    if (next.sessionId !== activeSessionIdRef.current) return snapshotRef.current;
    const current = snapshotRef.current;
    if (current?.sessionId === next.sessionId && next.version < current.version) return current;
    if (current?.sessionId !== next.sessionId) {
      snapshotRef.current = null;
      busyRef.current.clear();
      retryKeysRef.current.clear();
      setBusyRows(new Set());
      setConnectionCheck(null);
    }
    snapshotRef.current = next;
    for (const delivery of next.deliveries ?? []) {
      const retryAttempt = delivery.deliveryId ? retryKeysRef.current.get(delivery.deliveryId) : undefined;
      if (delivery.deliveryId && retryAttempt?.accepted && !busyRef.current.has(delivery.deliveryId)
        && (delivery.status === 'SENT' || delivery.status === 'FAILED')) retryKeysRef.current.delete(delivery.deliveryId);
    }
    setSnapshot(next);
    return next;
  }, []);

  const refresh = useCallback(async () => {
    const sequence = ++requestSequence.current;
    const next = await getPublishSnapshot(sessionId);
    if (sequence !== requestSequence.current || sessionId !== activeSessionIdRef.current || next.sessionId !== sessionId) return snapshotRef.current;
    return applySnapshot(next);
  }, [applySnapshot, sessionId]);

  useEffect(() => {
    let active = true;
    requestSequence.current += 1;
    activeSessionIdRef.current = sessionId;
    snapshotRef.current = null;
    busyRef.current.clear();
    retryKeysRef.current.clear();
    Promise.allSettled([getPublishSnapshot(sessionId), listParticipants()]).then(([snapshotResult, participantResult]) => {
      if (!active) return;
      if (snapshotResult.status === 'fulfilled') applySnapshot(snapshotResult.value);
      else setError(snapshotResult.reason instanceof PublishApiError && snapshotResult.reason.status === 404
        ? '회의를 찾을 수 없습니다.' : '회의 결과를 불러오지 못했습니다. 다시 시도해 주세요.');
      if (participantResult.status === 'fulfilled') setParticipants(participantResult.value);
      else setParticipants([]);
      setLoadedSessionId(sessionId);
    });
    return () => { active = false; };
  }, [applySnapshot, sessionId]);

  const viewSnapshot = snapshot?.sessionId === sessionId ? snapshot : null;
  const hasPending = Boolean(viewSnapshot?.deliveries?.some(({ status }) => status === 'PENDING' || status === 'SENDING'));
  const loading = loadedSessionId !== sessionId || reloadLoading;
  useEffect(() => {
    if (!hasPending && busyRows.size === 0) return;
    const timer = window.setInterval(() => {
      void refresh().catch(() => setMessage('상태를 새로 확인하지 못했습니다. 다시 시도해 주세요.'));
    }, 1500);
    return () => window.clearInterval(timer);
  }, [busyRows.size, hasPending, refresh]);

  useEffect(() => {
    if (!viewSnapshot || viewSnapshot.status !== 'CONFIRMED' || !viewSnapshot.allowedActions.includes('PUBLISH')) return;
    const attemptId = `${sessionId}:${viewSnapshot.version}`;
    if (autoPublishAttempts.current.has(attemptId)) return;
    autoPublishAttempts.current.add(attemptId);
    let active = true;
    setConnectionCheck({ sessionId, state: 'checking' });
    setMessage('연결된 문서와 회의 참석자에게 결과를 전달하고 있습니다.');
    void getDocumentAppConfig().then((config) => publishSession(sessionId, viewSnapshot.version, createIdempotencyKey(),
      viewSnapshot.meeting?.participantIds ?? [], config.notification?.configured === true))
      .then(() => { if (active) { setConnectionCheck({ sessionId, state: 'complete' }); void refresh(); } })
      .catch(async (requestError) => {
        if (!active) return;
        if (requestError instanceof PublishApiError && requestError.outcomeUnknown) {
          setConnectionCheck({ sessionId, state: 'unknown' });
          setMessage('전달 요청 결과를 확인하고 있습니다.');
          try {
            const latest = await refresh();
            if (latest && latest.status !== 'CONFIRMED') setConnectionCheck({ sessionId, state: 'complete' });
          } catch { /* keep the connection step in an unknown state */ }
        } else {
          setConnectionCheck({ sessionId, state: 'failed' });
          setMessage('자동 전달을 시작하지 못했습니다. 회의 상태를 새로고침해 주세요.');
        }
      });
    return () => { active = false; };
  }, [refresh, sessionId, viewSnapshot]);

  async function retry(delivery: PublishDelivery) {
    const id = delivery.deliveryId;
    if (!id || delivery.status !== 'FAILED' || delivery.retryable !== true || busyRef.current.has(id)) return;
    const attempt = retryKeysRef.current.get(id) ?? { key: createIdempotencyKey(), accepted: false };
    retryKeysRef.current.set(id, attempt);
    const key = attempt.key;
    busyRef.current.add(id);
    setBusyRows(new Set(busyRef.current));
    setMessage(`${rowLabel(delivery, participants)} 재시도를 접수하고 있습니다.`);
    try {
      try {
        await retryDelivery(sessionId, id, key);
        attempt.accepted = true;
      } catch (requestError) {
        if (!(requestError instanceof PublishApiError) || !requestError.outcomeUnknown) throw requestError;
        const latest = await refresh();
        const current = latest?.deliveries?.find((row) => row.deliveryId === id);
        if (current?.status === 'PENDING' || current?.status === 'SENDING' || current?.status === 'SENT') {
          attempt.accepted = true;
          if (current.status === 'SENT') retryKeysRef.current.delete(id);
          setMessage('최신 전달 상태를 확인했습니다.');
          return;
        }
        if (!current || current.status !== 'FAILED' || current.retryable !== true) {
          setMessage('최신 전달 상태를 확인했습니다.');
          return;
        }
        // Replay this click with the exact same key; a fresh key is only created by a later user click.
        await retryDelivery(sessionId, id, key);
        attempt.accepted = true;
      }
      setMessage(`${rowLabel(delivery, participants)} 재시도가 접수되었습니다.`);
      await refresh();
    } catch (requestError) {
      const conflict = requestError instanceof PublishApiError && (requestError.status === 409 || requestError.status === 404);
      try {
        const latest = await refresh();
        const current = latest?.deliveries?.find((row) => row.deliveryId === id);
        if (!attempt.accepted && conflict && current && (current.status === 'SENT' || current.status === 'FAILED')) retryKeysRef.current.delete(id);
      } catch { /* retain the last authoritative snapshot and this click's idempotency key */ }
      setMessage(conflict
        ? '최신 상태를 확인했습니다. 재시도 가능 여부를 확인해 주세요.'
        : '재시도 결과를 확인할 수 없습니다. 최신 상태를 새로고침해 주세요.');
    } finally {
      busyRef.current.delete(id);
      const latestStatus = snapshotRef.current?.deliveries?.find((row) => row.deliveryId === id)?.status;
      if (attempt.accepted && (latestStatus === 'SENT' || latestStatus === 'FAILED')) retryKeysRef.current.delete(id);
      setBusyRows(new Set(busyRef.current));
    }
  }

  async function retryPublish() {
    const current = snapshotRef.current;
    if (!current || !current.allowedActions.includes('PUBLISH') || manualPublishInFlight.current) return;
    manualPublishInFlight.current = true;
    setPublishing(true);
    setError('');
    setConnectionCheck({ sessionId, state: 'checking' });
    setMessage('문서와 참석자 전달을 다시 시작하고 있습니다.');
    try {
      const config = await getDocumentAppConfig();
      await publishSession(sessionId, current.version, createIdempotencyKey(), current.meeting?.participantIds ?? [],
        config.notification?.configured === true);
      setConnectionCheck({ sessionId, state: 'complete' });
      setMessage('다시 전달을 시작했습니다. 최신 상태를 확인하고 있습니다.');
      await refresh();
    } catch (requestError) {
      if (requestError instanceof PublishApiError && requestError.outcomeUnknown) {
        setConnectionCheck({ sessionId, state: 'unknown' });
        setMessage('전달 요청 결과를 확인하고 있습니다.');
        try {
          const latest = await refresh();
          if (latest && latest.status !== 'CONFIRMED') setConnectionCheck({ sessionId, state: 'complete' });
        } catch { /* keep the connection step in an unknown state */ }
      } else {
        setConnectionCheck({ sessionId, state: 'failed' });
        setError('다시 전달을 시작하지 못했습니다. 연결 상태와 회의 상태를 확인해 주세요.');
      }
    } finally {
      manualPublishInFlight.current = false;
      setPublishing(false);
    }
  }

  if (loading) return <main className="share-page complete-page"><CompletePageHeader/><AsyncState state={{ status: 'loading', label: '회의 결과를 불러오는 중…', fullPage: true }} renderReady={() => null} /></main>;
  if (!viewSnapshot) return <main className="share-page complete-page"><CompletePageHeader/><AsyncState state={{
    status: 'error',
    title: '회의 결과를 불러오지 못했습니다.',
    error: normalizeApiError(error ? { error: { code: 'REQUEST_FAILED' } } : undefined),
    description: error || '회의 결과를 불러오지 못했습니다. 다시 시도해 주세요.',
    onRetry: () => { setReloadLoading(true); setError(''); void refresh().catch(() => setError('회의 결과를 불러오지 못했습니다. 다시 시도해 주세요.')).finally(() => setReloadLoading(false)); },
    retryLabel: '다시 시도',
  }} renderReady={() => null} /></main>;

  const people = new Map(participants.map((participant) => [participant.id, participant]));
  const emailRows = (viewSnapshot.deliveries ?? []).filter((delivery) => delivery.channel === 'EMAIL');
  const notificationRows = (viewSnapshot.deliveries ?? []).filter((delivery) => delivery.channel === 'NOTIFICATION');
  const documentSaved = documentIsSaved(viewSnapshot);
  const canRetryPublish = viewSnapshot.allowedActions.includes('PUBLISH')
    && (viewSnapshot.status === 'DOCUMENT_FAILED'
      || (viewSnapshot.status === 'CONFIRMED' && message === '자동 전달을 시작하지 못했습니다. 회의 상태를 새로고침해 주세요.'));
  const currentConnectionState = connectionCheck?.sessionId === sessionId ? connectionCheck.state : null;
  const connectionState: StepState = viewSnapshot.status !== 'CONFIRMED'
    ? 'complete'
    : currentConnectionState === 'checking' ? 'current'
      : currentConnectionState === 'failed' ? 'failed'
        : currentConnectionState === 'unknown' ? 'current' : 'waiting';
  const documentState: StepState = viewSnapshot.status === 'DOCUMENT_FAILED' ? 'failed'
    : documentSaved ? 'complete'
      : viewSnapshot.status === 'PUBLISHING' ? 'current' : 'waiting';
  const terminalState: StepState = !documentSaved ? 'waiting'
    : (emailRows.some(({ status }) => status === 'FAILED') || notificationRows.some(({ status }) => status === 'FAILED')) ? 'failed'
      : (emailRows.some(({ status }) => status === 'PENDING' || status === 'SENDING')
        || notificationRows.some(({ status }) => status === 'PENDING' || status === 'SENDING')) ? 'current' : 'complete';
  const steps: Array<{ title: string; state: StepState }> = [
    { title: '저장 요청', state: connectionState },
    { title: '회의록 저장', state: documentState },
    ...(documentSaved && emailRows.length > 0 ? [{ title: '이메일 전달', state: deliveryStepState(emailRows) }] : []),
    ...(documentSaved && notificationRows.length > 0 ? [{ title: 'Slack 전달', state: deliveryStepState(notificationRows) }] : []),
    ...(documentSaved ? [{ title: '완료', state: terminalState }] : []),
  ];
  const stepIcon = (state: StepState) => state === 'complete'
    ? <svg viewBox="0 0 20 20" aria-hidden="true"><path d="m4 10 4 4 8-8" /></svg>
    : state === 'failed' ? <svg viewBox="0 0 20 20" aria-hidden="true"><path d="m6 6 8 8m0-8-8 8" /></svg>
      : state === 'current' ? <span aria-hidden="true" /> : null;
  const rows = (items: PublishDelivery[], kind: 'email' | 'notification') => items.map((delivery, index) => {
    const key = delivery.deliveryId ?? `${kind}-${index}`;
    const recipient = delivery.recipientParticipantId ? people.get(delivery.recipientParticipantId) : undefined;
    const label = kind === 'notification' ? 'Slack' : recipient?.name || delivery.recipientParticipantId || '참석자';
    const retryName = `Retry ${kind === 'notification' ? 'Slack 알림' : label}`;
    const retryable = Boolean(delivery.deliveryId && delivery.status === 'FAILED' && delivery.retryable === true);
    return <li key={key} className="complete-delivery-row">
      <div><strong>{label}: {statusLabel(delivery.status)}</strong></div>
      {retryable && <button type="button" className="button button-secondary" aria-label={retryName} disabled={busyRows.has(delivery.deliveryId!)} onClick={() => void retry(delivery)}>
        {busyRows.has(delivery.deliveryId!) ? '재시도 중…' : 'Retry'}
      </button>}
    </li>;
  });

  return <main className="share-page complete-page">
    <CompletePageHeader/>
    <section className="processing-card complete-publish-card" aria-label="회의 결과 처리 단계" aria-live="polite">
      <ol className="processing-steps complete-publish-steps">{steps.map(({ title, state }, index) => <li key={`${title}-${index}`} className={`is-${state}`} aria-current={state === 'current' ? 'step' : undefined}>
        <span className="processing-step-icon" aria-hidden="true">{stepIcon(state)}</span>
        <span className="processing-step-label"><span>{title}</span><small className="complete-step-state">{stepStateLabel(state)}</small></span>
      </li>)}</ol>
    </section>
    {documentSaved && emailRows.length > 0 && <section className="share-result-card" aria-label="Email 전달 결과" aria-live="polite"><h2>Email</h2><ul>{rows(emailRows, 'email')}</ul></section>}
    {documentSaved && notificationRows.length > 0 && <section className="share-result-card" aria-label="Slack 알림 결과" aria-live="polite"><h2>Slack 알림</h2><ul>{rows(notificationRows, 'notification')}</ul></section>}
    {error && <p role="alert">{error}</p>}
    <div className="share-actions">
      {canRetryPublish && <button type="button" className="button button-primary" disabled={publishing} onClick={() => void retryPublish()}>
        {publishing ? '저장 및 전달 중…' : viewSnapshot.status === 'DOCUMENT_FAILED' ? '문서 저장 다시 시도' : '저장 및 전달 다시 시도'}
      </button>}
      <button type="button" className="button button-secondary" onClick={() => { setError(''); void refresh().then(() => setMessage('상태를 새로 확인했습니다.')).catch(() => setError('최신 상태를 불러오지 못했습니다. 다시 시도해 주세요.')); }}>회의 상태 새로고침</button>
    </div>
  </main>;
}

function rowLabel(delivery: PublishDelivery, participants: Participant[]): string {
  if (delivery.channel === 'NOTIFICATION') return 'Slack 알림';
  return participants.find(({ id }) => id === delivery.recipientParticipantId)?.name || delivery.recipientParticipantId || '참석자';
}

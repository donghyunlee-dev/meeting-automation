import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import { listParticipants, type Participant } from './participantsApi';
import { createIdempotencyKey, getPublishSnapshot, PublishApiError, retryDelivery, type PublishDelivery, type PublishSnapshot } from './publishApi';

function safeDocumentUrl(value: string | null | undefined): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : null;
  } catch { return null; }
}

function statusLabel(status: string): string {
  if (status === 'SENT') return '전송 요청 수락';
  if (status === 'PENDING' || status === 'SENDING') return '전달 진행 중';
  return '전달 실패';
}

function summaryLabel(snapshot: PublishSnapshot): string {
  const deliveries = snapshot.deliveries ?? [];
  if (deliveries.some(({ status }) => status === 'PENDING' || status === 'SENDING')) return '전달 진행 중';
  if (!deliveries.length) return snapshot.document ? '문서 저장 완료' : '문서 결과 확인 불가';
  const failed = deliveries.some(({ status }) => status === 'FAILED');
  if (deliveries.every(({ status }) => status === 'SENT')) return '완료';
  if (failed && deliveries.every(({ status }) => status === 'FAILED')) return '전달 실패';
  if (failed) return '일부 전달 실패';
  return '전달 결과 확인 중';
}

function safeErrorMessage(delivery: PublishDelivery): string {
  if (delivery.retryable === false) return '중복 전달을 막기 위해 자동 재전송하지 않습니다. 목적지에서 도착 여부를 확인해 주세요.';
  if (delivery.errorCode === 'DELIVERY_NOT_RETRYABLE') return '현재 상태에서는 다시 보낼 수 없습니다. 최신 상태를 확인해 주세요.';
  return '전달하지 못했습니다. 잠시 후 다시 확인해 주세요.';
}

export function CompletePage() {
  const { sessionId = '' } = useParams();
  const [snapshot, setSnapshot] = useState<PublishSnapshot | null>(null);
  const [participants, setParticipants] = useState<Participant[]>([]);
  const [loadedSessionId, setLoadedSessionId] = useState('');
  const [reloadLoading, setReloadLoading] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [busyRows, setBusyRows] = useState<Set<string>>(() => new Set());
  const busyRef = useRef(new Set<string>());
  const retryKeysRef = useRef(new Map<string, { key: string; accepted: boolean }>());
  const snapshotRef = useRef<PublishSnapshot | null>(null);
  const requestSequence = useRef(0);

  const applySnapshot = useCallback((next: PublishSnapshot) => {
    const current = snapshotRef.current;
    if (current && next.version < current.version) return current;
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
    if (sequence !== requestSequence.current) return snapshotRef.current;
    return applySnapshot(next);
  }, [applySnapshot, sessionId]);

  useEffect(() => {
    let active = true;
    Promise.allSettled([getPublishSnapshot(sessionId), listParticipants()]).then(([snapshotResult, participantResult]) => {
      if (!active) return;
      if (snapshotResult.status === 'fulfilled') applySnapshot(snapshotResult.value);
      else setError(snapshotResult.reason instanceof PublishApiError && snapshotResult.reason.status === 404
        ? '회의를 찾을 수 없습니다.' : '회의 결과를 불러오지 못했습니다. 다시 시도해 주세요.');
      if (participantResult.status === 'fulfilled') setParticipants(participantResult.value);
      setLoadedSessionId(sessionId);
    });
    return () => { active = false; };
  }, [applySnapshot, sessionId]);

  const hasPending = Boolean(snapshot?.deliveries?.some(({ status }) => status === 'PENDING' || status === 'SENDING'));
  const loading = loadedSessionId !== sessionId || reloadLoading;
  useEffect(() => {
    if (!hasPending && busyRows.size === 0) return;
    const timer = window.setInterval(() => {
      void refresh().catch(() => setMessage('상태를 새로 확인하지 못했습니다. 다시 시도해 주세요.'));
    }, 1500);
    return () => window.clearInterval(timer);
  }, [busyRows.size, hasPending, refresh]);

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

  if (loading) return <main className="share-page"><p role="status">회의 결과를 불러오는 중…</p></main>;
  if (!snapshot) return <main className="share-page"><h1>회의 결과</h1><p role="alert">{error || '회의 결과를 불러오지 못했습니다.'}</p><button type="button" className="button button-secondary" onClick={() => { setReloadLoading(true); setError(''); void refresh().catch(() => setError('회의 결과를 불러오지 못했습니다. 다시 시도해 주세요.')).finally(() => setReloadLoading(false)); }}>다시 시도</button></main>;

  const people = new Map(participants.map((participant) => [participant.id, participant]));
  const emailRows = (snapshot.deliveries ?? []).filter((delivery) => delivery.channel === 'EMAIL');
  const notificationRows = (snapshot.deliveries ?? []).filter((delivery) => delivery.channel === 'NOTIFICATION');
  const documentUrl = safeDocumentUrl(snapshot.document?.documentUrl);
  const rows = (items: PublishDelivery[], kind: 'email' | 'notification') => items.map((delivery, index) => {
    const key = delivery.deliveryId ?? `${kind}-${index}`;
    const recipient = delivery.recipientParticipantId ? people.get(delivery.recipientParticipantId) : undefined;
    const label = kind === 'notification' ? 'Slack' : recipient?.name || delivery.recipientParticipantId || '참석자';
    const retryName = `Retry ${kind === 'notification' ? 'Slack 알림' : label}`;
    const retryable = Boolean(delivery.deliveryId && delivery.status === 'FAILED' && delivery.retryable === true);
    return <li key={key} className="complete-delivery-row">
      <div><strong>{label}: {statusLabel(delivery.status)}</strong>
        {typeof delivery.attemptCount === 'number' && <span> (시도 {delivery.attemptCount}회)</span>}
        {delivery.status === 'FAILED' && <p>{safeErrorMessage(delivery)}</p>}
      </div>
      {retryable && <button type="button" className="button button-secondary" aria-label={retryName} disabled={busyRows.has(delivery.deliveryId!)} onClick={() => void retry(delivery)}>
        {busyRows.has(delivery.deliveryId!) ? '재시도 중…' : 'Retry'}
      </button>}
    </li>;
  });

  return <main className="share-page complete-page">
    <header className="topbar"><Link className="brand" to="/" aria-label="Meeting Automation 홈">M</Link><div><p className="eyebrow">MEETING AUTOMATION</p><h1>회의 결과</h1></div></header>
    <section className="share-result-card" aria-labelledby="complete-summary" aria-live="polite">
      <h2 id="complete-summary">{summaryLabel(snapshot)}</h2>
      <p>{snapshot.document ? '문서 저장 완료' : '문서 저장 결과를 확인할 수 없습니다.'}</p>
      {documentUrl ? <a href={documentUrl} target="_blank" rel="noopener noreferrer">저장된 회의록 열기<span className="visually-hidden"> (새 탭)</span></a>
        : <p>문서 링크를 확인할 수 없습니다.</p>}
    </section>
    {emailRows.length > 0 && <section className="share-result-card" aria-label="Email 전달 결과" aria-live="polite"><h2>Email</h2><ul>{rows(emailRows, 'email')}</ul></section>}
    {notificationRows.length > 0 && <section className="share-result-card" aria-label="Slack 알림 결과" aria-live="polite"><h2>Slack 알림</h2><ul>{rows(notificationRows, 'notification')}</ul></section>}
    {error && <p role="alert">{error}</p>}
    <p role="status" aria-live="polite">{message}</p>
    <div className="share-actions">
      <button type="button" className="button button-secondary" onClick={() => { setError(''); void refresh().then(() => setMessage('상태를 새로 확인했습니다.')).catch(() => setError('최신 상태를 불러오지 못했습니다. 다시 시도해 주세요.')); }}>회의 상태 새로고침</button>
      <Link className="button button-secondary" to={`/meetings/${encodeURIComponent(sessionId)}/share`}>공유 설정으로 돌아가기</Link>
    </div>
  </main>;
}

function rowLabel(delivery: PublishDelivery, participants: Participant[]): string {
  if (delivery.channel === 'NOTIFICATION') return 'Slack 알림';
  return participants.find(({ id }) => id === delivery.recipientParticipantId)?.name || delivery.recipientParticipantId || '참석자';
}

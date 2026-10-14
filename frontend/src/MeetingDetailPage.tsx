import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import { getMeetingDetail, MeetingDetailApiError, type MeetingDetail } from './meetingDetailApi';

function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '날짜 정보 없음' : new Intl.DateTimeFormat('ko-KR', { dateStyle: 'long', timeStyle: 'short' }).format(date);
}

function safeExternalUrl(value: string | null): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    return (url.protocol === 'http:' || url.protocol === 'https:') && url.hostname && !url.username && !url.password ? value : null;
  } catch {
    return null;
  }
}

function TranscriptDrawer({ detail, onClose }: { detail: MeetingDetail; onClose: () => void }) {
  const closeRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLElement>(null);
  useEffect(() => {
    closeRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
        return;
      }
      if (event.key !== 'Tab') return;
      const dialog = dialogRef.current;
      if (!dialog) return;
      const focusable = [...dialog.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      )];
      if (focusable.length === 0) {
        event.preventDefault();
        dialog.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (!dialog.contains(active)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      } else if (event.shiftKey && active === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    };
    const onFocusIn = (event: FocusEvent) => {
      const dialog = dialogRef.current;
      if (dialog && event.target instanceof Node && !dialog.contains(event.target)) closeRef.current?.focus();
    };
    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('focusin', onFocusIn, true);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('focusin', onFocusIn, true);
    };
  }, [onClose]);
  const speakerNames = new Map(detail.participants.map(({ id, name }) => [id, name]));
  return (
    <div className="transcript-drawer-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <section ref={dialogRef} className="transcript-drawer" role="dialog" aria-modal="true" aria-labelledby="transcript-title" tabIndex={-1}>
        <header className="transcript-drawer-header"><div><p className="eyebrow">회의 기록</p><h2 id="transcript-title">Transcript</h2></div><button ref={closeRef} className="icon-button" type="button" aria-label="Transcript 닫기" onClick={onClose}>×</button></header>
        <div className="transcript-drawer-list">
          {detail.transcript.length === 0 ? <p className="transcript-drawer-empty">Transcript가 없습니다.</p> : detail.transcript.map((segment) => (
            <article className="transcript-drawer-entry" key={segment.segmentId}>
              <div className="transcript-drawer-entry-heading"><strong>{speakerNames.get(segment.speakerId) ?? segment.speakerId}</strong><time>{new Intl.NumberFormat('ko-KR', { minimumIntegerDigits: 2 }).format(Math.floor(segment.startMs / 60000))}:{String(Math.floor(segment.startMs / 1000) % 60).padStart(2, '0')}</time></div>
              <p>{segment.text}</p>
            </article>
          ))}
        </div>
      </section>
    </div>
  );
}

export function MeetingDetailPage() {
  const { documentId = '' } = useParams();
  const [result, setResult] = useState<{ documentId: string; detail?: MeetingDetail; error?: MeetingDetailApiError; status: 'loading' | 'ready' | 'error' }>({ documentId: '', status: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const [showTranscript, setShowTranscript] = useState(false);
  const transcriptTrigger = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    let active = true;
    void getMeetingDetail(documentId).then((result) => {
      if (active) setResult({ documentId, detail: result, status: 'ready' });
    }).catch((reason: unknown) => {
      if (active) {
        setResult({ documentId, error: reason instanceof MeetingDetailApiError ? reason : new MeetingDetailApiError('MEETING_DETAIL_FAILED'), status: 'error' });
      }
    });
    return () => { active = false; };
  }, [attempt, documentId]);

  const closeTranscript = () => {
    setShowTranscript(false);
    requestAnimationFrame(() => transcriptTrigger.current?.focus());
  };
  const currentStatus = result.documentId === documentId ? result.status : 'loading';
  const detail = result.documentId === documentId ? result.detail ?? null : null;
  const error = result.documentId === documentId ? result.error ?? null : null;
  if (currentStatus === 'loading') return <main className="meeting-detail-page"><div className="state-card loading" role="status"><span className="spinner" aria-hidden="true" />회의 상세를 불러오는 중…</div></main>;
  if (error?.code === 'MEETING_NOT_FOUND') return <main className="meeting-detail-page"><section className="state-card empty-card"><h1>회의 기록을 찾을 수 없습니다.</h1><p>삭제되었거나 더 이상 접근할 수 없는 회의입니다.</p><Link className="button button-secondary" to="/meetings">회의록 목록으로</Link></section></main>;
  if (error || !detail) return <main className="meeting-detail-page"><section className="state-card error-card" role="alert"><p>회의 상세를 불러오지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.</p><button className="button button-secondary" type="button" onClick={() => { setResult({ documentId, status: 'loading' }); setAttempt((value) => value + 1); }}>다시 시도</button><Link to="/meetings">회의록 목록으로</Link></section></main>;

  const externalUrl = safeExternalUrl(detail.documentUrl);
  const participantNames = new Map(detail.participants.map(({ id, name }) => [id, name]));
  const minutes = detail.minutes;
  return (
    <main className="meeting-detail-page">
      <Link className="meeting-detail-back" to="/meetings">← 회의록 목록</Link>
      <header className="meeting-detail-heading"><p className="eyebrow">MEETING HISTORY</p><h1>{detail.title}</h1><p>{formatDate(detail.meetingAt)}</p><p>{detail.participants.length ? detail.participants.map(({ name }) => name).join(', ') : '참석자 정보 없음'}</p></header>
      <section className="meeting-detail-card" aria-labelledby="minutes-title"><h2 id="minutes-title">회의록</h2><section><h3>요약</h3><p>{minutes.summary || '요약이 없습니다.'}</p></section>
        {minutes.discussionPoints.length > 0 && <section><h3>논의 사항</h3><ul>{minutes.discussionPoints.map((item, index) => <li key={`${index}-${item}`}>{item}</li>)}</ul></section>}
        {minutes.decisions.length > 0 && <section><h3>결정 사항</h3><ul>{minutes.decisions.map((item, index) => <li key={`${index}-${item}`}>{item}</li>)}</ul></section>}
        {minutes.actionItems.length > 0 && <section><h3>실행 항목</h3><ul>{minutes.actionItems.map((item, index) => <li key={`${index}-${item.task}`}><strong>{item.task}</strong>{item.ownerParticipantId && <span> · 담당: {participantNames.get(item.ownerParticipantId) ?? '참석자'}</span>}{item.dueDate && <span> · 기한: {item.dueDate}</span>}</li>)}</ul></section>}
        {minutes.followUps.length > 0 && <section><h3>후속 조치</h3><ul>{minutes.followUps.map((item, index) => <li key={`${index}-${item}`}>{item}</li>)}</ul></section>}
      </section>
      <div className="meeting-detail-actions"><button ref={transcriptTrigger} className="button button-secondary" type="button" onClick={() => setShowTranscript(true)}>Transcript 보기</button>{externalUrl && <a className="button button-primary" href={externalUrl} target="_blank" rel="noopener noreferrer">Notion/Confluence에서 열기</a>}</div>
      {showTranscript && <TranscriptDrawer detail={detail} onClose={closeTranscript} />}
    </main>
  );
}

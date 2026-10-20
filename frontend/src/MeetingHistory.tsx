import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { AsyncState } from './components/async-state/AsyncState';
import { normalizeApiError, type SafeApiError } from './lib/api/normalizeApiError';
import { listMeetings, MeetingListApiError, type MeetingSummary } from './meetingListApi';
import { filterMeetings, hasInvalidDateRange, type MeetingFilters } from './meetingFilters';
import { getSetupSnapshot, type DocumentProvider, type SetupSnapshot } from './documentSetupApi';

type ListResult =
  | { status: 'loading' }
  | { status: 'ready'; data: MeetingSummary[]; loadedAttempt: number; warning?: SafeApiError }
  | { status: 'error'; error: SafeApiError };

function formatMeetingDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '날짜 정보 없음';
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(date);
}

function participantSummary(meeting: MeetingSummary, showNames: boolean): string {
  if (!showNames) return `참석자 ${meeting.participants.length}명`;
  const names = meeting.participants.slice(0, 3).map(({ name }) => name);
  if (meeting.participants.length > 3) names.push(`외 ${meeting.participants.length - 3}명`);
  return names.length ? names.join(', ') : '참석자 정보 없음';
}

function MeetingRows({ meetings, showParticipantNames, linkRows = false, separateParticipantLine = false }: { meetings: MeetingSummary[]; showParticipantNames: boolean; linkRows?: boolean; separateParticipantLine?: boolean }) {
  const navigate = useNavigate();
  return (
    <ul className="meeting-summary-list">
      {meetings.map((meeting) => (
        <li className="meeting-summary-row" key={meeting.documentId}>
          {linkRows ? <button className="meeting-summary-button" type="button" aria-label={`${meeting.title} 상세 보기`}
            onClick={() => navigate(`/meetings/${encodeURIComponent(meeting.documentId)}`)}>
            <span className="meeting-summary-copy"><h3>{meeting.title}</h3><span className="meeting-summary-meta"><time>{formatMeetingDate(meeting.meetingAt)}</time>{!separateParticipantLine && <><span aria-hidden="true">·</span><span>{participantSummary(meeting, showParticipantNames)}</span></>}</span>{separateParticipantLine && <span className="meeting-summary-participants">{participantSummary(meeting, showParticipantNames)}</span>}</span>
            <span className="meeting-summary-arrow" aria-hidden="true">›</span>
          </button> : <><h3>{meeting.title}</h3><p>{formatMeetingDate(meeting.meetingAt)}</p><p>{participantSummary(meeting, showParticipantNames)}</p></>}
        </li>
      ))}
    </ul>
  );
}

function MeetingList({ limit, emptyMessage, enableFilters = false, quietEmpty = false, linkRows = false, showRecentParticipantNames = false }: { limit?: number; emptyMessage: string; enableFilters?: boolean; quietEmpty?: boolean; linkRows?: boolean; showRecentParticipantNames?: boolean }) {
  const [result, setResult] = useState<ListResult>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const [filters, setFilters] = useState<MeetingFilters>({ query: '', from: '', to: '' });
  const [appliedFilters, setAppliedFilters] = useState<MeetingFilters>({ query: '', from: '', to: '' });
  const [filtersExpanded, setFiltersExpanded] = useState(false);
  const retry = () => setAttempt((value) => value + 1);

  useEffect(() => {
    let active = true;
    void listMeetings(limit).then((items) => {
      if (!active) return;
      setResult({ status: 'ready', data: items, loadedAttempt: attempt });
    }).catch((reason: unknown) => {
      if (!active) return;
      const safeError = reason instanceof MeetingListApiError ? reason.safe : normalizeApiError(null);
      setResult((current) => current.status === 'ready'
        ? { status: 'ready', data: current.data, loadedAttempt: attempt, warning: safeError }
        : { status: 'error', error: safeError });
    });
    return () => { active = false; };
  }, [limit, attempt]);

  const refreshing = result.status === 'ready' && result.loadedAttempt < attempt;

  const visibleMeetings = useMemo(() => result.status === 'ready'
    ? (enableFilters ? filterMeetings(result.data, appliedFilters) : result.data).slice(0, limit ?? 100)
    : [], [enableFilters, appliedFilters, limit, result]);

  if (result.status === 'loading') return <AsyncState state={{ status: 'loading', label: '회의 목록을 불러오는 중…', fullPage: enableFilters }} renderReady={() => null} />;
  if (result.status === 'error') return <AsyncState state={{ status: 'error', title: '회의 목록을 불러오지 못했습니다.', error: result.error, onRetry: retry }} renderReady={() => null} />;
  if (result.data.length === 0) return <AsyncState state={{
    status: 'empty', title: emptyMessage,
    visual: quietEmpty ? <img className="empty-meetings-icon" src="/assets/empty-meetings.svg" alt="회의 기록 없음" /> : undefined,
    visuallyHideTitle: quietEmpty,
  }} renderReady={() => null} />;
  if (!enableFilters) return <AsyncState state={{ status: 'ready', data: visibleMeetings, refreshing, warning: result.warning, onRetry: retry }} renderReady={(items) => <MeetingRows meetings={items} showParticipantNames={limit === undefined || showRecentParticipantNames} linkRows={linkRows} separateParticipantLine={showRecentParticipantNames} />} />;
  const invalidRange = hasInvalidDateRange(filters.from, filters.to);
  return (
    <AsyncState state={{ status: 'ready', data: visibleMeetings, refreshing, warning: result.warning, onRetry: retry }} renderReady={(items) => <>
      <section className="meeting-filter-shell" aria-label="회의 검색 조건">
        <form className="meeting-filters" aria-label="회의 필터" onSubmit={(event) => { event.preventDefault(); setAppliedFilters(filters); }}>
          <label className="meeting-search-field">회의 검색<span className="meeting-search-control">
            <input type="search" aria-label="회의 검색" value={filters.query}
              onChange={(event) => setFilters((current) => ({ ...current, query: event.target.value }))}
              onBlur={() => setAppliedFilters((current) => ({ ...current, query: filters.query }))}
              placeholder="회의명 또는 참석자" />
            <button className="meeting-search-clear" type="button" aria-label="검색어 초기화" title="검색어 초기화" disabled={!filters.query}
              onClick={() => { setFilters((current) => ({ ...current, query: '' })); setAppliedFilters((current) => ({ ...current, query: '' })); }}>
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="m6 6 8 8M14 6l-8 8" /></svg>
            </button>
          </span></label>
          <div className="meeting-filter-details-row">
            <button className="meeting-filter-toggle" type="button" aria-expanded={filtersExpanded}
              onClick={() => setFiltersExpanded((expanded) => !expanded)}>
              상세 조건<svg viewBox="0 0 20 20" aria-hidden="true"><path d={filtersExpanded ? 'm5 12 5-5 5 5' : 'm5 8 5 5 5-5'} /></svg>
            </button>
          </div>
          {filtersExpanded && <fieldset className="meeting-date-range" aria-describedby={invalidRange ? 'meeting-date-error' : undefined}>
            <legend>검색 기간</legend>
            <label><span>시작일</span><input type="date" aria-label="시작일" value={filters.from}
              onChange={(event) => { const from = event.target.value; setFilters((current) => ({ ...current, from })); setAppliedFilters((current) => ({ ...current, from })); }} aria-invalid={invalidRange} /></label>
            <span className="meeting-date-separator" aria-hidden="true">~</span>
            <label><span>종료일</span><input type="date" aria-label="종료일" value={filters.to}
              onChange={(event) => { const to = event.target.value; setFilters((current) => ({ ...current, to })); setAppliedFilters((current) => ({ ...current, to })); }} aria-invalid={invalidRange} /></label>
          </fieldset>}
          {invalidRange && <p id="meeting-date-error" className="field-error" role="alert">시작일은 종료일보다 늦을 수 없습니다. 날짜 범위는 적용하지 않았습니다.</p>}
        </form>
      </section>
      <p className="meeting-result-count">총 {items.length}건</p>
      {items.length === 0 ? (
        <div className="state-card empty-card"><p>검색 결과가 없습니다.</p></div>
      ) : <MeetingRows meetings={items} showParticipantNames linkRows />}
    </>} />
  );
}

export function HomePage() {
  const navigate = useNavigate();
  const [connection, setConnection] = useState<{ status: 'loading' | 'error' | 'ready'; snapshot?: SetupSnapshot }>({ status: 'loading' });
  const [connectionDialogOpen, setConnectionDialogOpen] = useState(false);
  const connectionButtonRef = useRef<HTMLButtonElement | null>(null);
  const dialogCloseRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    let live = true;
    void getSetupSnapshot().then(({ snapshot }) => {
      if (live) setConnection({ status: 'ready', snapshot });
    }).catch(() => {
      if (live) setConnection({ status: 'error' });
    });
    return () => { live = false; };
  }, []);

  useEffect(() => {
    if (!connectionDialogOpen) return;
    dialogCloseRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setConnectionDialogOpen(false);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      connectionButtonRef.current?.focus();
    };
  }, [connectionDialogOpen]);

  const active = connection.snapshot?.active ?? null;
  const providerLabel = (provider: DocumentProvider) => provider === 'NOTION' ? 'Notion' : 'Confluence';
  const connectionReady = connection.snapshot?.status === 'READY' && connection.snapshot.canCreateMeeting;
  const connectionLabel = connection.status === 'loading' ? '연결 상태 확인 중' : connection.status === 'error'
    ? '연결 상태 확인 필요' : active
      ? connection.snapshot?.status === 'SWITCHING' ? `${providerLabel(active.provider)} 전환 중`
        : connection.snapshot?.status === 'ATTENTION_REQUIRED' ? '연결 점검 필요'
          : connectionReady ? `${providerLabel(active.provider)} 연결됨` : '문서 설정 확인 필요'
      : '문서 서비스 연결 필요';

  return (
    <main className="home-page">
      <div className="home-content">
        <div className="home-toolbar">
          <button ref={connectionButtonRef} className={`home-connection-button${connectionReady ? ' is-connected' : ''}`} type="button"
            aria-label={`문서 서비스 연결 상태: ${connectionLabel}`} aria-haspopup="dialog" onClick={() => setConnectionDialogOpen(true)}>
            <span className="home-connection-indicator" aria-hidden="true" />
            {active ? <span className={`home-provider-icon home-provider-icon-${active.provider.toLowerCase()}`}><img src={active.provider === 'NOTION' ? '/assets/notion.ico' : '/assets/confluence.svg'} alt="" /></span>
              : <span className="home-provider-placeholder" aria-hidden="true">▤</span>}
          </button>
        </div>
        <div className="home-message">
          <h2>회의를 시작할까요?</h2>
        </div>
        <button className="button button-primary home-cta" type="button" onClick={() => navigate('/meetings/new')}>새 회의 시작</button>
        <section className="home-recent" aria-labelledby="recent-meetings-title">
          <div className="home-recent-heading">
            <h2 id="recent-meetings-title">최근 회의</h2>
            <button className="home-all-meetings" type="button" onClick={() => navigate('/meetings')}>전체 보기 <svg viewBox="0 0 16 16" aria-hidden="true"><path d="m5.5 3 5 5-5 5" /></svg></button>
          </div>
          <MeetingList limit={3} emptyMessage="최근 회의가 없습니다. 새 회의를 시작해 보세요." quietEmpty linkRows showRecentParticipantNames />
        </section>
      </div>
      {connectionDialogOpen && <div className="home-dialog-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setConnectionDialogOpen(false); }}>
        <section className="home-connection-dialog" role="dialog" aria-modal="true" aria-labelledby="home-connection-dialog-title">
          <header><div><h2 id="home-connection-dialog-title">문서 연결 상태</h2></div>
            <button ref={dialogCloseRef} className="home-dialog-close" type="button" aria-label="닫기" onClick={() => setConnectionDialogOpen(false)}>×</button></header>
          {connection.status === 'loading' ? <p role="status">문서 연결 상태를 불러오는 중…</p>
            : connection.status === 'error' ? <div className="home-dialog-status" role="alert"><strong>연결 상태를 확인할 수 없습니다</strong><p>백엔드 연결을 확인한 뒤 다시 시도해 주세요.</p>
              <button className="button button-secondary" type="button" onClick={() => { setConnection({ status: 'loading' }); void getSetupSnapshot().then(({ snapshot }) => setConnection({ status: 'ready', snapshot })).catch(() => setConnection({ status: 'error' })); }}>다시 확인</button></div>
              : active ? <>
                <div className="home-dialog-provider"><span className={`home-provider-icon home-provider-icon-${active.provider.toLowerCase()}`}><img src={active.provider === 'NOTION' ? '/assets/notion.ico' : '/assets/confluence.svg'} alt="" /></span>
                  <span><strong>{providerLabel(active.provider)}</strong><small>{connection.snapshot?.status === 'SWITCHING' ? '새 문서 서비스로 전환 중' : connectionReady ? `연결됨 · 버전 ${active.connectionVersion}` : '연결 상태를 확인해 주세요'}</small></span>
                  {connectionReady && <span className="home-connected-check" aria-label="연결됨">✓</span>}</div>
                <div className="home-document-structure"><h3>문서 구조</h3><ul><li><strong>Meeting Automation</strong><ul><li>Meetings <span>회의록</span></li><li>Participants <span>참석자</span></li><li>Templates <span>기본 회의록 템플릿</span></li></ul></li></ul></div>
                <div className="home-dialog-actions"><button className="button button-primary" type="button" onClick={() => navigate('/setup/document')}>문서 연결 변경</button></div>
              </> : <div className="home-dialog-status"><strong>연결된 문서 서비스가 없습니다</strong><p>회의록을 저장하려면 문서 서비스를 연결해 주세요.</p>
                <button className="button button-primary" type="button" onClick={() => navigate('/setup/document')}>문서 서비스 연결</button></div>}
        </section>
      </div>}
    </main>
  );
}

export function MeetingsPage() {
  const navigate = useNavigate();
  return (
    <main className="meetings-page page-scroll-layout">
      <header className="meetings-heading">
        <h1><svg className="meetings-heading-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
          <path d="M9 6h11M9 12h11M9 18h11" />
          <path d="M4 6h.01M4 12h.01M4 18h.01" />
        </svg>회의 기록</h1>
      </header>
      <div className="meetings-list-viewport page-scroll-content">
        <MeetingList emptyMessage="회의 기록이 없습니다. 회의를 완료하면 여기에 표시됩니다." enableFilters quietEmpty />
      </div>
      <div className="meetings-create-dock"><button className="button button-primary" type="button" onClick={() => navigate('/meetings/new')}>새 회의 시작</button></div>
    </main>
  );
}

export { MeetingList };

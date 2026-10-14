import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { listMeetings, type MeetingSummary } from './meetingListApi';
import { filterMeetings, groupMeetingsByLocalMonth, hasInvalidDateRange, type MeetingFilters } from './meetingFilters';

type ListState = 'loading' | 'ready' | 'error';

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

function MeetingRows({ meetings, showParticipantNames, linkRows = false }: { meetings: MeetingSummary[]; showParticipantNames: boolean; linkRows?: boolean }) {
  return (
    <ul className="meeting-summary-list">
      {meetings.map((meeting) => (
        <li className="meeting-summary-row" key={meeting.documentId}>
          <h3>{linkRows ? <Link aria-label={`${meeting.title} 상세 보기`} to={`/meetings/${encodeURIComponent(meeting.documentId)}`}>{meeting.title}</Link> : meeting.title}</h3>
          <p>{formatMeetingDate(meeting.meetingAt)}</p>
          <p>{participantSummary(meeting, showParticipantNames)}</p>
        </li>
      ))}
    </ul>
  );
}

function MeetingList({ limit, emptyMessage, enableFilters = false }: { limit?: number; emptyMessage: string; enableFilters?: boolean }) {
  const [meetings, setMeetings] = useState<MeetingSummary[]>([]);
  const [state, setState] = useState<ListState>('loading');
  const [attempt, setAttempt] = useState(0);
  const [filters, setFilters] = useState<MeetingFilters>({ query: '', from: '', to: '' });

  useEffect(() => {
    let active = true;
    void listMeetings(limit).then((items) => {
      if (!active) return;
      setMeetings(items);
      setState('ready');
    }).catch(() => {
      if (active) setState('error');
    });
    return () => { active = false; };
  }, [limit, attempt]);

  const visibleMeetings = useMemo(() => (enableFilters ? filterMeetings(meetings, filters) : meetings).slice(0, limit ?? 100), [enableFilters, filters, limit, meetings]);

  if (state === 'loading') return <div className="state-card loading" role="status"><span className="spinner" aria-hidden="true" />회의 목록을 불러오는 중…</div>;
  if (state === 'error') return (
    <div className="state-card error-card" role="alert">
      <p>회의 목록을 불러오지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.</p>
      <button className="button button-secondary" type="button" onClick={() => { setState('loading'); setAttempt((value) => value + 1); }}>다시 시도</button>
    </div>
  );
  if (meetings.length === 0) return <div className="state-card empty-card"><p>{emptyMessage}</p></div>;
  if (!enableFilters) return <MeetingRows meetings={visibleMeetings} showParticipantNames={limit === undefined} />;
  const invalidRange = hasInvalidDateRange(filters.from, filters.to);
  const reset = () => setFilters({ query: '', from: '', to: '' });
  return (
    <>
      <form className="meeting-filters" aria-label="회의 필터" onSubmit={(event) => event.preventDefault()}>
        <label>회의 검색<input type="search" aria-label="회의 검색" value={filters.query} onChange={(event) => setFilters((current) => ({ ...current, query: event.target.value }))} placeholder="회의명 또는 참석자" /></label>
        <label>시작일<input type="date" aria-label="시작일" value={filters.from} onChange={(event) => setFilters((current) => ({ ...current, from: event.target.value }))} aria-invalid={invalidRange} aria-describedby={invalidRange ? 'meeting-date-error' : undefined} /></label>
        <label>종료일<input type="date" aria-label="종료일" value={filters.to} onChange={(event) => setFilters((current) => ({ ...current, to: event.target.value }))} aria-invalid={invalidRange} aria-describedby={invalidRange ? 'meeting-date-error' : undefined} /></label>
        {invalidRange && <p id="meeting-date-error" className="field-error" role="alert">시작일은 종료일보다 늦을 수 없습니다. 날짜 범위는 적용하지 않았습니다.</p>}
        <button className="button button-secondary" type="button" onClick={reset}>필터 초기화</button>
      </form>
      {visibleMeetings.length === 0 ? (
        <div className="state-card empty-card"><p>검색 결과가 없습니다.</p><button className="button button-secondary" type="button" onClick={reset}>필터 초기화</button></div>
      ) : groupMeetingsByLocalMonth(visibleMeetings).map((group) => (
        <section className="meeting-month-group" aria-labelledby={`month-${group.key}`} key={group.key}>
          <h2 id={`month-${group.key}`}>{group.label}</h2>
          <MeetingRows meetings={group.meetings} showParticipantNames linkRows />
        </section>
      ))}
    </>
  );
}

export function HomePage() {
  return (
    <main className="home-page">
      <div className="home-content">
        <p className="eyebrow">MEETING AUTOMATION</p>
        <h1>Meeting Automation</h1>
        <div className="home-message">
          <h2>회의를 시작할까요?</h2>
          <p>회의 내용을 녹음하고 자동으로 정리합니다.</p>
        </div>
        <Link className="button button-primary home-cta" to="/meetings/new">새 회의 시작</Link>
        <section className="home-recent" aria-labelledby="recent-meetings-title">
          <div className="home-recent-heading">
            <h2 id="recent-meetings-title">최근 회의</h2>
            <Link to="/meetings">전체 보기</Link>
          </div>
          <MeetingList limit={5} emptyMessage="최근 회의가 없습니다. 새 회의를 시작해 보세요." />
        </section>
      </div>
    </main>
  );
}

export function MeetingsPage() {
  return (
    <main className="meetings-page">
      <header className="meetings-heading">
        <p className="eyebrow">MEETING AUTOMATION</p>
        <h1>회의록</h1>
        <p>저장된 회의를 최신순으로 확인합니다.</p>
      </header>
      <MeetingList emptyMessage="회의 기록이 없습니다. 회의를 완료하면 여기에 표시됩니다." enableFilters />
    </main>
  );
}

export { MeetingList };

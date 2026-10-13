import { useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router';
import { getDocumentAppConfig, listMeetingParticipants, type DocumentAppConfig } from './newMeetingApi';
import type { Participant } from './participantsApi';
import { getReviewSession, type ReviewSpeaker } from './reviewApi';

type Resource<T> = { status: 'loading' | 'ready' | 'error'; data?: T };
type Props = {
  drafts: Record<string, Record<string, string | null>>;
  onInitialize: (sessionId: string, speakers: ReviewSpeaker[]) => void;
  onChange: (sessionId: string, speakerId: string, participantId: string | null) => void;
};

export function ReviewSpeakerPage({ drafts, onInitialize, onChange }: Props) {
  const { sessionId = '' } = useParams();
  const location = useLocation();
  const [review, setReview] = useState<Resource<Awaited<ReturnType<typeof getReviewSession>>>>({ status: 'loading' });
  const [participants, setParticipants] = useState<Resource<Participant[]>>({ status: 'loading' });
  const [config, setConfig] = useState<Resource<DocumentAppConfig>>({ status: 'loading' });
  const [retryingRoster, setRetryingRoster] = useState(false);
  const currentDraft = drafts[sessionId] ?? {};

  const loadRoster = useCallback(async () => {
    setRetryingRoster(true);
    setParticipants((current) => ({ status: 'loading', data: current.data }));
    try { setParticipants({ status: 'ready', data: await listMeetingParticipants() }); }
    catch { setParticipants((current) => ({ status: 'error', data: current.data })); }
    finally { setRetryingRoster(false); }
  }, []);

  const loadReview = useCallback(async () => {
    setReview((current) => ({ status: 'loading', data: current.data }));
    try {
      const data = await getReviewSession(sessionId);
      setReview({ status: 'ready', data });
      onInitialize(sessionId, data.speakers);
    } catch { setReview((current) => ({ status: 'error', data: current.data })); }
  }, [onInitialize, sessionId]);

  const loadConfig = useCallback(async () => {
    setConfig((current) => ({ status: 'loading', data: current.data }));
    try { setConfig({ status: 'ready', data: await getDocumentAppConfig() }); }
    catch { setConfig((current) => ({ status: 'error', data: current.data })); }
  }, []);

  useEffect(() => {
    let active = true;
    void getReviewSession(sessionId).then((data) => {
      if (!active) return;
      setReview({ status: 'ready', data });
      onInitialize(sessionId, data.speakers);
    }).catch(() => {
      if (active) setReview((current) => ({ status: 'error', data: current.data }));
    });
    void listMeetingParticipants().then((data) => {
      if (active) setParticipants({ status: 'ready', data });
    }).catch(() => {
      if (active) setParticipants((current) => ({ status: 'error', data: current.data }));
    });
    void getDocumentAppConfig().then((data) => {
      if (active) setConfig({ status: 'ready', data });
    }).catch(() => {
      if (active) setConfig((current) => ({ status: 'error', data: current.data }));
    });
    return () => { active = false; };
  }, [onInitialize, sessionId]);

  const availableParticipants = (participants.data ?? []).filter(({ id }) => review.data?.participantIds.includes(id));
  const providerReady = config.status === 'ready' && Boolean(config.data?.document?.provider) && config.data?.document?.configured === true;
  const needsSettings = config.status === 'ready' && (!config.data?.document?.provider || config.data.document.configured !== true);

  function speakerValue(speaker: ReviewSpeaker): string {
    if (Object.prototype.hasOwnProperty.call(currentDraft, speaker.speakerId)) return currentDraft[speaker.speakerId] ?? '';
    return speaker.participantId ?? '';
  }

  return (
    <main className="review-speaker-page">
      <header className="review-speaker-heading">
        <p className="eyebrow">MEETING REVIEW</p>
        <h1>회의록 검토</h1>
        <p>선택한 화자 매핑은 해당 화자의 모든 발화에 적용됩니다.</p>
      </header>

      {review.status === 'loading' && <p role="status">회의의 화자 정보를 불러오는 중입니다.</p>}
      {review.status === 'error' && <section className="review-speaker-message" role="alert">
        <p>회의 화자 정보를 불러오지 못했습니다. 다시 시도해 주세요.</p>
        <button className="button button-secondary" type="button" onClick={() => void loadReview()}>회의 정보 다시 불러오기</button>
      </section>}

      {review.data && <section className="review-speaker-card" aria-labelledby="speaker-mapping-heading">
        <div className="review-speaker-card-heading">
          <div><h2 id="speaker-mapping-heading">화자 매핑</h2><p>매핑한 참석자 이름은 모든 해당 발화에 적용됩니다.</p></div>
          {providerReady && <button className="button button-secondary" type="button" disabled={retryingRoster || participants.status === 'loading'} onClick={() => void loadRoster()}>참석자 목록 새로고침</button>}
        </div>
        {participants.status === 'loading' && <p role="status">참석자 목록을 불러오는 중입니다.</p>}
        {participants.status === 'error' && <div className="review-speaker-message" role="alert">
          <p>참석자 목록을 불러오지 못했습니다. 기존 선택은 유지됩니다.</p>
          <button className="button button-secondary" type="button" disabled={retryingRoster} onClick={() => void loadRoster()}>{retryingRoster ? '불러오는 중…' : '참석자 목록 다시 불러오기'}</button>
        </div>}
        {needsSettings && <div className="review-speaker-message" role="status"><p>문서 서비스를 연결하면 화자를 참석자와 연결할 수 있습니다.</p><Link className="button button-primary" to="/settings" state={{ from: location.pathname }}>문서 설정 열기</Link></div>}
        {config.status === 'error' && <div className="review-speaker-message" role="alert"><p>문서 연결 상태를 확인할 수 없습니다. 기존 선택은 유지됩니다.</p><button className="button button-secondary" type="button" onClick={() => void loadConfig()}>설정 상태 다시 확인</button></div>}
        {participants.status === 'ready' && participants.data?.length === 0 && <div className="review-speaker-message" role="status"><p>선택할 참석자가 없습니다. 참석자를 먼저 추가해 주세요.</p><Link className="button button-secondary" to="/settings/participants" state={{ from: location.pathname }}>참석자 관리</Link></div>}
        {review.data.speakers.length === 0 && <p role="status">감지된 화자가 없습니다.</p>}
        <div className="review-speaker-list">
          {review.data.speakers.map((speaker) => {
            const value = speakerValue(speaker);
            const selectedIsInList = availableParticipants.some(({ id }) => id === value);
            const fallbackLabel = participants.status === 'loading' ? '참석자 목록 확인 중' : '기존 매핑 유지';
            return <div className="review-speaker-row" key={speaker.speakerId}>
              <label htmlFor={`speaker-${speaker.speakerId}`}>{speaker.label} 참가자</label>
              {providerReady ? <select
                id={`speaker-${speaker.speakerId}`}
                aria-label={`${speaker.label} 참가자`}
                value={value}
                disabled={participants.status === 'loading' || availableParticipants.length === 0}
                onChange={(event) => onChange(sessionId, speaker.speakerId, event.target.value || null)}
              >
                <option value="">참가자 선택</option>
                {value && !selectedIsInList && <option value={value}>{fallbackLabel}</option>}
                {availableParticipants.map(({ id, name }) => <option key={id} value={id}>{name}</option>)}
              </select> : <p className="review-speaker-current">{speaker.participantId ? (availableParticipants.find(({ id }) => id === value)?.name ?? fallbackLabel) : '참가자 선택'}</p>}
            </div>;
          })}
        </div>
      </section>}
    </main>
  );
}

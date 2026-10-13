import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router';
import { getDocumentAppConfig, listMeetingParticipants, type DocumentAppConfig } from './newMeetingApi';
import type { Participant } from './participantsApi';
import { getReviewSession, updateSpeakerMappings, type ReviewSession, type ReviewSpeaker, type SpeakerMappingInput, type StructuredMinutes } from './reviewApi';
import { ReviewMinutesEditor, type MinutesSaveState } from './ReviewMinutesEditor';
import { ReviewTranscriptDrawer } from './ReviewTranscriptDrawer';

type Resource<T> = { status: 'loading' | 'ready' | 'error'; data?: T };
type ReviewResource = Resource<Awaited<ReturnType<typeof getReviewSession>>> & { requestSessionId: string };
type MinutesDraftStore = {
  drafts: Record<string, StructuredMinutes | null>;
  baselines: Record<string, StructuredMinutes | null>;
  saveStatuses: Record<string, MinutesSaveState['status']>;
};
type Props = {
  drafts: Record<string, Record<string, string | null>>;
  onInitialize: (sessionId: string, speakers: ReviewSpeaker[]) => void;
  onChange: (sessionId: string, speakerId: string, participantId: string | null) => void;
  onCommit: (sessionId: string, speakers: ReviewSpeaker[]) => void;
  onMinutesSave?: (sessionId: string, minutes: StructuredMinutes) => void;
  minutesSaveState?: MinutesSaveState;
};

export function ReviewSpeakerPage({ drafts, onInitialize, onChange, onCommit, onMinutesSave, minutesSaveState }: Props) {
  const { sessionId = '' } = useParams();
  const location = useLocation();
  const [review, setReview] = useState<ReviewResource>(() => ({ status: 'loading', requestSessionId: sessionId }));
  const [participants, setParticipants] = useState<Resource<Participant[]>>({ status: 'loading' });
  const [config, setConfig] = useState<Resource<DocumentAppConfig>>({ status: 'loading' });
  const [retryingRoster, setRetryingRoster] = useState(false);
  const [minutesDraftStore, setMinutesDraftStore] = useState<MinutesDraftStore>(() => ({ drafts: {}, baselines: {}, saveStatuses: {} }));
  const [transcriptOpen, setTranscriptOpen] = useState(false);
  const [saveState, setSaveState] = useState<{ sessionId: string; saving: boolean; message: string; recoveryAttempt: SpeakerMappingInput[] | null }>({
    sessionId: '', saving: false, message: '', recoveryAttempt: null,
  });
  const reviewRequestGeneration = useRef(0);
  const saveInFlight = useRef(false);
  const activeSessionId = useRef(sessionId);
  const latestReview = useRef<{ sessionId: string; version: number } | null>(null);
  const currentDraft = drafts[sessionId] ?? {};
  const sessionSaveState = saveState.sessionId === sessionId ? saveState : { sessionId, saving: false, message: '', recoveryAttempt: null };
  const saving = sessionSaveState.saving;
  const saveMessage = sessionSaveState.message;
  const recoveryAttempt = sessionSaveState.recoveryAttempt;

  function setSaving(value: boolean) {
    setSaveState((current) => ({ ...(current.sessionId === sessionId ? current : sessionSaveState), sessionId, saving: value }));
  }
  function setSaveMessage(message: string) {
    setSaveState((current) => ({ ...(current.sessionId === sessionId ? current : sessionSaveState), sessionId, message }));
  }
  function setRecoveryAttempt(attempt: SpeakerMappingInput[] | null) {
    setSaveState((current) => ({ ...(current.sessionId === sessionId ? current : sessionSaveState), sessionId, recoveryAttempt: attempt }));
  }

  useLayoutEffect(() => {
    activeSessionId.current = sessionId;
    reviewRequestGeneration.current += 1;
    saveInFlight.current = false;
    latestReview.current = null;
  }, [sessionId]);

  const applySnapshot = useCallback((data: ReviewSession) => {
    if (activeSessionId.current !== data.sessionId) return false;
    const latest = latestReview.current;
    if (latest?.sessionId === data.sessionId && data.version < latest.version) return false;
    latestReview.current = { sessionId: data.sessionId, version: data.version };
    setMinutesDraftStore((current) => Object.prototype.hasOwnProperty.call(current.baselines, data.sessionId)
      ? current
      : { ...current, baselines: { ...current.baselines, [data.sessionId]: data.minutes } });
    setReview({ status: 'ready', data, requestSessionId: data.sessionId });
    onInitialize(data.sessionId, data.speakers);
    return true;
  }, [onInitialize]);

  const loadRoster = useCallback(async () => {
    setRetryingRoster(true);
    setParticipants((current) => ({ status: 'loading', data: current.data }));
    try { setParticipants({ status: 'ready', data: await listMeetingParticipants() }); }
    catch { setParticipants((current) => ({ status: 'error', data: current.data })); }
    finally { setRetryingRoster(false); }
  }, []);

  const loadReview = useCallback(async () => {
    const generation = ++reviewRequestGeneration.current;
    setReview((current) => ({ status: 'loading', data: current.data, requestSessionId: sessionId }));
    try {
      const data = await getReviewSession(sessionId);
      if (generation !== reviewRequestGeneration.current) return;
      applySnapshot(data);
    } catch {
      if (generation === reviewRequestGeneration.current) setReview((current) => ({ status: 'error', data: current.data, requestSessionId: sessionId }));
    }
  }, [applySnapshot, sessionId]);

  const loadConfig = useCallback(async () => {
    setConfig((current) => ({ status: 'loading', data: current.data }));
    try { setConfig({ status: 'ready', data: await getDocumentAppConfig() }); }
    catch { setConfig((current) => ({ status: 'error', data: current.data })); }
  }, []);

  useEffect(() => {
    const generation = ++reviewRequestGeneration.current;
    saveInFlight.current = false;
    latestReview.current = null;
    let active = true;
    void getReviewSession(sessionId).then((data) => {
      if (!active || generation !== reviewRequestGeneration.current) return;
      applySnapshot(data);
    }).catch(() => {
      if (active && generation === reviewRequestGeneration.current) setReview((current) => ({ status: 'error', data: current.data, requestSessionId: sessionId }));
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
    return () => { active = false; reviewRequestGeneration.current += 1; };
  }, [applySnapshot, onInitialize, sessionId]);

  const currentReview = review.requestSessionId === sessionId && review.data?.sessionId === sessionId ? review.data : undefined;
  const reviewIsLoading = review.requestSessionId !== sessionId || review.status === 'loading';
  const reviewHasError = review.requestSessionId === sessionId && review.status === 'error';
  const availableParticipants = (participants.data ?? []).filter(({ id }) => currentReview?.participantIds.includes(id));
  const providerReady = config.status === 'ready' && Boolean(config.data?.document?.provider) && config.data?.document?.configured === true;
  const needsSettings = config.status === 'ready' && (!config.data?.document?.provider || config.data.document.configured !== true);
  const hasMinutesDraft = Object.prototype.hasOwnProperty.call(minutesDraftStore.drafts, sessionId);
  const currentMinutes = hasMinutesDraft ? minutesDraftStore.drafts[sessionId] : currentReview?.minutes ?? null;
  const hasMinutesBaseline = Object.prototype.hasOwnProperty.call(minutesDraftStore.baselines, sessionId);
  const minutesBaseline = hasMinutesBaseline ? minutesDraftStore.baselines[sessionId] : currentReview?.minutes ?? null;
  const minutesOwnerState = !providerReady
    ? config.status === 'loading' ? 'loading' : config.status === 'error' ? 'error' : 'unavailable'
    : participants.status;
  const sessionMinutesSaveState = minutesSaveState?.sessionId === sessionId
    ? minutesSaveState : { sessionId, status: 'idle' as const };
  const observedMinutesSaveStatus = minutesSaveState?.sessionId === sessionId ? minutesSaveState.status : undefined;
  if (currentReview && observedMinutesSaveStatus && minutesDraftStore.saveStatuses[sessionId] !== observedMinutesSaveStatus) {
    const savedMinutes = hasMinutesDraft ? currentMinutes : currentReview.minutes;
    setMinutesDraftStore((current) => ({
      ...current,
      baselines: observedMinutesSaveStatus === 'saved'
        ? { ...current.baselines, [sessionId]: savedMinutes }
        : current.baselines,
      saveStatuses: { ...current.saveStatuses, [sessionId]: observedMinutesSaveStatus },
    }));
  }

  const updateMinutesDraft = useCallback((draft: StructuredMinutes) => {
    setMinutesDraftStore((current) => ({ ...current, drafts: { ...current.drafts, [sessionId]: draft } }));
  }, [sessionId]);

  function speakerValue(speaker: ReviewSpeaker): string {
    if (Object.prototype.hasOwnProperty.call(currentDraft, speaker.speakerId)) return currentDraft[speaker.speakerId] ?? '';
    return speaker.participantId ?? '';
  }

  const submittedMappings = currentReview?.speakers.map((speaker) => ({ speakerId: speaker.speakerId, participantId: speakerValue(speaker) || null })) ?? [];
  const committedMappings = currentReview?.speakers.map((speaker) => ({ speakerId: speaker.speakerId, participantId: speaker.participantId })) ?? [];
  const draftIsDirty = submittedMappings.length > 0 && submittedMappings.some((mapping, index) => mapping.participantId !== committedMappings[index]?.participantId);
  const canSave = Boolean(currentReview && providerReady && currentReview.allowedActions.includes('UPDATE_SPEAKER_MAPPING')
    && draftIsDirty && !saving && participants.status !== 'loading');

  async function reconcile(attempt: SpeakerMappingInput[], isUncertain: boolean, generation = reviewRequestGeneration.current, expectedSessionId = sessionId) {
    const isCurrent = () => generation === reviewRequestGeneration.current && activeSessionId.current === expectedSessionId;
    try {
      const latest = await getReviewSession(expectedSessionId);
      if (!isCurrent()) return;
      const exact = latest.speakers.length === attempt.length && latest.speakers.every((speaker, index) =>
        speaker.speakerId === attempt[index]?.speakerId && speaker.participantId === attempt[index]?.participantId);
      if (!applySnapshot(latest)) return;
      if (exact && isUncertain) {
        onCommit(expectedSessionId, latest.speakers);
        setSaveMessage('매핑 저장이 확인되었습니다.');
        setRecoveryAttempt(null);
      } else {
        setSaveMessage(isUncertain
          ? '서버의 최신 매핑을 불러왔습니다. 변경 내용은 유지했습니다. 확인 후 다시 저장해 주세요.'
          : '최신 회의 정보를 불러왔습니다. 변경 내용은 유지했으며 자동 저장하지 않았습니다. 확인 후 다시 저장해 주세요.');
        setRecoveryAttempt(null);
      }
    } catch {
      if (!isCurrent()) return;
      setSaveMessage('저장 결과를 확인하지 못했습니다. 변경 내용은 유지됩니다. 최신 상태를 다시 확인해 주세요.');
      setRecoveryAttempt(attempt);
    }
  }

  async function refreshAfterCommit(expectedSessionId: string, committedVersion: number, generation: number) {
    try {
      const latest = await getReviewSession(expectedSessionId);
      if (generation !== reviewRequestGeneration.current || activeSessionId.current !== expectedSessionId) return;
      if (latest.version < committedVersion) return;
      applySnapshot(latest);
    } catch {
      if (generation === reviewRequestGeneration.current && activeSessionId.current === expectedSessionId) {
        setSaveMessage('매핑은 저장되었습니다. 최신 회의 권한을 확인하지 못했습니다. 화면을 다시 불러와 확인해 주세요.');
      }
    }
  }

  async function saveMappings() {
    if (!currentReview || !canSave || saveInFlight.current) return;
    const expectedSessionId = sessionId;
    const generation = reviewRequestGeneration.current;
    const isCurrent = () => generation === reviewRequestGeneration.current && activeSessionId.current === expectedSessionId;
    saveInFlight.current = true;
    setSaving(true);
    setSaveMessage('매핑을 저장하는 중입니다.');
    const attempt = submittedMappings;
    try {
      const result = await updateSpeakerMappings(expectedSessionId, currentReview.version, attempt);
      if (!isCurrent()) return;
      const byId = new Map(result.mappings.map((mapping) => [mapping.speakerId, mapping]));
      const speakers = currentReview.speakers.map((speaker) => {
        const mapping = byId.get(speaker.speakerId);
        return mapping ? { ...speaker, participantId: mapping.participantId, participantName: mapping.participantName } : speaker;
      });
      const committed = { ...currentReview, version: result.version, speakers };
      if (!applySnapshot(committed)) {
        setSaveMessage('더 최신 회의 정보가 확인되어 저장 결과를 반영하지 않았습니다. 최신 상태를 확인해 주세요.');
        return;
      }
      onCommit(expectedSessionId, speakers);
      setSaveMessage('매핑이 저장되었습니다.');
      setRecoveryAttempt(null);
      await refreshAfterCommit(expectedSessionId, result.version, generation);
    } catch (error) {
      if (!isCurrent()) return;
      if (error instanceof Error && 'status' in error && error.status === 412) {
        await reconcile(attempt, false, generation, expectedSessionId);
      } else if (error instanceof Error && 'outcomeUnknown' in error && error.outcomeUnknown === true) {
        await reconcile(attempt, true, generation, expectedSessionId);
      } else {
        setSaveMessage('매핑을 저장하지 못했습니다. 기존 저장 내용과 변경 내용은 유지됩니다. 다시 시도해 주세요.');
      }
    } finally {
      if (isCurrent()) {
        saveInFlight.current = false;
        setSaving(false);
      }
    }
  }

  return (
    <main className="review-speaker-page">
      <header className="review-speaker-heading">
        <p className="eyebrow">MEETING REVIEW</p>
        <h1>회의록 검토</h1>
        <p>회의록과 화자 정보를 확인하고 수정할 수 있습니다.</p>
      </header>

      {reviewIsLoading && <p role="status">회의의 화자 정보를 불러오는 중입니다.</p>}
      {reviewHasError && <section className="review-speaker-message" role="alert">
        <p>회의 화자 정보를 불러오지 못했습니다. 다시 시도해 주세요.</p>
        <button className="button button-secondary" type="button" onClick={() => void loadReview()}>회의 정보 다시 불러오기</button>
      </section>}

      {currentReview && <>
        <ReviewMinutesEditor
          key={sessionId}
          sessionId={sessionId}
          minutes={currentMinutes}
          baseline={minutesBaseline}
          participants={availableParticipants}
          ownerState={minutesOwnerState}
          saveState={sessionMinutesSaveState}
          onSave={onMinutesSave}
          onChange={updateMinutesDraft}
        />
        <section className="review-speaker-card" aria-labelledby="speaker-mapping-heading">
        <div className="review-speaker-card-heading">
          <div><h2 id="speaker-mapping-heading">화자 매핑</h2><p>매핑한 참석자 이름은 모든 해당 발화에 적용됩니다.</p></div>
          <div className="review-speaker-actions">
            <button className="button button-secondary" type="button" onClick={() => setTranscriptOpen(true)}>Transcript 원문 보기</button>
            {providerReady && <button className="button button-secondary" type="button" disabled={retryingRoster || participants.status === 'loading'} onClick={() => void loadRoster()}>참석자 목록 새로고침</button>}
          </div>
        </div>
        {participants.status === 'loading' && <p role="status">참석자 목록을 불러오는 중입니다.</p>}
        {participants.status === 'error' && <div className="review-speaker-message" role="alert">
          <p>참석자 목록을 불러오지 못했습니다. 기존 선택은 유지됩니다.</p>
          <button className="button button-secondary" type="button" disabled={retryingRoster} onClick={() => void loadRoster()}>{retryingRoster ? '불러오는 중…' : '참석자 목록 다시 불러오기'}</button>
        </div>}
        {needsSettings && <div className="review-speaker-message" role="status"><p>문서 서비스를 연결하면 화자를 참석자와 연결할 수 있습니다.</p><Link className="button button-primary" to="/settings" state={{ from: location.pathname }}>문서 설정 열기</Link></div>}
        {config.status === 'error' && <div className="review-speaker-message" role="alert"><p>문서 연결 상태를 확인할 수 없습니다. 기존 선택은 유지됩니다.</p><button className="button button-secondary" type="button" onClick={() => void loadConfig()}>설정 상태 다시 확인</button></div>}
        {participants.status === 'ready' && participants.data?.length === 0 && <div className="review-speaker-message" role="status"><p>선택할 참석자가 없습니다. 참석자를 먼저 추가해 주세요.</p><Link className="button button-secondary" to="/settings/participants" state={{ from: location.pathname }}>참석자 관리</Link></div>}
        {currentReview.speakers.length === 0 && <p role="status">감지된 화자가 없습니다.</p>}
        <div className="review-speaker-list">
          {currentReview.speakers.map((speaker) => {
            const value = speakerValue(speaker);
            const selectedIsInList = availableParticipants.some(({ id }) => id === value);
            const fallbackLabel = participants.status === 'loading' ? '참석자 목록 확인 중' : '기존 매핑 유지';
            return <div className="review-speaker-row" key={speaker.speakerId}>
              <label htmlFor={`speaker-${speaker.speakerId}`}>{speaker.label} 참가자</label>
              {providerReady ? <select
                id={`speaker-${speaker.speakerId}`}
                aria-label={`${speaker.label} 참가자`}
                value={value}
                disabled={saving || participants.status === 'loading' || availableParticipants.length === 0}
                onChange={(event) => onChange(sessionId, speaker.speakerId, event.target.value || null)}
              >
                <option value="">참가자 선택</option>
                {value && !selectedIsInList && <option value={value}>{fallbackLabel}</option>}
                {availableParticipants.map(({ id, name }) => <option key={id} value={id}>{name}</option>)}
              </select> : <p className="review-speaker-current">{speaker.participantId ? (availableParticipants.find(({ id }) => id === value)?.name ?? fallbackLabel) : '참가자 선택'}</p>}
            </div>;
          })}
        </div>
        {currentReview.transcript.length > 0 && <section className="review-transcript" aria-label="화자 매핑 미리보기">
          <h3>발화 미리보기</h3>
          {currentReview.transcript.map((segment) => {
            const speaker = currentReview.speakers.find((item) => item.speakerId === segment.speakerId);
            const participantId = speaker ? speakerValue(speaker) : null;
            const participant = availableParticipants.find((item) => item.id === participantId);
            const participantName = participant?.name ?? (participantId === speaker?.participantId ? speaker?.participantName : null) ?? null;
            return <p key={segment.segmentId} data-segment-id={segment.segmentId} data-start-ms={segment.startMs} data-end-ms={segment.endMs}
              data-speaker-name={participantName ?? ''}>
              <span>{speaker?.label ?? segment.speakerId}</span>{participantName && <span> · {participantName}</span>}{' '}
              <time>{segment.startMs}–{segment.endMs}ms</time>{' '}{segment.text}
            </p>;
          })}
        </section>}
        {(currentReview.allowedActions.includes('UPDATE_SPEAKER_MAPPING') || saveMessage) && <div className="review-save-controls">
          {currentReview.allowedActions.includes('UPDATE_SPEAKER_MAPPING') && <button className="button button-primary" type="button" disabled={!canSave} onClick={() => void saveMappings()}>
            {saving ? '저장 중…' : '매핑 저장'}
          </button>}
          {saveMessage && <p role="status">{saveMessage}</p>}
          {recoveryAttempt && <button className="button button-secondary" type="button" disabled={saving} onClick={() => void reconcile(recoveryAttempt, true)}>저장 상태 다시 확인</button>}
        </div>}
        </section>
        <ReviewTranscriptDrawer
          open={transcriptOpen}
          segments={currentReview.transcript}
          speakers={currentReview.speakers}
          participants={participants.data ?? []}
          selectedParticipantId={(speaker) => speakerValue(speaker) || null}
          onClose={() => setTranscriptOpen(false)}
        />
      </>}
    </main>
  );
}

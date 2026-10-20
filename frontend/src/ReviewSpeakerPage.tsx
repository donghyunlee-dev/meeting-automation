import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { getDocumentAppConfig, listMeetingParticipants, type DocumentAppConfig } from './newMeetingApi';
import type { Participant } from './participantsApi';
import { getReviewSession, ReviewApiError, updateReviewMinutes, updateSpeakerMappings, type ReviewSession, type ReviewSpeaker, type SpeakerMappingInput, type StructuredMinutes } from './reviewApi';
import { ReviewMinutesEditor, type MinutesSaveState } from './ReviewMinutesEditor';
import { ReviewTranscriptDrawer } from './ReviewTranscriptDrawer';
import { getMinutesRegenerationSnapshot, type MinutesRegenerationSnapshot } from './minutesRegenerationApi';
import { ProcessingPoller } from './processingPoller';
import { AsyncState } from './components/async-state/AsyncState';
import { MeetingListBackLink } from './components/MeetingListBackLink';
import { normalizeApiError } from './lib/api/normalizeApiError';
import { confirmReview, createIdempotencyKey, getPublishSnapshot, PublishApiError, publishSession } from './publishApi';

type Resource<T> = { status: 'loading' | 'ready' | 'error'; data?: T };
type ReviewResource = Resource<Awaited<ReturnType<typeof getReviewSession>>> & { requestSessionId: string };
type MinutesDraftStore = {
  drafts: Record<string, StructuredMinutes | null>;
  baselines: Record<string, StructuredMinutes | null>;
  baseVersions: Record<string, number>;
  saveStatuses: Record<string, MinutesSaveState['status']>;
};
type MinutesMutationState = MinutesSaveState & { recoverySnapshot?: StructuredMinutes };
type ReviewFailureCode = 'SESSION_NOT_FOUND' | 'SESSION_NOT_IN_REVIEW' | 'OTHER';
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
  const navigate = useNavigate();
  const [review, setReview] = useState<ReviewResource>(() => ({ status: 'loading', requestSessionId: sessionId }));
  const [participants, setParticipants] = useState<Resource<Participant[]>>({ status: 'loading' });
  const [config, setConfig] = useState<Resource<DocumentAppConfig>>({ status: 'loading' });
  const [retryingRoster, setRetryingRoster] = useState(false);
  const [completingReview, setCompletingReview] = useState(false);
  const [minutesDraftStore, setMinutesDraftStore] = useState<MinutesDraftStore>(() => ({ drafts: {}, baselines: {}, baseVersions: {}, saveStatuses: {} }));
  const [minutesMutationStates, setMinutesMutationStates] = useState<Record<string, MinutesMutationState>>({});
  const [reviewFailure, setReviewFailure] = useState<{ sessionId: string; code: ReviewFailureCode } | null>(null);
  const [transcriptOpen, setTranscriptOpen] = useState(false);
  const [regenerationResume, setRegenerationResume] = useState<{ sessionId: string; progress: number | null } | null>(null);
  const [regenerationResumeMessage, setRegenerationResumeMessage] = useState<{ sessionId: string; message: string } | null>(null);
  const [saveState, setSaveState] = useState<{ sessionId: string; saving: boolean; message: string; recoveryAttempt: SpeakerMappingInput[] | null }>({
    sessionId: '', saving: false, message: '', recoveryAttempt: null,
  });
  const [completionNotice, setCompletionNotice] = useState<{ sessionId: string; message: string } | null>(null);
  const reviewRequestGeneration = useRef(0);
  const saveInFlight = useRef(false);
  const confirmInFlight = useRef(false);
  const confirmAttempt = useRef<{ version: number; key: string } | null>(null);
  const minutesSaveInFlight = useRef(new Set<string>());
  const activeSessionId = useRef(sessionId);
  const latestReview = useRef<{ sessionId: string; version: number } | null>(null);
  const serverBaseBySession = useRef(new Map<string, { version: number; minutes: StructuredMinutes | null }>());
  const minutesDraftBySession = useRef(new Map<string, StructuredMinutes | null>());
  const restoredRouteState = useRef<string | null>(null);
  const routeSpeakerDrafts = (location.state as { speakerDrafts?: Record<string, string | null> } | null)?.speakerDrafts;
  const currentDraft = Object.keys(drafts[sessionId] ?? {}).length > 0 ? drafts[sessionId] ?? {} : routeSpeakerDrafts ?? {};
  const sessionSaveState = saveState.sessionId === sessionId ? saveState : { sessionId, saving: false, message: '', recoveryAttempt: null };
  const saving = sessionSaveState.saving;
  const saveMessage = sessionSaveState.message;
  const recoveryAttempt = sessionSaveState.recoveryAttempt;

  function setMinutesMutationState(expectedSessionId: string, state: MinutesMutationState) {
    setMinutesMutationStates((current) => ({ ...current, [expectedSessionId]: state }));
  }

  function rememberMinutesBase(expectedSessionId: string, version: number, minutes: StructuredMinutes | null): boolean {
    const previous = serverBaseBySession.current.get(expectedSessionId);
    if (previous && version < previous.version) return false;
    serverBaseBySession.current.set(expectedSessionId, { version, minutes });
    setMinutesDraftStore((current) => {
      const existingVersion = current.baseVersions[expectedSessionId];
      if (existingVersion !== undefined && version < existingVersion) return current;
      return {
        ...current,
        baselines: { ...current.baselines, [expectedSessionId]: minutes },
        baseVersions: { ...current.baseVersions, [expectedSessionId]: version },
      };
    });
    return true;
  }

  const recordReviewFailure = useCallback((expectedSessionId: string, error: unknown) => {
    if (activeSessionId.current !== expectedSessionId) return;
    const code = error instanceof ReviewApiError && (error.code === 'SESSION_NOT_FOUND' || error.apiCode === 'SESSION_NOT_FOUND')
      ? 'SESSION_NOT_FOUND'
      : error instanceof ReviewApiError && error.code === 'SESSION_NOT_IN_REVIEW'
        ? 'SESSION_NOT_IN_REVIEW' : 'OTHER';
    setReviewFailure({ sessionId: expectedSessionId, code });
    setReview((current) => code === 'OTHER' && current.data?.sessionId === expectedSessionId
      ? { status: 'ready', data: current.data, requestSessionId: expectedSessionId }
      : { status: 'error', requestSessionId: expectedSessionId });
  }, []);

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
    const rememberedBase = serverBaseBySession.current.get(data.sessionId);
    if (rememberedBase && data.version < rememberedBase.version) return false;
    latestReview.current = { sessionId: data.sessionId, version: data.version };
    rememberMinutesBase(data.sessionId, data.version, data.minutes);
    setReviewFailure(null);
    setReview({ status: 'ready', data, requestSessionId: data.sessionId });
    onInitialize(data.sessionId, data.speakers);
    return true;
  }, [onInitialize]);

  const applyRegenerationSnapshot = useCallback((data: ReviewSession, replaceMinutesDraft = false): boolean => {
    const applied = applySnapshot(data);
    if (!applied || !replaceMinutesDraft) return applied;
    minutesDraftBySession.current.delete(data.sessionId);
    setMinutesDraftStore((current) => {
      const drafts = { ...current.drafts };
      delete drafts[data.sessionId];
      return { ...current, drafts };
    });
    return true;
  }, [applySnapshot]);

  const reconcileTerminalRegenerationSnapshot = useCallback((snapshot: MinutesRegenerationSnapshot): boolean => {
    const operation = snapshot.lastOperation;
    if (snapshot.status !== 'REVIEW' || snapshot.sessionId !== activeSessionId.current || !snapshot.review
      || snapshot.review.sessionId !== snapshot.sessionId || snapshot.review.version !== snapshot.version
      || operation?.type !== 'MINUTES_REGENERATION' || !['SUCCEEDED', 'FAILED'].includes(operation.outcome)
      || typeof operation.requestedTemplateId !== 'string' || operation.requestedTemplateId.length === 0
      || snapshot.review.lastOperation?.type !== operation.type
      || snapshot.review.lastOperation.outcome !== operation.outcome
      || snapshot.review.lastOperation.requestedTemplateId !== operation.requestedTemplateId) return false;

    if (!applyRegenerationSnapshot(snapshot.review, true)) return false;
    setRegenerationResumeMessage({ sessionId: snapshot.sessionId, message: operation.outcome === 'SUCCEEDED'
      ? 'Minutes 재생성이 완료되어 최신 회의 정보를 표시합니다.'
      : '재생성에 실패해 이전 회의록을 복구했습니다. 최신 회의 정보를 표시합니다.' });
    return true;
  }, [applyRegenerationSnapshot]);

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
    } catch (error) {
      if (generation === reviewRequestGeneration.current) recordReviewFailure(sessionId, error);
    }
  }, [applySnapshot, recordReviewFailure, sessionId]);

  useEffect(() => {
    if (regenerationResume?.sessionId !== sessionId) return;
    const poller = new ProcessingPoller({
      sessionId,
      getStatus: (id, signal) => getMinutesRegenerationSnapshot(id, signal),
      onSnapshot: (snapshot: MinutesRegenerationSnapshot) => {
        if (snapshot.sessionId !== sessionId) return;
        if (snapshot.status === 'PROCESSING') {
          if (snapshot.processing.stage === 'DRAFT_REGENERATION') {
            setRegenerationResume({ sessionId, progress: snapshot.processing.progressPercent ?? null });
          } else {
            setRegenerationResume(null);
            recordReviewFailure(sessionId, new ReviewApiError('SESSION_NOT_IN_REVIEW'));
          }
          return;
        }
        if (snapshot.status === 'REVIEW' && reconcileTerminalRegenerationSnapshot(snapshot)) {
          setRegenerationResume(null);
          return;
        }
        setRegenerationResume(null);
        recordReviewFailure(sessionId, new ReviewApiError('SESSION_NOT_IN_REVIEW'));
      },
      onError: (error) => {
        if (error.code === 'SESSION_NOT_FOUND') {
          setRegenerationResume(null);
          recordReviewFailure(sessionId, new ReviewApiError('SESSION_NOT_FOUND', 404));
        }
      },
    });
    poller.start();
    return () => poller.stop();
  }, [reconcileTerminalRegenerationSnapshot, recordReviewFailure, regenerationResume?.sessionId, sessionId]);

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
    const statusController = new AbortController();
    void getReviewSession(sessionId).then((data) => {
      if (!active || generation !== reviewRequestGeneration.current) return;
      applySnapshot(data);
    }).catch((error: unknown) => {
      if (!active || generation !== reviewRequestGeneration.current) return;
      if (error instanceof ReviewApiError && error.code === 'SESSION_NOT_IN_REVIEW') {
        void getMinutesRegenerationSnapshot(sessionId, statusController.signal).then((snapshot) => {
          if (active && generation === reviewRequestGeneration.current && snapshot.status === 'PROCESSING'
            && snapshot.processing.stage === 'DRAFT_REGENERATION') {
            setRegenerationResume({ sessionId, progress: snapshot.processing.progressPercent ?? null });
            return;
          }
          if (active && generation === reviewRequestGeneration.current && snapshot.status === 'REVIEW'
            && reconcileTerminalRegenerationSnapshot(snapshot)) return;
          if (active && generation === reviewRequestGeneration.current) recordReviewFailure(sessionId, error);
        }).catch(() => {
          if (active && generation === reviewRequestGeneration.current) recordReviewFailure(sessionId, error);
        });
        return;
      }
      recordReviewFailure(sessionId, error);
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
    return () => { active = false; statusController.abort(); reviewRequestGeneration.current += 1; };
  }, [applySnapshot, onInitialize, reconcileTerminalRegenerationSnapshot, recordReviewFailure, sessionId]);

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
  const sessionMinutesMutationState = minutesMutationStates[sessionId] ?? { sessionId, status: 'idle' as const };
  const sessionMinutesSaveState = minutesSaveState?.sessionId === sessionId ? minutesSaveState : sessionMinutesMutationState;
  const minutesSaveBlockedMessage = currentReview && !currentReview.allowedActions.includes('UPDATE_MINUTES')
    ? '최신 Review 정보를 확인한 뒤 다시 시도해 주세요.' : undefined;
  const observedMinutesSaveStatus = minutesSaveState?.sessionId === sessionId ? minutesSaveState.status : undefined;
  if (currentReview && observedMinutesSaveStatus && minutesDraftStore.saveStatuses[sessionId] !== observedMinutesSaveStatus) {
    const savedMinutes = hasMinutesDraft ? currentMinutes : currentReview.minutes;
    setMinutesDraftStore((current) => ({
      ...current,
      baselines: observedMinutesSaveStatus === 'saved'
        ? { ...current.baselines, [sessionId]: savedMinutes }
        : current.baselines,
      baseVersions: observedMinutesSaveStatus === 'saved'
        ? { ...current.baseVersions, [sessionId]: currentReview.version }
        : current.baseVersions,
      saveStatuses: { ...current.saveStatuses, [sessionId]: observedMinutesSaveStatus },
    }));
  }
  const updateMinutesDraft = useCallback((draft: StructuredMinutes) => {
    minutesDraftBySession.current.set(sessionId, draft);
    setCompletionNotice(null);
    setMinutesDraftStore((current) => ({ ...current, drafts: { ...current.drafts, [sessionId]: draft } }));
  }, [sessionId]);

  useEffect(() => {
    const state = location.state as { minutesDraft?: StructuredMinutes } | null;
    if (currentReview?.sessionId !== sessionId || !state?.minutesDraft || restoredRouteState.current === location.key) return;
    restoredRouteState.current = location.key;
    updateMinutesDraft(state.minutesDraft);
  }, [currentReview, location.key, location.state, sessionId, updateMinutesDraft]);

  useEffect(() => {
    if (!location.hash || currentReview?.sessionId !== sessionId) return;
    const targetId = decodeURIComponent(location.hash.slice(1));
    requestAnimationFrame(() => {
      const target = document.getElementById(targetId);
      if (!target) return;
      target.scrollIntoView({ block: 'center' });
      if (target instanceof HTMLElement) target.focus({ preventScroll: true });
    });
  }, [currentReview, location.hash, sessionId]);

  function sameMinutes(left: StructuredMinutes | null | undefined, right: StructuredMinutes | null | undefined): boolean {
    return JSON.stringify(left) === JSON.stringify(right);
  }

  function snapshotMinutes(minutes: StructuredMinutes): StructuredMinutes {
    return {
      ...minutes,
      discussionPoints: [...minutes.discussionPoints],
      decisions: [...minutes.decisions],
      actionItems: minutes.actionItems.map((item) => ({ ...item })),
      followUps: [...minutes.followUps],
    };
  }

  async function reconcileMinutes(submittedSnapshot: StructuredMinutes, expectedSessionId: string, conflict: boolean) {
    try {
      const latest = await getReviewSession(expectedSessionId);
      const accepted = rememberMinutesBase(expectedSessionId, latest.version, latest.minutes);
      if (activeSessionId.current === expectedSessionId && accepted) applySnapshot(latest);
      const latestBase = serverBaseBySession.current.get(expectedSessionId);
      const currentDraft = minutesDraftBySession.current.has(expectedSessionId)
        ? minutesDraftBySession.current.get(expectedSessionId) : latestBase?.minutes ?? latest.minutes;
      const draftMatchesLatest = sameMinutes(currentDraft, latestBase?.minutes ?? latest.minutes);
      const submittedMatchesLatest = sameMinutes(submittedSnapshot, latestBase?.minutes ?? latest.minutes);
      if (submittedMatchesLatest || draftMatchesLatest) {
        setMinutesMutationState(expectedSessionId, {
          sessionId: expectedSessionId,
          status: 'saved',
          message: submittedMatchesLatest ? '저장 결과가 확인되었습니다.' : '최신 회의록과 일치합니다.',
        });
      } else {
        setMinutesMutationState(expectedSessionId, {
          sessionId: expectedSessionId,
          status: 'error',
          message: conflict
            ? '최신 회의 정보를 불러왔습니다. 수정 내용은 유지했으며 자동 저장하지 않았습니다. 확인 후 다시 저장해 주세요.'
            : '저장 결과가 서버 회의록과 다릅니다. 수정 내용은 유지했으며 확인 후 다시 저장해 주세요.',
        });
      }
    } catch (error) {
      recordReviewFailure(expectedSessionId, error);
      setMinutesMutationState(expectedSessionId, {
        sessionId: expectedSessionId,
        status: 'error',
        message: error instanceof ReviewApiError && error.code === 'SESSION_NOT_FOUND'
          ? '회의를 찾을 수 없습니다. 새 회의를 시작해 주세요.'
          : error instanceof ReviewApiError && error.code === 'SESSION_NOT_IN_REVIEW'
            ? '이 회의는 더 이상 Review 상태가 아닙니다. 처리 상태를 확인해 주세요.'
            : '저장 결과를 확인하지 못했습니다. 수정 내용은 유지됩니다. 최신 상태를 다시 확인해 주세요.',
        recoverySnapshot: snapshotMinutes(submittedSnapshot),
      });
    }
  }

  async function saveMinutes(expectedSessionId: string, draft: StructuredMinutes) {
    if (expectedSessionId !== sessionId || !currentReview || currentReview.sessionId !== expectedSessionId
      || !currentReview.allowedActions.includes('UPDATE_MINUTES') || minutesSaveInFlight.current.has(expectedSessionId)) return;
    const base = serverBaseBySession.current.get(expectedSessionId) ?? {
      version: currentReview.version,
      minutes: currentReview.minutes,
    };
    if (!base.minutes) return;
    const submittedSnapshot = snapshotMinutes({
      ...draft,
      templateId: base.minutes.templateId,
      templateVersion: base.minutes.templateVersion,
    });
    if (sameMinutes(submittedSnapshot, base.minutes)) return;

    minutesSaveInFlight.current.add(expectedSessionId);
    setMinutesMutationState(expectedSessionId, { sessionId: expectedSessionId, status: 'saving', message: '저장 중…' });
    try {
      const result = await updateReviewMinutes(expectedSessionId, base.version, submittedSnapshot);
      rememberMinutesBase(expectedSessionId, result.version, result.minutes);
      try {
        const latest = await getReviewSession(expectedSessionId);
        const applied = activeSessionId.current === expectedSessionId && applySnapshot(latest);
        if (!applied && latest.version >= result.version) rememberMinutesBase(expectedSessionId, latest.version, latest.minutes);
        const latestBase = serverBaseBySession.current.get(expectedSessionId);
        const currentDraft = minutesDraftBySession.current.has(expectedSessionId)
          ? minutesDraftBySession.current.get(expectedSessionId) : latestBase?.minutes;
        if (sameMinutes(currentDraft, latestBase?.minutes)) {
          setCompletionNotice({ sessionId: expectedSessionId, message: '회의록이 저장되었습니다.' });
          setMinutesMutationState(expectedSessionId, {
            sessionId: expectedSessionId,
            status: 'saved',
            message: '저장 완료',
          });
        } else {
          setMinutesMutationState(expectedSessionId, {
            sessionId: expectedSessionId,
            status: 'error',
            message: '최신 회의록이 반영되었습니다. 수정 내용은 유지했으며 확인 후 다시 저장해 주세요.',
          });
        }
      } catch (error) {
        recordReviewFailure(expectedSessionId, error);
        const missingOrInactive = error instanceof ReviewApiError && (error.code === 'SESSION_NOT_FOUND' || error.code === 'SESSION_NOT_IN_REVIEW');
        setMinutesMutationState(expectedSessionId, {
          sessionId: expectedSessionId,
          status: missingOrInactive ? 'error' : 'saved',
          message: error instanceof ReviewApiError && error.code === 'SESSION_NOT_FOUND'
            ? '회의를 찾을 수 없습니다. 새 회의를 시작해 주세요.'
            : error instanceof ReviewApiError && error.code === 'SESSION_NOT_IN_REVIEW'
              ? '회의록은 저장되었지만 세션이 Review 상태가 아닙니다. 처리 상태를 확인해 주세요.'
              : '저장 완료. 최신 회의 정보를 확인하지 못했습니다. 화면에서 다시 확인해 주세요.',
          ...(!missingOrInactive ? { recoverySnapshot: snapshotMinutes(submittedSnapshot) } : {}),
        });
      }
    } catch (error) {
      if (error instanceof ReviewApiError && (error.code === 'SESSION_NOT_FOUND' || error.apiCode === 'SESSION_NOT_FOUND')) {
        recordReviewFailure(expectedSessionId, error);
        setMinutesMutationState(expectedSessionId, {
          sessionId: expectedSessionId,
          status: 'error',
          message: '회의를 찾을 수 없습니다. 새 회의를 시작해 주세요.',
        });
      } else if (error instanceof ReviewApiError && error.code === 'SESSION_NOT_IN_REVIEW') {
        recordReviewFailure(expectedSessionId, error);
        setMinutesMutationState(expectedSessionId, {
          sessionId: expectedSessionId,
          status: 'error',
          message: '이 회의는 더 이상 Review 상태가 아닙니다. 처리 상태를 확인해 주세요.',
        });
      } else if (error instanceof ReviewApiError
        && (error.status === 412 || error.apiCode === 'SESSION_STATE_CONFLICT' || error.outcomeUnknown)) {
        await reconcileMinutes(submittedSnapshot, expectedSessionId, error.status === 412 || error.apiCode === 'SESSION_STATE_CONFLICT');
      } else {
        const fieldLabels: Record<string, string> = {
          summary: '회의 요약', discussionPoints: '논의 내용', decisions: '결정 사항',
          actionItems: 'Action Items', followUps: '후속 조치', templateId: '템플릿', templateVersion: '템플릿 버전',
        };
        const invalidFields = error instanceof ReviewApiError
          ? [...new Set(error.fieldErrors.map((field) => fieldLabels[field]).filter((label): label is string => Boolean(label)))] : [];
        setMinutesMutationState(expectedSessionId, {
          sessionId: expectedSessionId,
          status: 'error',
          message: invalidFields.length > 0
            ? `저장하지 못했습니다. 다음 항목을 확인해 주세요: ${invalidFields.join(', ')}. 수정 내용은 유지됩니다.`
            : '저장하지 못했습니다. 기존 저장 내용과 수정 내용은 유지됩니다. 다시 시도해 주세요.',
        });
      }
    } finally {
      minutesSaveInFlight.current.delete(expectedSessionId);
    }
  }

  async function checkMinutesResult(expectedSessionId: string) {
    const submittedSnapshot = minutesMutationStates[expectedSessionId]?.recoverySnapshot;
    if (!submittedSnapshot || minutesSaveInFlight.current.has(expectedSessionId)) return;
    minutesSaveInFlight.current.add(expectedSessionId);
    setMinutesMutationState(expectedSessionId, { sessionId: expectedSessionId, status: 'saving', message: '저장 결과를 확인하는 중…' });
    try {
      await reconcileMinutes(submittedSnapshot, expectedSessionId, false);
    } finally {
      minutesSaveInFlight.current.delete(expectedSessionId);
    }
  }

  function speakerValue(speaker: ReviewSpeaker): string {
    if (Object.prototype.hasOwnProperty.call(currentDraft, speaker.speakerId)) return currentDraft[speaker.speakerId] ?? '';
    return speaker.participantId ?? '';
  }

  const submittedMappings = currentReview?.speakers.map((speaker) => ({ speakerId: speaker.speakerId, participantId: speakerValue(speaker) || null })) ?? [];
  const committedMappings = currentReview?.speakers.map((speaker) => ({ speakerId: speaker.speakerId, participantId: speaker.participantId })) ?? [];
  const draftIsDirty = submittedMappings.length > 0 && submittedMappings.some((mapping, index) => mapping.participantId !== committedMappings[index]?.participantId);
  const minutesDraftIsDirty = Boolean(currentReview && !sameMinutes(currentMinutes, minutesBaseline));
  const savingMinutes = sessionMinutesSaveState.status === 'saving';
  const currentCompletionNotice = completionNotice?.sessionId === sessionId ? completionNotice.message : '';

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
        setSaveMessage('최신 회의 정보를 불러왔습니다. 변경 내용은 유지했습니다. 확인 후 다시 저장해 주세요.');
        setRecoveryAttempt(attempt);
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

  async function saveMappings(mappingsAttempt: SpeakerMappingInput[]) {
    if (!currentReview || !providerReady || !currentReview.allowedActions.includes('UPDATE_SPEAKER_MAPPING')
      || saveInFlight.current || participants.status === 'loading') return;
    const baseline = currentReview.speakers.map((speaker) => ({ speakerId: speaker.speakerId, participantId: speaker.participantId }));
    if (mappingsAttempt.length !== baseline.length || mappingsAttempt.every((mapping, index) => mapping.participantId === baseline[index]?.participantId)) return;
    const expectedSessionId = sessionId;
    const generation = reviewRequestGeneration.current;
    const isCurrent = () => generation === reviewRequestGeneration.current && activeSessionId.current === expectedSessionId;
    saveInFlight.current = true;
    setSaving(true);
    setSaveMessage('매핑을 저장하는 중입니다.');
    const attempt = mappingsAttempt;
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
        setRecoveryAttempt(attempt);
      }
    } finally {
      if (isCurrent()) {
        saveInFlight.current = false;
        setSaving(false);
      }
    }
  }

  async function completeReview() {
    if (!currentReview || confirmInFlight.current || completingReview) return;
    if (minutesDraftIsDirty) {
      setCompletionNotice({ sessionId, message: '변경된 내용이 있으니 저장하세요.' });
      return;
    }
    if (saving || savingMinutes || draftIsDirty) {
      setCompletionNotice({ sessionId, message: saving || savingMinutes
        ? '저장 중인 변경사항이 있습니다. 저장이 끝날 때까지 기다려 주세요.'
        : '화자 매핑을 저장한 뒤 검토를 완료해 주세요.' });
      return;
    }
    if (!currentReview.allowedActions.includes('CONFIRM')) {
      setCompletionNotice({ sessionId, message: '최신 회의 정보를 확인한 뒤 다시 시도해 주세요.' });
      return;
    }
    if (!providerReady) {
      setCompletionNotice({ sessionId, message: '문서 서비스 연결을 확인한 뒤 다시 시도해 주세요.' });
      return;
    }
    setCompletionNotice(null);
    confirmInFlight.current = true;
    setCompletingReview(true);
    setSaveMessage('회의록을 확정하고 참석자에게 전달하는 중입니다.');
    const attempt = confirmAttempt.current?.version === currentReview.version
      ? confirmAttempt.current : { version: currentReview.version, key: createIdempotencyKey() };
    confirmAttempt.current = attempt;
    let confirmed = false;
    try {
      let confirmedVersion: number;
      try {
        confirmedVersion = await confirmReview(sessionId, attempt.version, attempt.key);
      } catch (error) {
        if (!(error instanceof PublishApiError) || !error.outcomeUnknown) throw error;
        const latest = await getPublishSnapshot(sessionId);
        if (latest.status === 'CONFIRMED' && latest.allowedActions.includes('PUBLISH')) confirmedVersion = latest.version;
        else if (latest.status === 'REVIEW') confirmedVersion = await confirmReview(sessionId, attempt.version, attempt.key);
        else throw new Error('회의 상태가 변경되어 완료할 수 없습니다.');
      }
      confirmed = true;
      confirmAttempt.current = null;
      await publishSession(sessionId, confirmedVersion, createIdempotencyKey(), currentReview.participantIds,
        config.data?.notification?.enabled === true);
      navigate(`/meetings/${encodeURIComponent(sessionId)}/complete`, { replace: true });
    } catch (error) {
      if (confirmed || (error instanceof PublishApiError && error.outcomeUnknown)) {
        navigate(`/meetings/${encodeURIComponent(sessionId)}/complete`, { replace: true });
      } else {
        setSaveMessage('회의록을 확정하거나 전달하지 못했습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.');
      }
    } finally {
      confirmInFlight.current = false;
      setCompletingReview(false);
    }
  }

  return (
    <main className="review-speaker-page page-scroll-layout">
      <div className="page-fixed-heading">
      <MeetingListBackLink />
      <header className="review-speaker-heading">
        <h1><svg className="workflow-heading-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 4h10l4 4v12H5zM14 4v5h5M8 13h8M8 17h6" /></svg>회의록 검토</h1>
      </header>
      </div>

      <div className="page-scroll-content">
      {reviewIsLoading && regenerationResume?.sessionId !== sessionId && <p role="status">회의의 화자 정보를 불러오는 중입니다.</p>}
      {regenerationResume?.sessionId === sessionId && <section className="review-speaker-message" role="status" aria-live="polite">
        <h2>Minutes 재생성 진행 중</h2>
        <p>처리 중인 Minutes와 Transcript 내용은 아직 표시하지 않습니다. 완료되면 최신 회의 정보를 불러옵니다.</p>
        {regenerationResume.progress !== null && <progress aria-label="Minutes 재생성 진행" value={regenerationResume.progress} max={100} />}
      </section>}
      {regenerationResumeMessage?.sessionId === sessionId && <p role="status" aria-live="polite">{regenerationResumeMessage.message}</p>}
      {reviewHasError && reviewFailure?.sessionId === sessionId && reviewFailure.code === 'SESSION_NOT_FOUND' && <section className="review-speaker-message" role="alert">
        <h2>회의를 찾을 수 없습니다</h2>
        <p>회의 처리 정보를 사용할 수 없습니다. 새 회의를 시작해 주세요.</p>
        <Link className="button button-primary" to="/meetings/new">새 회의 시작</Link>
      </section>}
      {reviewHasError && reviewFailure?.sessionId === sessionId && reviewFailure.code === 'SESSION_NOT_IN_REVIEW' && <section className="review-speaker-message" role="alert">
        <h2>Review 상태가 아닙니다</h2>
        <p>최신 회의 상태를 확인해 주세요.</p>
        <Link className="button button-primary" to={`/meetings/${encodeURIComponent(sessionId)}/processing`}>처리 상태 확인</Link>
      </section>}
      {reviewHasError && (!reviewFailure || reviewFailure.sessionId !== sessionId || reviewFailure.code === 'OTHER') && <AsyncState state={{
        status: 'error',
        title: '회의 화자 정보를 불러오지 못했습니다.',
        error: normalizeApiError({ error: { code: 'REQUEST_FAILED' } }),
        description: '회의 화자 정보를 불러오지 못했습니다. 다시 시도해 주세요.',
        onRetry: () => void loadReview(),
        retryLabel: '회의 정보 다시 불러오기',
      }} renderReady={() => null} />}

      {currentReview && <>
        <ReviewMinutesEditor
          key={sessionId}
          sessionId={sessionId}
          minutes={currentMinutes}
          baseline={minutesBaseline}
          participants={availableParticipants}
          ownerState={minutesOwnerState}
          saveState={sessionMinutesSaveState}
          saveBlockedMessage={minutesSaveBlockedMessage}
          disabled={regenerationResume?.sessionId === sessionId}
          actionContent={<button className="button button-primary" type="button" disabled={completingReview} aria-busy={completingReview} onClick={() => void completeReview()}>
            {completingReview ? '문서 저장 및 참석자 알림 처리 중…' : '문서 저장 및 참석자 알림 발송'}
          </button>}
          onSave={onMinutesSave ?? saveMinutes}
          onChange={updateMinutesDraft}
          footerContent={<>
            <details className="review-speaker-card review-speaker-details">
              <summary>화자 매핑 · 참석자 연결</summary>
              <div className="review-speaker-card-heading">
                <div><p>Speaker A/B를 실제 참석자와 연결하면 해당 이름이 전사 원문에 표시됩니다.</p></div>
                <div className="review-speaker-actions">
                  <button className="button button-secondary" type="button" onClick={() => setTranscriptOpen(true)}>전사 원문 보기</button>
                </div>
              </div>
              {participants.status === 'loading' && <AsyncState state={{ status: 'loading', label: '참석자 목록을 불러오는 중입니다.' }} renderReady={() => null} />}
              {participants.status === 'error' && <AsyncState state={{
                status: 'error', title: '참석자 목록을 불러오지 못했습니다.',
                error: normalizeApiError({ error: { code: 'REQUEST_FAILED' } }),
                description: '참석자 목록을 불러오지 못했습니다. 기존 선택은 유지됩니다.',
                onRetry: () => void loadRoster(), retryLabel: retryingRoster ? '불러오는 중…' : '참석자 목록 다시 불러오기',
              }} renderReady={() => null} />}
              {needsSettings && <div className="review-speaker-message" role="status"><p>참석자 매핑을 사용하려면 문서 서비스 연결이 필요합니다.</p></div>}
              {config.status === 'error' && <div className="review-speaker-message" role="alert"><p>문서 연결 상태를 확인할 수 없습니다. 기존 선택은 유지됩니다.</p><button className="button button-secondary" type="button" onClick={() => void loadConfig()}>설정 상태 다시 확인</button></div>}
              {participants.status === 'ready' && participants.data?.length === 0 && <AsyncState state={{
                status: 'empty', title: '선택할 참석자가 없습니다.', description: '참석자를 먼저 추가해 주세요.',
                action: <Link className="button button-secondary" to="/participants" state={{ from: location.pathname }}>참석자 관리</Link>,
              }} renderReady={() => null} />}
              {currentReview.speakers.length === 0 && <p role="status">감지된 화자가 없습니다.</p>}
              <div className="review-speaker-list">
                {currentReview.speakers.map((speaker) => {
                  const value = speakerValue(speaker);
                  const selectedIsInList = availableParticipants.some(({ id }) => id === value);
                  const fallbackLabel = participants.status === 'loading' ? '참석자 목록 확인 중' : '기존 매핑 유지';
                  return <div className="review-speaker-row" key={speaker.speakerId}>
                    <label htmlFor={`speaker-${speaker.speakerId}`}>{speaker.label}</label>
                    {providerReady ? <div className="meeting-select-control review-select-control"><select
                      id={`speaker-${speaker.speakerId}`}
                      aria-label={`${speaker.label} 연결할 참석자`}
                      value={value}
                      disabled={regenerationResume?.sessionId === sessionId || saving || participants.status === 'loading' || availableParticipants.length === 0}
                      onChange={(event) => {
                        const participantId = event.target.value || null;
                        setCompletionNotice(null);
                        onChange(sessionId, speaker.speakerId, participantId);
                        const attempt = submittedMappings.map((mapping) => mapping.speakerId === speaker.speakerId
                          ? { ...mapping, participantId } : mapping);
                        void saveMappings(attempt);
                      }}
                    >
                      <option value="">참가자 선택</option>
                      {value && !selectedIsInList && <option value={value}>{fallbackLabel}</option>}
                      {availableParticipants.map(({ id, name }) => <option key={id} value={id}>{name}</option>)}
                    </select><svg className="meeting-dropdown-chevron" viewBox="0 0 20 20" aria-hidden="true"><path d="m5 7.5 5 5 5-5" /></svg></div> : <p className="review-speaker-current">{speaker.participantId ? (availableParticipants.find(({ id }) => id === value)?.name ?? fallbackLabel) : '참가자 선택'}</p>}
                  </div>;
                })}
              </div>
              {(currentReview.allowedActions.includes('UPDATE_SPEAKER_MAPPING') || saveMessage) && <div className="review-save-controls">
                {saveMessage && <p role="status">{saveMessage}</p>}
                {recoveryAttempt && <button className="button button-secondary" type="button" disabled={saving} onClick={() => void saveMappings(recoveryAttempt)}>매핑 다시 저장</button>}
              </div>}
            </details>
            {sessionMinutesMutationState.recoverySnapshot && <button className="button button-secondary" type="button"
              disabled={sessionMinutesMutationState.status === 'saving'} onClick={() => void checkMinutesResult(sessionId)}>
              저장 결과 다시 확인
            </button>}
          </>}
        />
        <ReviewTranscriptDrawer
          open={transcriptOpen}
          segments={currentReview.transcript}
          speakers={currentReview.speakers}
          participants={participants.data ?? []}
          selectedParticipantId={(speaker) => speakerValue(speaker) || null}
          onClose={() => setTranscriptOpen(false)}
        />
      </>}
      {currentCompletionNotice && <div className="review-notice-backdrop" onMouseDown={(event) => {
        if (event.target === event.currentTarget) setCompletionNotice(null);
      }}>
        <section className="review-notice-dialog" role="dialog" aria-modal="true"
          aria-labelledby="review-notice-title" aria-describedby="review-notice-message">
          <div className="review-notice-heading">
            <h2 id="review-notice-title">{currentCompletionNotice === '회의록이 저장되었습니다.' ? '회의록 저장 완료' : '확인 필요'}</h2>
            <button className="review-notice-close" type="button" aria-label="닫기" onClick={() => setCompletionNotice(null)}>×</button>
          </div>
          <p id="review-notice-message" role="alert" aria-live="assertive">{currentCompletionNotice}</p>
          <div className="review-notice-actions">
            <button className="button button-primary" type="button" onClick={() => setCompletionNotice(null)}>확인</button>
          </div>
        </section>
      </div>}
      </div>
    </main>
  );
}

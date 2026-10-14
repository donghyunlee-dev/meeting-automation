import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router';
import { getDocumentAppConfig, listMeetingParticipants, type DocumentAppConfig } from './newMeetingApi';
import type { Participant } from './participantsApi';
import { getReviewSession, ReviewApiError, updateReviewMinutes, updateSpeakerMappings, type ReviewSession, type ReviewSpeaker, type SpeakerMappingInput, type StructuredMinutes } from './reviewApi';
import { ReviewMinutesEditor, type MinutesSaveState } from './ReviewMinutesEditor';
import { ReviewTranscriptDrawer } from './ReviewTranscriptDrawer';
import { MinutesRegenerationControl } from './MinutesRegenerationControl';
import { getMinutesRegenerationSnapshot, type MinutesRegenerationSnapshot } from './minutesRegenerationApi';
import { ProcessingPoller } from './processingPoller';

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
  const [review, setReview] = useState<ReviewResource>(() => ({ status: 'loading', requestSessionId: sessionId }));
  const [participants, setParticipants] = useState<Resource<Participant[]>>({ status: 'loading' });
  const [config, setConfig] = useState<Resource<DocumentAppConfig>>({ status: 'loading' });
  const [retryingRoster, setRetryingRoster] = useState(false);
  const [minutesDraftStore, setMinutesDraftStore] = useState<MinutesDraftStore>(() => ({ drafts: {}, baselines: {}, baseVersions: {}, saveStatuses: {} }));
  const [minutesMutationStates, setMinutesMutationStates] = useState<Record<string, MinutesMutationState>>({});
  const [reviewFailure, setReviewFailure] = useState<{ sessionId: string; code: ReviewFailureCode } | null>(null);
  const [transcriptOpen, setTranscriptOpen] = useState(false);
  const [regenerationProcessing, setRegenerationProcessing] = useState<{ sessionId: string; processing: boolean }>(() => ({ sessionId, processing: false }));
  const [regenerationControlUsed, setRegenerationControlUsed] = useState<{ sessionId: string; used: boolean }>(() => ({ sessionId, used: false }));
  const [regenerationResume, setRegenerationResume] = useState<{ sessionId: string; progress: number | null } | null>(null);
  const [regenerationResumeMessage, setRegenerationResumeMessage] = useState<{ sessionId: string; message: string } | null>(null);
  const [saveState, setSaveState] = useState<{ sessionId: string; saving: boolean; message: string; recoveryAttempt: SpeakerMappingInput[] | null }>({
    sessionId: '', saving: false, message: '', recoveryAttempt: null,
  });
  const reviewRequestGeneration = useRef(0);
  const saveInFlight = useRef(false);
  const minutesSaveInFlight = useRef(new Set<string>());
  const activeSessionId = useRef(sessionId);
  const latestReview = useRef<{ sessionId: string; version: number } | null>(null);
  const serverBaseBySession = useRef(new Map<string, { version: number; minutes: StructuredMinutes | null }>());
  const minutesDraftBySession = useRef(new Map<string, StructuredMinutes | null>());
  const currentDraft = drafts[sessionId] ?? {};
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

  const reportRegenerationProcessing = useCallback((processing: boolean) => {
    setRegenerationProcessing({ sessionId, processing });
    if (processing) setRegenerationControlUsed({ sessionId, used: true });
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
    setMinutesDraftStore((current) => ({ ...current, drafts: { ...current.drafts, [sessionId]: draft } }));
  }, [sessionId]);

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

  async function prepareRegenerationSnapshot(): Promise<ReviewSession | null> {
    const expectedSessionId = sessionId;
    const visibleReview = review.data;
    if (!visibleReview || visibleReview.sessionId !== expectedSessionId || activeSessionId.current !== expectedSessionId) return null;
    const draft = minutesDraftBySession.current.has(expectedSessionId)
      ? minutesDraftBySession.current.get(expectedSessionId) ?? null
      : currentMinutes;
    const base = serverBaseBySession.current.get(expectedSessionId) ?? { version: visibleReview.version, minutes: visibleReview.minutes };
    if (!sameMinutes(draft, base.minutes)) {
      if (!draft) return null;
      await saveMinutes(expectedSessionId, draft);
      const draftAfterSave = minutesDraftBySession.current.has(expectedSessionId)
        ? minutesDraftBySession.current.get(expectedSessionId) ?? null : draft;
      if (!sameMinutes(draftAfterSave, draft)) return null;
      const savedBase = serverBaseBySession.current.get(expectedSessionId);
      if (!savedBase || !sameMinutes(savedBase.minutes, draft)) return null;
    }
    try {
      const latest = await getReviewSession(expectedSessionId);
      if (latest.version < (serverBaseBySession.current.get(expectedSessionId)?.version ?? visibleReview.version)) return null;
      if (!sameMinutes(latest.minutes, draft)) return null;
      if (!applySnapshot(latest)) return null;
      return latest;
    } catch (error) {
      recordReviewFailure(expectedSessionId, error);
      return null;
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
      {reviewHasError && (!reviewFailure || reviewFailure.sessionId !== sessionId || reviewFailure.code === 'OTHER') && <section className="review-speaker-message" role="alert">
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
          saveBlockedMessage={minutesSaveBlockedMessage}
          disabled={regenerationProcessing.sessionId === sessionId && regenerationProcessing.processing}
          onSave={onMinutesSave ?? saveMinutes}
          onChange={updateMinutesDraft}
        />
        {(currentReview.allowedActions.includes('REGENERATE_MINUTES') || (regenerationControlUsed.sessionId === sessionId && regenerationControlUsed.used)) && <MinutesRegenerationControl
          session={currentReview}
          blocked={(regenerationProcessing.sessionId === sessionId && regenerationProcessing.processing) || saving || Boolean(sessionMinutesMutationState.status === 'saving')}
          prepareSnapshot={prepareRegenerationSnapshot}
          applySnapshot={applyRegenerationSnapshot}
          onProcessingChange={reportRegenerationProcessing}
        />}
        {sessionMinutesMutationState.recoverySnapshot && <button className="button button-secondary" type="button"
          disabled={sessionMinutesMutationState.status === 'saving'} onClick={() => void checkMinutesResult(sessionId)}>
          저장 결과 다시 확인
        </button>}
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
                disabled={(regenerationProcessing.sessionId === sessionId && regenerationProcessing.processing) || saving || participants.status === 'loading' || availableParticipants.length === 0}
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
          {currentReview.allowedActions.includes('UPDATE_SPEAKER_MAPPING') && <button className="button button-primary" type="button" disabled={!canSave || (regenerationProcessing.sessionId === sessionId && regenerationProcessing.processing)} onClick={() => void saveMappings()}>
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

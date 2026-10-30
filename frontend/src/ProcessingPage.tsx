import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { audioDownloadUrl, finalizeProcessingFailure, getProcessingStatus, ProcessingApiError, retryProcessing, type ProcessingAction, type ProcessingSnapshot, type ProcessingStage } from './processingApi';
import { ProcessingPoller } from './processingPoller';
import { AsyncState } from './components/async-state/AsyncState';
import { normalizeApiError } from './lib/api/normalizeApiError';

const stageNames = ['오디오 준비', '음성 인식', '화자 분석', '회의록 생성'] as const;
const stageLabels: Record<ProcessingStage, string> = { AUDIO_ASSEMBLY: '오디오 준비', TRANSCRIPTION: '음성 인식', DIARIZATION: '화자 분석', MINUTES_GENERATION: '회의록 생성', DRAFT_READY: '회의록 생성', DRAFT_REGENERATION: '회의록 재생성' };
const stageIndex: Record<ProcessingStage, number> = { AUDIO_ASSEMBLY: 0, TRANSCRIPTION: 1, DIARIZATION: 2, MINUTES_GENERATION: 3, DRAFT_READY: 3, DRAFT_REGENERATION: 3 };
const failureMessages: Record<ProcessingStage, string> = {
  AUDIO_ASSEMBLY: '오디오를 준비하는 중 문제가 발생했습니다.', TRANSCRIPTION: '음성을 변환하는 중 문제가 발생했습니다.',
  DIARIZATION: '화자를 분석하는 중 문제가 발생했습니다.', MINUTES_GENERATION: '회의록을 생성하는 중 문제가 발생했습니다.',
  DRAFT_READY: '회의록을 생성하는 중 문제가 발생했습니다.', DRAFT_REGENERATION: '회의록을 재생성하는 중 문제가 발생했습니다.',
};
const failureDetails: Record<string, string> = {
  AUDIO_ASSEMBLY_FAILED: '녹음 파일 조각을 하나의 오디오로 준비하지 못했습니다. 다시 시도해 주세요.',
  AUDIO_STORAGE_FAILED: '오디오 파일을 안전하게 보관하지 못했습니다. 다시 시도해도 해결되지 않으면 새 회의를 녹음해 주세요.',
  TRANSCRIPTION_AUTHENTICATION_FAILED: '음성 인식 서비스 연결 설정을 확인해 주세요.',
  TRANSCRIPTION_CONFIGURATION_ERROR: '음성 인식 서비스 설정에 문제가 있습니다. 설정을 확인한 뒤 다시 시도해 주세요.',
  TRANSCRIPTION_INVALID_AUDIO: '녹음 파일을 음성 인식 서비스에서 읽지 못했습니다. 다시 시도해 주세요.',
  TRANSCRIPTION_INVALID_RESPONSE: '음성 인식 결과를 읽을 수 없었습니다. 다시 시도해 주세요.',
  TRANSCRIPTION_PROCESSING_FAILED: '음성 인식 서비스가 처리를 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  DIARIZATION_FAILED: '화자 분석을 완료하지 못했습니다. 다시 시도해 주세요.',
  DIARIZATION_RESULT_SAVE_FAILED: '화자 분석 결과를 저장하지 못했습니다. 다시 시도해 주세요.',
  MINUTES_AUTHENTICATION_FAILED: '회의록 작성 서비스 연결 설정을 확인해 주세요.',
  MINUTES_CONFIGURATION_ERROR: '회의록 작성 서비스 설정에 문제가 있습니다. 설정을 확인한 뒤 다시 시도해 주세요.',
  MINUTES_PROCESSING_FAILED: '회의록 작성 서비스가 처리를 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  MINUTES_RESULT_SAVE_FAILED: '작성한 회의록을 저장하지 못했습니다. 다시 시도해 주세요.',
  TRANSCRIPTION_RESULT_NOT_AVAILABLE: '음성 인식 결과를 찾지 못했습니다. 다시 시도해 주세요.',
  PROCESSING_RUNNER_FAILED: '회의 처리 작업이 중단되었습니다. 다시 시도해 주세요.',
  PROCESSING_FAILED: '회의 처리 중 일시적인 문제가 발생했습니다. 다시 시도해 주세요.',
};
const DOWNLOAD_RETURN_KEY = 'processing-download-return';
const SAFE_NETWORK_ERROR = normalizeApiError({ error: { code: 'NETWORK_ERROR' } });

function newKey(): string { return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`; }

export function ProcessingPage() {
  const { sessionId = '' } = useParams();
  const navigate = useNavigate();
  const [snapshot, setSnapshot] = useState<ProcessingSnapshot | null>(null);
  const [failure, setFailure] = useState<'network' | 'missing' | 'invalid' | 'session-mismatch' | 'unavailable' | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState('');
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [confirmDownload, setConfirmDownload] = useState(false);
  const [downloadReadySessionId, setDownloadReadySessionId] = useState<string | null>(null);
  const downloadReady = downloadReadySessionId === sessionId;
  const [clockNow, setClockNow] = useState(() => Date.now());
  const [transcriptionElapsedSeconds, setTranscriptionElapsedSeconds] = useState(0);
  const navigated = useRef(false);
  const inFlight = useRef(false);
  const snapshotRef = useRef<ProcessingSnapshot | null>(null);
  const discardDialogRef = useRef<HTMLElement | null>(null);
  const downloadDialogRef = useRef<HTMLElement | null>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const downloadLinkRef = useRef<HTMLAnchorElement | null>(null);
  const reconcilingDownloadRef = useRef(false);

  useEffect(() => { snapshotRef.current = snapshot; }, [snapshot]);

  const applySnapshot = useCallback((next: ProcessingSnapshot) => {
    if (next.sessionId !== sessionId) { setFailure('session-mismatch'); return; }
    if (snapshotRef.current && next.version < snapshotRef.current.version) return;
    const wasTranscribing = snapshotRef.current?.status === 'PROCESSING'
      && snapshotRef.current.processing.stage === 'TRANSCRIPTION';
    if (!wasTranscribing && next.status === 'PROCESSING' && next.processing.stage === 'TRANSCRIPTION') {
      setTranscriptionElapsedSeconds(0);
    }
    snapshotRef.current = next;
    setSnapshot(next);
    setDownloadReadySessionId((readySessionId) => readySessionId === sessionId
      && next.status === 'PROCESSING_FAILED'
      && next.processing.audioAvailable === true
      && next.allowedActions.includes('DOWNLOAD_AUDIO') ? sessionId : null);
    if (next.status !== 'PROCESSING_FAILED' || next.processing.audioAvailable !== true || !next.allowedActions.includes('DOWNLOAD_AUDIO')) {
      setConfirmDownload(false);
    }
    setFailure(null);
    if (next.status === 'REVIEW' && !navigated.current) {
      navigated.current = true;
      navigate(`/meetings/${encodeURIComponent(sessionId)}/review`, { replace: true });
    }
  }, [navigate, sessionId]);

  const refresh = useCallback(async () => {
    try { applySnapshot(await getProcessingStatus(sessionId)); }
    catch (error) {
      const mapped = error instanceof ProcessingApiError ? error : new ProcessingApiError('NETWORK_ERROR');
      setFailure(mapped.code === 'SESSION_NOT_FOUND' ? 'missing' : mapped.code === 'INVALID_RESPONSE' ? 'invalid' : mapped.code === 'HTTP_ERROR' && mapped.status !== undefined && mapped.status < 500 && mapped.status !== 408 && mapped.status !== 429 ? 'unavailable' : 'network');
    }
  }, [applySnapshot, sessionId]);

  useEffect(() => {
    let live = true;
    const poller = new ProcessingPoller({
      sessionId, getStatus: (id, signal) => getProcessingStatus(id, signal),
      onSnapshot: (next) => { if (live) applySnapshot(next); },
      onError: (error) => { if (!live) return; setFailure(error.code === 'SESSION_NOT_FOUND' ? 'missing' : error.code === 'INVALID_RESPONSE' ? 'invalid' : error.code === 'HTTP_ERROR' && error.status !== undefined && error.status < 500 && error.status !== 408 && error.status !== 429 ? 'unavailable' : 'network'); },
    });
    poller.start();
    const onVisibilityChange = () => {
      if (document.visibilityState !== 'visible') return;
      if (sessionStorage.getItem(DOWNLOAD_RETURN_KEY) === sessionId) {
        sessionStorage.removeItem(DOWNLOAD_RETURN_KEY);
        returnFocusRef.current = downloadLinkRef.current;
        setConfirmDownload(false);
        reconcilingDownloadRef.current = true;
        setBusy(true);
        setActionError('');
        void getProcessingStatus(sessionId).then((latest) => {
          applySnapshot(latest);
          if (latest.status === 'PROCESSING_FAILED' && latest.processing.audioAvailable === true && latest.allowedActions.includes('DOWNLOAD_AUDIO')) {
            setConfirmDownload(true);
          } else {
            setActionError('\uC624\uB514\uC624\uB97C \uC0AC\uC6A9\uD560 \uC218 \uC5C6\uAC70\uB098 \uBCF4\uAD00 \uAE30\uD55C\uC774 \uC9C0\uB0AC\uC2B5\uB2C8\uB2E4. \uCD5C\uC2E0 \uC0C1\uD0DC\uB97C \uD655\uC778\uD574 \uC8FC\uC138\uC694.');
          }
        }).catch(() => {
          setFailure('network');
          setActionError('\uC624\uB514\uC624 \uC0C1\uD0DC\uB97C \uD655\uC778\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4. \uC5F0\uACB0\uC744 \uD655\uC778\uD558\uACE0 \uB2E4\uC2DC \uD655\uC778\uD574 \uC8FC\uC138\uC694.');
        }).finally(() => { reconcilingDownloadRef.current = false; setBusy(false); });
      } else if (reconcilingDownloadRef.current) return;
      else if (snapshotRef.current?.status === 'PROCESSING_FAILED') void refresh(); else poller.refreshNow();
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    window.addEventListener('focus', onVisibilityChange);
    return () => { live = false; document.removeEventListener('visibilitychange', onVisibilityChange); window.removeEventListener('focus', onVisibilityChange); poller.stop(); };
  }, [applySnapshot, refresh, sessionId]);

  useEffect(() => {
    const expiresAt = snapshot?.processing.audioExpiresAt;
    if (!expiresAt || snapshot?.status !== 'PROCESSING_FAILED') return;
    const expiry = Date.parse(expiresAt);
    if (!Number.isFinite(expiry)) return;
    const update = () => setClockNow(Date.now());
    const timer = window.setInterval(update, 60_000);
    const expiryRefresh = window.setTimeout(() => { void refresh(); }, Math.max(0, expiry - Date.now()));
    return () => { window.clearInterval(timer); window.clearTimeout(expiryRefresh); };
  }, [refresh, snapshot?.processing.audioExpiresAt, snapshot?.status]);

  const expiryTime = snapshot?.processing.audioExpiresAt ? Date.parse(snapshot.processing.audioExpiresAt) : Number.NaN;
  const expiresIn = snapshot?.status === 'PROCESSING_FAILED' && Number.isFinite(expiryTime) ? Math.max(0, Math.ceil((expiryTime - clockNow) / 60_000)) : null;
  const isTranscribing = snapshot?.status === 'PROCESSING' && snapshot.processing.stage === 'TRANSCRIPTION';

  useEffect(() => {
    if (!isTranscribing) return;
    let elapsed = 0;
    const timer = window.setInterval(() => setTranscriptionElapsedSeconds(++elapsed), 1_000);
    return () => window.clearInterval(timer);
  }, [isTranscribing]);

  useEffect(() => {
    const dialog = confirmDiscard ? discardDialogRef.current : confirmDownload ? downloadDialogRef.current : null;
    if (dialog) {
      dialog.querySelector<HTMLElement>('button')?.focus();
      const onKeyDown = (event: KeyboardEvent) => {
        if (event.key !== 'Escape') return;
        event.preventDefault();
        setConfirmDiscard(false); setConfirmDownload(false);
      };
      document.addEventListener('keydown', onKeyDown);
      return () => document.removeEventListener('keydown', onKeyDown);
    }
    const target = returnFocusRef.current;
    if (target?.isConnected) target.focus();
    returnFocusRef.current = null;
  }, [confirmDiscard, confirmDownload]);

  const allowed = (action: ProcessingAction) => {
    if (failure !== null) return false;
    if (snapshot?.allowedActions.includes(action) !== true) return false;
    if (action === 'DOWNLOAD_AUDIO') return snapshot?.processing.audioAvailable === true;
    return true;
  };
  const mutate = async (kind: 'retry' | 'finalize', disposition?: 'DOWNLOADED' | 'DISCARDED') => {
    if (!snapshot || inFlight.current) return;
    inFlight.current = true;
    setBusy(true); setActionError('');
    const key = newKey();
    try {
      if (kind === 'retry') await retryProcessing(sessionId, snapshot.version, key);
      else await finalizeProcessingFailure(sessionId, snapshot.version, disposition!, key);
      setConfirmDiscard(false); setConfirmDownload(false);
      await refresh();
    } catch (error) {
      const mapped = error instanceof ProcessingApiError ? error : new ProcessingApiError('NETWORK_ERROR', undefined, true);
      setActionError(mapped.code === 'DOCUMENT_FAILED' ? '실패 기록을 저장하지 못했습니다. 상태를 확인한 뒤 다시 시도해 주세요.' : mapped.code === 'AUDIO_NOT_AVAILABLE' ? '다시 처리할 오디오를 찾지 못했습니다. 보관 기한이 지났을 수 있습니다.' : mapped.code === 'SESSION_STATE_CONFLICT' ? '현재 회의 상태에서는 다시 시도할 수 없습니다. 최신 상태를 확인해 주세요.' : '요청 결과를 확인하지 못했습니다. 최신 상태를 다시 확인합니다.');
      await refresh();
    } finally { inFlight.current = false; setBusy(false); }
  };
  const checkDownloadAvailability = async () => {
    setBusy(true); setActionError(''); setDownloadReadySessionId(null);
    try {
      const latest = await getProcessingStatus(sessionId);
      applySnapshot(latest);
      if (latest.status === 'PROCESSING_FAILED' && latest.processing.audioAvailable === true && latest.allowedActions.includes('DOWNLOAD_AUDIO')) setDownloadReadySessionId(sessionId);
      else setActionError('오디오를 사용할 수 없거나 보관 기한이 지났습니다. 최신 상태를 확인해 주세요.');
    } catch (error) {
      const mapped = error instanceof ProcessingApiError ? error : new ProcessingApiError('NETWORK_ERROR');
      setFailure(mapped.code === 'SESSION_NOT_FOUND' ? 'missing' : mapped.code === 'INVALID_RESPONSE' ? 'invalid' : 'network');
      setActionError('오디오 상태를 확인하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.');
    } finally { setBusy(false); }
  };

  const reportedStage = snapshot?.processing?.stage ? stageIndex[snapshot.processing.stage] : -1;
  const progress = snapshot?.processing?.progressPercent;
  const validProgress = typeof progress === 'number' && Number.isFinite(progress) && progress >= 0 && progress <= 100;
  const isProcessing = snapshot?.status === 'PROCESSING';
  // The API percentage marks workflow milestones; it is not audio throughput.
  const activeStage = isProcessing && reportedStage === 2 && validProgress && progress === 100 ? 3 : reportedStage;
  const activeStep = activeStage >= 0 ? activeStage + 1 : null;
  const stageForFailure = snapshot?.processing?.stage;
  const failureDetail = snapshot?.processing.errorCode ? failureDetails[snapshot.processing.errorCode] : undefined;

  return (
    <main className="processing-page page-scroll-layout" aria-labelledby="processing-title">
      <header className="processing-heading"><h1 id="processing-title"><svg className="workflow-heading-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h10M4 18h7" /><circle cx="18" cy="16" r="3" /></svg>회의 처리</h1></header>
      <div className="page-scroll-content">
      {!snapshot && failure === null && <AsyncState state={{ status: 'loading', label: '회의 처리 상태를 확인하고 있습니다.' }} renderReady={() => null} />}
      {failure === 'network' && !snapshot && <AsyncState state={{
        status: 'error',
        title: '처리 상태를 불러오지 못했습니다.',
        error: SAFE_NETWORK_ERROR,
        description: '연결을 확인해 주세요. 처리 상태는 자동으로 다시 확인합니다.',
        onRetry: () => void refresh(),
      }} renderReady={() => null} />}
      {failure === 'network' && snapshot && <AsyncState state={{
        status: 'ready', data: snapshot, warning: SAFE_NETWORK_ERROR, onRetry: () => void refresh(),
      }} renderReady={() => null} />}
      {failure === 'missing' && <section className="processing-message" role="alert"><h2>회의를 찾을 수 없습니다</h2><p>서버에서 회의 처리 정보를 찾을 수 없어 다시 변환하거나 오디오를 복구할 수 없습니다. 새 회의를 시작해 주세요.</p><Link className="button button-primary" to="/meetings/new">새 회의 시작</Link></section>}
      {failure === 'session-mismatch' && <p role="alert" className="processing-message">회의 처리 정보를 확인할 수 없습니다. 다시 시도해 주세요.</p>}
      {failure === 'invalid' && <p role="alert" className="processing-message">회의 처리 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.</p>}
      {failure === 'unavailable' && <p role="alert" className="processing-message">회의 처리 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.</p>}
      {snapshot?.status === 'PROCESSING_FAILED' && failure === null && <section className="processing-message processing-recovery" role="region" aria-labelledby="processing-failed-title">
        <p className="visually-hidden" role="alert">{failureDetail ?? (stageForFailure ? failureMessages[stageForFailure] : '회의를 처리하는 중 문제가 발생했습니다.')}</p>
        <h2 id="processing-failed-title">처리를 완료하지 못했습니다</h2>
        <p className="processing-failure-detail">{failureDetail ?? (stageForFailure ? failureMessages[stageForFailure] : '회의를 처리하는 중 문제가 발생했습니다.')}</p>
        {stageForFailure && <p className="processing-failure-stage">실패한 단계 · {stageLabels[stageForFailure]}</p>}
        {expiresIn !== null && <p>오디오 보관 만료: {expiresIn === 0 ? '만료 시각이 지났습니다. 서버 상태를 확인하고 있습니다.' : `약 ${expiresIn}분 남았습니다.`}</p>}
        <div className="processing-recovery-actions" aria-busy={busy}>
          {allowed('RETRY_PROCESSING') && <button className="button button-primary processing-retry-button" disabled={busy} onClick={() => void mutate('retry')}>{busy ? '다시 시도 중…' : '다시 시도'}</button>}
          {allowed('DOWNLOAD_AUDIO') && !downloadReady && <button className="button button-secondary" disabled={busy} onClick={() => void checkDownloadAvailability()}>오디오 다운로드 준비</button>}
          {allowed('DOWNLOAD_AUDIO') && downloadReady && <a ref={downloadLinkRef} className="button button-secondary" href={audioDownloadUrl(sessionId)} download target="_blank" rel="noopener" onClick={() => {
            sessionStorage.setItem(DOWNLOAD_RETURN_KEY, sessionId);
          }}>원본 오디오 다운로드</a>}
          {allowed('FINALIZE_PROCESSING_FAILURE') && <button className="button button-secondary" disabled={busy} onClick={(event) => { returnFocusRef.current = event.currentTarget; setConfirmDiscard(true); }}>오디오 없이 실패 마무리</button>}
        </div>
        {actionError && <p className="processing-action-error" role="alert">{actionError}</p>}
        {confirmDownload && <section ref={downloadDialogRef} className="processing-confirmation" role="dialog" aria-labelledby="download-confirm-title" aria-describedby="download-confirm-description"><h3 id="download-confirm-title">오디오를 기기에 저장했나요?</h3><p id="download-confirm-description">저장된 것을 확인한 경우에만 다운로드 완료로 기록합니다.</p><button className="button button-primary" disabled={busy} onClick={() => void mutate('finalize', 'DOWNLOADED')}>저장했어요</button><button className="button button-secondary" onClick={() => setConfirmDownload(false)}>저장하지 못했어요</button></section>}
        {confirmDiscard && <section ref={discardDialogRef} className="processing-confirmation" role="dialog" aria-labelledby="discard-confirm-title" aria-describedby="discard-confirm-description"><h3 id="discard-confirm-title">오디오 없이 마무리</h3><p id="discard-confirm-description">오디오를 보관하지 않고 실패 회의 기록을 저장합니다. 이 선택은 되돌릴 수 없습니다.</p><button className="button button-primary" disabled={busy} onClick={() => void mutate('finalize', 'DISCARDED')}>실패 마무리</button><button className="button button-secondary" disabled={busy} onClick={() => setConfirmDiscard(false)}>취소</button></section>}
      </section>}
      {snapshot?.status === 'COMPLETED_WITH_WARNINGS' && <section className="processing-message" role="status"><h2>실패 기록을 저장했습니다</h2><p>Email 또는 Slack으로 회의가 전달되지 않았습니다.</p></section>}
      {(failure === null || failure === 'network') && snapshot?.status !== 'PROCESSING_FAILED' && snapshot?.status !== 'COMPLETED_WITH_WARNINGS' && (
        <section className="processing-card" aria-label="처리 단계"><ol className="processing-steps">{stageNames.map((name, index) => {
          const current = isProcessing && activeStage === index;
          const complete = snapshot?.status === 'REVIEW' || (isProcessing && activeStage > index);
          return <li key={name} className={current ? 'is-current' : complete ? 'is-complete' : ''} aria-current={current ? 'step' : undefined}>
            <span className="processing-step-icon" aria-hidden="true">{complete ? <svg viewBox="0 0 20 20"><path d="m4 10 4 4 8-8" /></svg> : current ? <span /> : null}</span>
            <span className="processing-step-label">{name}</span>
          </li>;
        })}</ol>{activeStep !== null ? <div className="processing-progress" aria-label="단계 기반 진행률">
          <p className="processing-current" role="status">전체 {stageNames.length}단계 중 {activeStep}단계 · {stageNames[activeStage]} 진행 중</p>
          <progress aria-label="전체 처리 단계" value={activeStep} max={stageNames.length}>{activeStep}/{stageNames.length}</progress>
          {isTranscribing && <span className="processing-elapsed-time" role="timer" aria-label={`음성 인식 경과 시간 ${formatElapsedTime(transcriptionElapsedSeconds)}`}>경과 {formatElapsedTime(transcriptionElapsedSeconds)}</span>}
        </div> : <p className="processing-current" role="status">처리 상태를 확인하고 있습니다.</p>}</section>
      )}
      </div>
    </main>
  );
}

function formatElapsedTime(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60).toString().padStart(2, '0');
  const seconds = (totalSeconds % 60).toString().padStart(2, '0');
  return `${minutes}:${seconds}`;
}

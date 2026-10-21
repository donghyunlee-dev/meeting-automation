import { useEffect, useRef, useState, type MouseEvent } from 'react';
import { Link } from 'react-router';
import { formatElapsedTime } from './recordingTime';
import type { ChunkUploadSnapshot } from './chunkUploadCoordinator';
import type { RecordingEndSnapshot } from './recordingEndCoordinator';

export type RecordingViewState = 'Ready' | 'Recording' | 'Paused';

export type RecordingSessionContext = {
  sessionId: string;
  title: string;
};

export type RecordingCallbacks = {
  onStart: () => void;
  onPause: () => void;
  onResume: () => void;
  onRequestEnd: () => void;
  onConfirmEnd: () => void;
  onCancelEnd: () => void;
};

type RecordingPageProps = RecordingSessionContext & RecordingCallbacks & {
  state: RecordingViewState;
  elapsedMs: number;
  microphoneStatus?: 'checking' | 'ready' | 'error';
  microphoneLevel?: number;
  microphoneInputDetected?: boolean;
  errorMessage?: string;
  completed?: boolean;
  endFlowFailed?: boolean;
  uploadSnapshot?: ChunkUploadSnapshot;
  endSnapshot?: RecordingEndSnapshot;
  onRetryUpload?: () => void;
  onRetryProcessing?: () => void;
  onRetryMicrophone?: () => void;
  onReturnToSetup?: () => Promise<void>;
};

const statusByState: Record<RecordingViewState, string> = {
  Ready: '마이크 확인 중',
  Recording: '녹음 중',
  Paused: '일시정지됨',
};

export function RecordingPage({ title, state, elapsedMs, microphoneStatus = 'checking', microphoneLevel = 0, microphoneInputDetected = false, errorMessage, completed = false, endFlowFailed = false, uploadSnapshot, endSnapshot, onRetryUpload, onRetryProcessing, onRetryMicrophone, onReturnToSetup, onStart, onPause, onResume, onRequestEnd, onConfirmEnd, onCancelEnd }: RecordingPageProps) {
  const [isConfirmOpen, setConfirmOpen] = useState(false);
  const [returningToSetup, setReturningToSetup] = useState(false);
  const [returnError, setReturnError] = useState('');
  const microphoneSignalDetected = microphoneInputDetected;
  const microphoneStatusLabel = microphoneStatus === 'checking'
    ? '마이크를 확인하고 있습니다'
    : microphoneStatus === 'error'
      ? '마이크를 사용할 수 없습니다'
      : microphoneSignalDetected ? '마이크 입력이 감지되었습니다' : '마이크 입력 대기 중';
  const microphoneSignalState = microphoneStatus === 'error'
    ? 'error'
    : microphoneStatus === 'checking'
      ? 'checking'
      : microphoneSignalDetected ? 'input-detected' : 'waiting';
  const showRecoveryActions = (completed && Boolean(errorMessage)) || endFlowFailed || ['retryable', 'incomplete', 'upload-failed', 'failed'].includes(endSnapshot?.status ?? '');
  const hasRecordingActions = (state === 'Ready' && !completed && !endFlowFailed)
    || (state === 'Ready' && showRecoveryActions)
    || state === 'Recording'
    || state === 'Paused';
  const isRecovering = completed || endFlowFailed || ['retryable', 'incomplete', 'upload-failed', 'failed'].includes(endSnapshot?.status ?? '');
  const endTrigger = useRef<HTMLButtonElement>(null);
  const cancelButton = useRef<HTMLButtonElement>(null);
  const confirmButton = useRef<HTMLButtonElement>(null);
  const dialogHandled = useRef(true);

  useEffect(() => {
    if (!isConfirmOpen) return;
    cancelButton.current?.focus();
    return () => endTrigger.current?.focus();
  }, [isConfirmOpen]);

  function cancelEnd() {
    if (dialogHandled.current) return;
    dialogHandled.current = true;
    setConfirmOpen(false);
    onCancelEnd();
  }

  function requestEnd(event: MouseEvent<HTMLButtonElement>) {
    endTrigger.current = event.currentTarget;
    dialogHandled.current = false;
    onRequestEnd();
    setConfirmOpen(true);
  }

  function confirmEnd() {
    if (dialogHandled.current) return;
    dialogHandled.current = true;
    setConfirmOpen(false);
    onConfirmEnd();
  }

  function handleDialogKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      event.preventDefault();
      cancelEnd();
      return;
    }
    if (event.key !== 'Tab') return;
    const first = cancelButton.current;
    const last = confirmButton.current;
    if (!first || !last) return;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  return (
    <main className={`recording-page page-scroll-layout recording-page--${state.toLowerCase()}${completed ? ' recording-page--completed' : ''}${isRecovering ? ' recording-page--recovering' : ''}`}>
      <header className="recording-heading">
        {state === 'Ready' && !completed && onReturnToSetup && <button className="recording-back" type="button" disabled={returningToSetup} aria-busy={returningToSetup} onClick={() => {
          setReturningToSetup(true);
          setReturnError('');
          void onReturnToSetup().catch(() => setReturnError('회의 준비 화면으로 돌아가지 못했습니다. 다시 시도해 주세요.')).finally(() => setReturningToSetup(false));
        }} aria-label="회의 준비로 돌아가기"><svg viewBox="0 0 16 16" aria-hidden="true"><path d="m10 3-5 5 5 5" /></svg></button>}
        <h1>
          <svg className="recording-title-icon" viewBox="0 0 24 24" aria-hidden="true">
            <rect x="9" y="3" width="6" height="12" rx="3" />
            <path d="M5.5 11v1a6.5 6.5 0 0 0 13 0v-1M12 18.5V22m-4 0h8" />
          </svg>
          회의 녹음
        </h1>
      </header>

      <div className="page-scroll-content">
      <section className="recording-content" aria-label="녹음 상태">
        <h2 className="recording-meeting-title">{title}</h2>
        {(state !== 'Ready' || completed) && <p className="recording-status" role="status" aria-live="polite">
          {completed ? '녹음이 종료되었습니다.' : <>
            {state === 'Recording' && <span className="recording-indicator" aria-hidden="true" />}
            {statusByState[state]}
          </>}
        </p>}
        <p className="recording-timer" aria-label="경과 시간">{formatElapsedTime(state === 'Ready' && !completed ? 0 : elapsedMs)}</p>
        {!completed && <div className={`recording-microphone recording-microphone--${microphoneStatus} recording-microphone--${microphoneSignalState}`} role="status" aria-label={microphoneStatusLabel} aria-live="polite">
          <div className="recording-microphone-visual" aria-hidden="true">
            <svg className="recording-microphone-icon" viewBox="0 0 24 24">
              <rect x="9" y="3" width="6" height="12" rx="3" />
              <path d="M5.5 11v1a6.5 6.5 0 0 0 13 0v-1M12 18.5V22m-4 0h8" />
            </svg>
            <i className={`recording-microphone-lamp recording-microphone-lamp--${microphoneSignalState}`} />
          </div>
          <div className="recording-waveform" aria-label={`마이크 입력 레벨 ${Math.round(microphoneLevel * 100)}%`}>
            {Array.from({ length: 21 }, (_, index) => {
              const position = Math.abs(index - 10) / 10;
              const wave = .18 + microphoneLevel * (1 - position * .72) * (0.45 + .55 * Math.abs(Math.sin(index * 1.7)));
              const waveformScale = state === 'Recording' || state === 'Paused' ? 64 : 38;
              return <i key={index} style={{ height: `${Math.max(4, Math.round(wave * waveformScale))}px` }} />;
            })}
          </div>
          {onRetryMicrophone && (microphoneStatus === 'error' || (microphoneStatus === 'ready' && !microphoneSignalDetected)) && <button type="button" className="recording-microphone-retry" aria-label={microphoneStatus === 'error' ? '마이크 다시 확인' : '마이크 입력 다시 확인'} onClick={onRetryMicrophone}>
            <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M16.5 7A7 7 0 0 0 4.4 4.7L3 6m0 0V2.8M3 6h3.2M3.5 13A7 7 0 0 0 15.6 15.3L17 14m0 0v3.2M17 14h-3.2" /></svg>
          </button>}
        </div>}
        {returnError && <p className="recording-error" role="alert">{returnError}</p>}
        {errorMessage && <p className="recording-error" role="alert">{errorMessage}</p>}
        {endSnapshot?.message && endSnapshot.status !== 'idle'
          && !(endSnapshot.status === 'uploading' && uploadSnapshot && (uploadSnapshot.totalCount > 0 || uploadSnapshot.status === 'retryable'))
          && <div className={`recording-end-state${endSnapshot.status === 'uploading' ? ' recording-end-state--uploading' : ''}`} aria-label="처리 시작 상태">
          {endSnapshot.status === 'uploading' && <div className="recording-upload-heading"><strong>오디오 업로드</strong></div>}
          <p className={endSnapshot.status === 'uploading' ? 'visually-hidden' : undefined} role={['stopping', 'persisting', 'uploading', 'starting'].includes(endSnapshot.status) ? 'status' : ['retryable', 'incomplete', 'upload-failed', 'failed'].includes(endSnapshot.status) ? 'alert' : 'status'} aria-live={['stopping', 'persisting', 'uploading', 'starting'].includes(endSnapshot.status) ? 'polite' : undefined}>
            {endSnapshot.message}
          </p>
          {['stopping', 'persisting', 'uploading', 'starting'].includes(endSnapshot.status) && <progress aria-label={endSnapshot.status === 'uploading' ? '오디오 업로드 진행' : '처리 시작 진행'} />}
          {['retryable', 'incomplete', 'failed'].includes(endSnapshot.status) && onRetryProcessing && <button className="button button-secondary" type="button" onClick={onRetryProcessing}>처리 시작 재시도</button>}
        </div>}
        {uploadSnapshot && ((state !== 'Recording' && state !== 'Paused' && uploadSnapshot.status === 'uploading') || ['retryable', 'failed', 'session-not-found'].includes(uploadSnapshot.status)) && <div className="recording-upload" aria-label="오디오 업로드">
          <div className="recording-upload-heading">
            <strong>오디오 업로드</strong>
            {uploadSnapshot.status === 'uploading' ? <span>업로드 중</span> : uploadSnapshot.totalCount > 0 && <span>{uploadSnapshot.confirmedCount}/{uploadSnapshot.totalCount}</span>}
          </div>
          {uploadSnapshot.status === 'uploading'
            ? <progress aria-label="오디오 업로드 진행" />
            : uploadSnapshot.totalCount > 0
              ? <progress aria-label="오디오 업로드 진행" value={uploadSnapshot.confirmedCount} max={uploadSnapshot.totalCount} />
              : <progress aria-label="오디오 업로드 진행" />}
          <span className="visually-hidden" role={['retryable', 'failed', 'session-not-found'].includes(uploadSnapshot.status) ? 'alert' : 'status'} aria-live="polite">
            {uploadSnapshot.message ?? (uploadSnapshot.status === 'complete' ? '오디오 업로드가 완료되었습니다.' : `${uploadSnapshot.pendingCount}개 조각 업로드 대기 중`)}
          </span>
          {uploadSnapshot.status === 'retryable' && onRetryUpload && <button className="button button-secondary" type="button" onClick={onRetryUpload}>업로드 재시도</button>}
        </div>}
        {hasRecordingActions && <div className={`recording-actions${state === 'Recording' || state === 'Paused' ? ' recording-actions--active' : ''}`}>
          {state === 'Ready' && !completed && !endFlowFailed && <button className="button button-primary recording-primary-action recording-start-action" type="button" disabled={microphoneStatus !== 'ready' || !microphoneSignalDetected} onClick={onStart}>녹음 시작</button>}
          {state === 'Ready' && showRecoveryActions && <>
            <Link className="button button-primary recording-primary-action" to="/meetings/new">새 회의 시작</Link>
            <Link className="button button-secondary" to="/meetings">회의록 목록</Link>
          </>}
          {state === 'Recording' && <>
            <button className="button button-secondary recording-action-pill" type="button" onClick={onPause}>
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M7 5.5v9M13 5.5v9" /></svg><span>일시정지</span>
            </button>
            <button className="button button-danger recording-primary-action recording-action-pill" type="button" ref={endTrigger} onClick={requestEnd} aria-label="회의 종료">
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="m4.5 10.5 3.6 3.6 7.4-7.6" /></svg><span>완료</span>
            </button>
          </>}
          {state === 'Paused' && <>
            <button className="button button-primary recording-primary-action recording-action-pill" type="button" onClick={onResume}>
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="m7 5 8 5-8 5z" /></svg><span>녹음 재개</span>
            </button>
            <button className="button button-danger recording-action-pill" type="button" ref={endTrigger} onClick={requestEnd} aria-label="회의 종료">
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="m4.5 10.5 3.6 3.6 7.4-7.6" /></svg><span>완료</span>
            </button>
          </>}
        </div>}
      </section>

      {isConfirmOpen && (
        <div className="recording-dialog-backdrop">
          <div className="recording-dialog" role="dialog" aria-modal="true" aria-labelledby="recording-dialog-title" aria-describedby="recording-dialog-description" onKeyDown={handleDialogKeyDown}>
            <h2 id="recording-dialog-title">회의를 종료할까요?</h2>
            <p id="recording-dialog-description">녹음을 종료하고 회의록 작성을 시작합니다.</p>
            <div className="recording-dialog-actions">
              <button className="button button-secondary" type="button" ref={cancelButton} onClick={cancelEnd}>계속 녹음</button>
              <button className="button button-danger" type="button" ref={confirmButton} onClick={confirmEnd}>회의 종료</button>
            </div>
          </div>
        </div>
      )}
      </div>
    </main>
  );
}

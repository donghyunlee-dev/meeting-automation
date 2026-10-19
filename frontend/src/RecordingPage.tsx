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
  errorMessage?: string;
  completed?: boolean;
  endFlowFailed?: boolean;
  uploadSnapshot?: ChunkUploadSnapshot;
  endSnapshot?: RecordingEndSnapshot;
  onRetryUpload?: () => void;
  onRetryProcessing?: () => void;
};

const statusByState: Record<RecordingViewState, string> = {
  Ready: '준비됨',
  Recording: '녹음 중',
  Paused: '일시정지됨',
};

export function RecordingPage({ title, state, elapsedMs, errorMessage, completed = false, endFlowFailed = false, uploadSnapshot, endSnapshot, onRetryUpload, onRetryProcessing, onStart, onPause, onResume, onRequestEnd, onConfirmEnd, onCancelEnd }: RecordingPageProps) {
  const [isConfirmOpen, setConfirmOpen] = useState(false);
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
    <main className={`recording-page recording-page--${state.toLowerCase()}${completed ? ' recording-page--completed' : ''}${isRecovering ? ' recording-page--recovering' : ''}`}>
      <header className="recording-heading">
        <h1>
          <svg className="recording-title-icon" viewBox="0 0 24 24" aria-hidden="true">
            <rect x="9" y="3" width="6" height="12" rx="3" />
            <path d="M5.5 11v1a6.5 6.5 0 0 0 13 0v-1M12 18.5V22m-4 0h8" />
          </svg>
          회의 녹음
        </h1>
      </header>

      <section className="recording-content" aria-label="녹음 상태">
        <h2 className="recording-meeting-title">{title}</h2>
        <p className="recording-status" role="status" aria-live="polite">
          {state === 'Recording' && <span className="recording-indicator" aria-hidden="true" />}
          {completed ? '녹음이 종료되었습니다.' : statusByState[state]}
        </p>
        <p className="recording-timer" aria-label="경과 시간">{formatElapsedTime(state === 'Ready' && !completed ? 0 : elapsedMs)}</p>
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
        {uploadSnapshot && (uploadSnapshot.totalCount > 0 || uploadSnapshot.status === 'retryable') && <div className="recording-upload" aria-label="오디오 업로드">
          <div className="recording-upload-heading"><strong>오디오 업로드</strong><span>{uploadSnapshot.confirmedCount}/{uploadSnapshot.totalCount}</span></div>
          {uploadSnapshot.totalCount > 0
            ? <progress aria-label="오디오 업로드 진행" value={uploadSnapshot.confirmedCount} max={uploadSnapshot.totalCount} />
            : <progress aria-label="오디오 업로드 진행" />}
          <span className="visually-hidden" role={uploadSnapshot.status === 'retryable' ? 'alert' : 'status'} aria-live="polite">
            {uploadSnapshot.message ?? (uploadSnapshot.status === 'complete' ? '오디오 업로드가 완료되었습니다.' : `${uploadSnapshot.pendingCount}개 조각 업로드 대기 중`)}
          </span>
          {uploadSnapshot.status === 'retryable' && onRetryUpload && <button className="button button-secondary" type="button" onClick={onRetryUpload}>업로드 재시도</button>}
        </div>}
        {hasRecordingActions && <div className="recording-actions">
          {state === 'Ready' && !completed && !endFlowFailed && <button className="button button-primary recording-primary-action recording-start-action" type="button" onClick={onStart}>녹음 시작</button>}
          {state === 'Ready' && showRecoveryActions && <>
            <Link className="button button-primary recording-primary-action" to="/meetings/new">새 회의 시작</Link>
            <Link className="button button-secondary" to="/meetings">회의록 목록</Link>
          </>}
          {state === 'Recording' && <>
            <button className="button button-secondary" type="button" onClick={onPause}>일시정지</button>
            <button className="button button-danger recording-primary-action" type="button" ref={endTrigger} onClick={requestEnd}>회의 종료</button>
          </>}
          {state === 'Paused' && <>
            <button className="button button-primary recording-primary-action" type="button" onClick={onResume}>녹음 재개</button>
            <button className="button button-danger" type="button" ref={endTrigger} onClick={requestEnd}>회의 종료</button>
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
    </main>
  );
}

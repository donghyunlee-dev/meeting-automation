import { useEffect, useRef, useState, type MouseEvent } from 'react';
import { formatElapsedTime } from './recordingTime';

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
};

const statusByState: Record<RecordingViewState, string> = {
  Ready: '준비됨',
  Recording: '녹음 중',
  Paused: '일시정지됨',
};

export function RecordingPage({ sessionId, title, state, elapsedMs, errorMessage, completed = false, onStart, onPause, onResume, onRequestEnd, onConfirmEnd, onCancelEnd }: RecordingPageProps) {
  const [isConfirmOpen, setConfirmOpen] = useState(false);
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
    <main className={`recording-page recording-page--${state.toLowerCase()}`}>
      <header className="recording-heading">
        <p className="recording-eyebrow">회의 녹음</p>
        <h1>{title}</h1>
        <p className="recording-session-id">세션 ID: <span>{sessionId}</span></p>
      </header>

      <section className="recording-content" aria-label="녹음 상태">
        <p className="recording-status" role="status" aria-live="polite">
          {state === 'Recording' && <span className="recording-indicator" aria-hidden="true" />}
          {completed ? '녹음이 종료되었습니다.' : statusByState[state]}
        </p>
        <p className="recording-timer" aria-label="경과 시간">{formatElapsedTime(state === 'Ready' && !completed ? 0 : elapsedMs)}</p>
        {errorMessage && <p className="recording-error" role="alert">{errorMessage}</p>}
        <div className="recording-actions">
          {state === 'Ready' && !completed && <button className="button button-primary recording-primary-action" type="button" onClick={onStart}>녹음 시작</button>}
          {state === 'Recording' && <>
            <button className="button button-secondary" type="button" onClick={onPause}>일시정지</button>
            <button className="button button-danger recording-primary-action" type="button" ref={endTrigger} onClick={requestEnd}>회의 종료</button>
          </>}
          {state === 'Paused' && <>
            <button className="button button-primary recording-primary-action" type="button" onClick={onResume}>녹음 재개</button>
            <button className="button button-danger" type="button" ref={endTrigger} onClick={requestEnd}>회의 종료</button>
          </>}
        </div>
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

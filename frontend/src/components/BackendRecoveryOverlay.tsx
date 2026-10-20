import { useEffect, useRef, useSyncExternalStore, type KeyboardEvent } from 'react';
import {
  dismissApiRecovery,
  getApiRecoveryState,
  retryApiRecovery,
  subscribeApiRecovery,
} from '../apiRecovery';

export function BackendRecoveryOverlay() {
  const state = useSyncExternalStore(subscribeApiRecovery, getApiRecoveryState, getApiRecoveryState);
  const dialogRef = useRef<HTMLElement | null>(null);
  const closeButtonRef = useRef<HTMLButtonElement | null>(null);
  const readyTitleRef = useRef<HTMLHeadingElement | null>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const visible = state.status !== 'idle' && state.visible;
  const ready = state.status === 'ready';

  useEffect(() => {
    if (!visible) return;
    returnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeButtonRef.current?.focus();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previousOverflow;
      if (returnFocusRef.current?.isConnected) returnFocusRef.current.focus();
      returnFocusRef.current = null;
    };
  }, [visible]);

  useEffect(() => {
    if (ready) readyTitleRef.current?.focus({ preventScroll: true });
  }, [ready]);

  if (!visible) return null;

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === 'Escape') {
      event.preventDefault();
      dismissApiRecovery();
      return;
    }
    if (event.key !== 'Tab') return;
    const focusable = dialogRef.current?.querySelectorAll<HTMLElement>('button:not(:disabled)');
    if (!focusable?.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  return (
    <div className="backend-recovery-backdrop">
      <section
        ref={dialogRef}
        className="backend-recovery-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="backend-recovery-title"
        aria-describedby="backend-recovery-description"
        onKeyDown={handleKeyDown}
      >
        <button
          ref={closeButtonRef}
          className="backend-recovery-close"
          type="button"
          aria-label="복구 대기 닫기"
          onClick={dismissApiRecovery}
        >×</button>
        <div className={`backend-recovery-visual${ready ? ' is-ready' : ''}`} aria-hidden="true">
          <svg viewBox="0 0 240 190" role="presentation">
            <circle className="recovery-orbit recovery-orbit--outer" cx="120" cy="95" r="76" />
            <circle className="recovery-orbit recovery-orbit--inner" cx="120" cy="95" r="60" />
            <path className="recovery-door-halo" d="M67 148V80a53 53 0 0 1 106 0v68z" />
            <path className="recovery-door" d="M82 148V82a38 38 0 0 1 76 0v66z" />
            <path className="recovery-door-edge" d="M82 148V82a38 38 0 0 1 76 0v66" />
            <circle className="recovery-door-knob" cx="145" cy="111" r="3" />
            <path className="recovery-ice-crack" d="m119 28-8 24 13 10-16 16 13 11-7 19 13 12-8 27" />
            <path className="recovery-ice-shard recovery-ice-shard--left" d="m58 92-16-9 5 24-14 9 25 7" />
            <path className="recovery-ice-shard recovery-ice-shard--right" d="m182 72 18-13-2 25 16 8-25 12" />
            <path className="recovery-light-beam" d="M120 147V83" />
            <circle className="recovery-light-dot" cx="120" cy="73" r="5" />
          </svg>
        </div>
        <div className="backend-recovery-copy" aria-live="polite" aria-atomic="true">
          <p className="backend-recovery-eyebrow">WINDSOFT MINUTES</p>
          <h2 ref={readyTitleRef} id="backend-recovery-title" tabIndex={-1}>{ready ? '서버가 다시 열렸어요' : '서버 연결을 확인하고 있어요'}</h2>
          <p id="backend-recovery-description">
            {ready ? '잠시 후 진행 중인 화면으로 돌아갑니다.' : '연결이 다시 준비될 때까지 확인을 이어가고 있어요.'}
          </p>
        </div>
        {!ready && (
          <div className="backend-recovery-actions">
            <button className="button button-primary" type="button" onClick={retryApiRecovery}>다시 확인</button>
            <button className="button button-secondary" type="button" onClick={dismissApiRecovery}>화면으로 돌아가기</button>
          </div>
        )}
      </section>
    </div>
  );
}

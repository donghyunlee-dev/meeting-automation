import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RecordingPage, type RecordingCallbacks, type RecordingViewState } from './RecordingPage';
import { formatElapsedTime } from './recordingTime';

afterEach(cleanup);

function renderRecording(state: RecordingViewState, elapsedMs = 0) {
  const callbacks: RecordingCallbacks = {
    onStart: vi.fn(),
    onPause: vi.fn(),
    onResume: vi.fn(),
    onRequestEnd: vi.fn(),
    onConfirmEnd: vi.fn(),
    onCancelEnd: vi.fn(),
  };
  const result = render(<RecordingPage sessionId="session-123" title="Planning" state={state} elapsedMs={elapsedMs} {...callbacks} />);
  return { ...result, callbacks };
}

describe('RecordingPage', () => {
  it.each([
    ['Ready', 0, '준비됨', '녹음 시작'],
    ['Recording', 62018, '녹음 중', '일시정지'],
    ['Paused', 62018, '일시정지됨', '녹음 재개'],
  ] as const)('renders %s title, status, elapsed time, and primary action', (state, elapsedMs, status, primaryAction) => {
    renderRecording(state, elapsedMs);
    expect(screen.getByRole('heading', { name: 'Planning' })).toBeTruthy();
    expect(screen.getByText('세션 ID:').textContent).toContain('session-123');
    expect(screen.getByRole('status').textContent).toContain(status);
    expect(screen.getByLabelText('경과 시간').textContent).toBe(state === 'Ready' ? '00:00:00' : '00:01:02');
    expect(screen.getByRole('button', { name: primaryAction })).toBeTruthy();
  });

  it('formats durations as padded HH:mm:ss, including hours and fractional seconds', () => {
    expect(formatElapsedTime(62018)).toBe('00:01:02');
    expect(formatElapsedTime(3_661_999)).toBe('01:01:01');
    expect(formatElapsedTime(-500)).toBe('00:00:00');
  });

  it('calls only the Ready start callback when recording starts', () => {
    const { callbacks } = renderRecording('Ready');
    fireEvent.click(screen.getByRole('button', { name: '녹음 시작' }));
    expect(callbacks.onStart).toHaveBeenCalledTimes(1);
    expect(callbacks.onPause).not.toHaveBeenCalled();
    expect(callbacks.onResume).not.toHaveBeenCalled();
    expect(callbacks.onRequestEnd).not.toHaveBeenCalled();
  });

  it('calls pause and end request independently from the Recording actions', () => {
    const { callbacks } = renderRecording('Recording', 62018);
    fireEvent.click(screen.getByRole('button', { name: '일시정지' }));
    expect(callbacks.onPause).toHaveBeenCalledTimes(1);
    expect(callbacks.onRequestEnd).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '회의 종료' }));
    expect(callbacks.onRequestEnd).toHaveBeenCalledTimes(1);
    expect(callbacks.onConfirmEnd).not.toHaveBeenCalled();
  });

  it('calls resume and end request independently from the Paused actions', () => {
    const { callbacks } = renderRecording('Paused', 62018);
    fireEvent.click(screen.getByRole('button', { name: '녹음 재개' }));
    expect(callbacks.onResume).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: '회의 종료' }));
    expect(callbacks.onRequestEnd).toHaveBeenCalledTimes(1);
  });

  it('returns focus to the end trigger and preserves the timer and Recording status on cancel', () => {
    const { callbacks } = renderRecording('Recording', 62018);
    const trigger = screen.getByRole('button', { name: '회의 종료' });
    trigger.focus();
    fireEvent.click(trigger);
    const dialog = screen.getByRole('dialog', { name: '회의를 종료할까요?' });
    const cancel = within(dialog).getByRole('button', { name: '계속 녹음' });
    expect(document.activeElement).toBe(cancel);
    fireEvent.click(cancel);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('status').textContent).toBe('녹음 중');
    expect(screen.getByLabelText('경과 시간').textContent).toBe('00:01:02');
    expect(document.activeElement).toBe(trigger);
    expect(callbacks.onCancelEnd).toHaveBeenCalledTimes(1);
    expect(callbacks.onConfirmEnd).not.toHaveBeenCalled();
  });

  it('restores focus to the end button after pointer-style click without prior focus', () => {
    const { callbacks } = renderRecording('Recording', 62018);
    const trigger = screen.getByRole('button', { name: '회의 종료' });
    expect(document.activeElement).not.toBe(trigger);
    fireEvent.click(trigger);
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '계속 녹음' }));
    expect(callbacks.onCancelEnd).toHaveBeenCalledTimes(1);
    expect(document.activeElement).toBe(trigger);
  });

  it('cancels with Escape, keeps the Paused view state and timer, and restores trigger focus', () => {
    const { callbacks } = renderRecording('Paused', 62018);
    const trigger = screen.getByRole('button', { name: '회의 종료' });
    trigger.focus();
    fireEvent.click(trigger);
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('status').textContent).toBe('일시정지됨');
    expect(screen.getByLabelText('경과 시간').textContent).toBe('00:01:02');
    expect(document.activeElement).toBe(trigger);
    expect(callbacks.onCancelEnd).toHaveBeenCalledTimes(1);
    expect(callbacks.onConfirmEnd).not.toHaveBeenCalled();
  });

  it('confirms end once, exposes a named modal, and keeps all modal focus inside it', () => {
    const { callbacks } = renderRecording('Recording', 62018);
    const trigger = screen.getByRole('button', { name: '회의 종료' });
    trigger.focus();
    fireEvent.click(trigger);
    const dialog = screen.getByRole('dialog', { name: '회의를 종료할까요?' });
    expect(within(dialog).getByText('녹음을 종료하고 회의록 작성을 시작합니다.')).toBeTruthy();
    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: '회의 종료' }));
    fireEvent.keyDown(dialog, { key: 'Tab' });
    expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: '계속 녹음' }));
    const confirm = within(dialog).getByRole('button', { name: '회의 종료' });
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    expect(callbacks.onConfirmEnd).toHaveBeenCalledTimes(1);
    expect(callbacks.onCancelEnd).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('announces state through a live status but leaves the second-by-second timer outside live regions', () => {
    renderRecording('Recording', 62018);
    expect(screen.getByRole('status').getAttribute('aria-live')).toBe('polite');
    expect(screen.getByLabelText('경과 시간').closest('[aria-live]')).toBeNull();
  });

  it('provides touch-sized actions and a narrow-screen safe layout structure', () => {
    renderRecording('Paused', 62018);
    const page = screen.getByRole('main');
    expect(page.className).toContain('recording-page');
    for (const action of screen.getAllByRole('button')) {
      expect(action.className).toContain('button');
      expect(action.getAttribute('type')).toBe('button');
    }
  });
});

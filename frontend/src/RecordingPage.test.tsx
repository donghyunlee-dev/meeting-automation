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
  const result = render(<RecordingPage sessionId="session-123" title="Planning" state={state} elapsedMs={elapsedMs}
    microphoneStatus="ready" microphoneInputDetected {...callbacks} />);
  return { ...result, callbacks };
}

describe('RecordingPage', () => {
  it.each([
    ['Ready', 0, '준비됨', '녹음 시작'],
    ['Recording', 62018, '녹음 중', '일시정지'],
    ['Paused', 62018, '일시정지됨', '녹음 재개'],
  ] as const)('renders %s title, status, elapsed time, and primary action', (state, elapsedMs, status, primaryAction) => {
    renderRecording(state, elapsedMs);
    expect(screen.getByRole('heading', { name: '회의 녹음', level: 1 })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Planning', level: 2 })).toBeTruthy();
    expect(screen.queryByText(/세션 ID/)).toBeNull();
    expect(screen.getByText(status).textContent).toContain(status);
    expect(screen.getByLabelText('경과 시간').textContent).toBe(state === 'Ready' ? '00:00:00' : '00:01:02');
    expect(screen.getByRole('button', { name: primaryAction })).toBeTruthy();
  });

  it('shows the recording menu title with its icon and meeting title above the timer', () => {
    renderRecording('Ready');

    const menuTitle = screen.getByRole('heading', { name: '회의 녹음', level: 1 });
    expect(menuTitle.querySelector('.recording-title-icon')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Planning', level: 2 }).className).toContain('recording-meeting-title');
    expect(screen.queryByText(/세션 ID/)).toBeNull();
    expect(screen.getByRole('button', { name: '녹음 시작' }).className).toContain('recording-start-action');
  });

  it('offers an input monitor retry when the microphone stream is ready but no signal is detected', () => {
    const onRetryMicrophone = vi.fn();
    render(<RecordingPage sessionId="session-123" title="Planning" state="Ready" elapsedMs={0}
      microphoneStatus="ready" microphoneInputDetected={false} onRetryMicrophone={onRetryMicrophone}
      onStart={vi.fn()} onPause={vi.fn()} onResume={vi.fn()} onRequestEnd={vi.fn()} onConfirmEnd={vi.fn()} onCancelEnd={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: '마이크 입력 확인' }));

    expect(onRetryMicrophone).toHaveBeenCalledTimes(1);
    expect((screen.getByRole('button', { name: '녹음 시작' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('formats durations as padded HH:mm:ss, including hours and fractional seconds', () => {
    expect(formatElapsedTime(62018)).toBe('00:01:02');
    expect(formatElapsedTime(3_661_999)).toBe('01:01:01');
    expect(formatElapsedTime(-500)).toBe('00:00:00');
  });

  it('shows pending upload progress after recording and lets the user retry failed uploads', () => {
    const onRetryUpload = vi.fn();
    const uploadProps = {
      sessionId: 'session-123', title: 'Planning', state: 'Ready' as const, elapsedMs: 12_000,
      completed: true,
      uploadSnapshot: { status: 'retryable' as const, totalCount: 3, confirmedCount: 2, pendingCount: 1 },
      onRetryUpload,
      onStart: vi.fn(), onPause: vi.fn(), onResume: vi.fn(), onRequestEnd: vi.fn(), onConfirmEnd: vi.fn(), onCancelEnd: vi.fn(),
    };
    render(<RecordingPage {...uploadProps} />);

    expect(screen.getByRole('progressbar', { name: '오디오 업로드 진행' })).toHaveProperty('value', 2);
    expect(screen.getByText('1개 조각 업로드 대기 중')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '업로드 재시도' }));
    expect(onRetryUpload).toHaveBeenCalledTimes(1);
  });

  it('hides chunk upload progress while recording and hides a completed per-batch count', () => {
    const callbacks = { onStart: vi.fn(), onPause: vi.fn(), onResume: vi.fn(), onRequestEnd: vi.fn(), onConfirmEnd: vi.fn(), onCancelEnd: vi.fn() };
    const base = { sessionId: 'session-123', title: 'Planning', state: 'Recording' as const, elapsedMs: 2_000, ...callbacks };
    const { rerender } = render(<RecordingPage {...base} uploadSnapshot={{ status: 'uploading', totalCount: 1, confirmedCount: 0, pendingCount: 1 }} />);

    expect(screen.queryByLabelText('오디오 업로드')).toBeNull();
    expect(screen.queryByRole('progressbar', { name: '오디오 업로드 진행' })).toBeNull();

    rerender(<RecordingPage {...base} state="Ready" completed uploadSnapshot={{ status: 'complete', totalCount: 1, confirmedCount: 1, pendingCount: 0 }} />);
    expect(screen.queryByLabelText('오디오 업로드')).toBeNull();
    expect(screen.queryByText('1/1')).toBeNull();
  });

  it('keeps an upload failure visible while recording', () => {
    render(<RecordingPage
      sessionId="session-123" title="Planning" state="Recording" elapsedMs={2_000}
      uploadSnapshot={{ status: 'retryable', totalCount: 2, confirmedCount: 1, pendingCount: 1, message: '업로드에 실패했습니다.' }}
      onRetryUpload={vi.fn()} onStart={vi.fn()} onPause={vi.fn()} onResume={vi.fn()}
      onRequestEnd={vi.fn()} onConfirmEnd={vi.fn()} onCancelEnd={vi.fn()}
    />);

    expect(screen.getByLabelText('오디오 업로드')).toBeTruthy();
    expect(screen.getByRole('button', { name: '업로드 재시도' })).toBeTruthy();
  });

  it('preserves the final elapsed time in the completed Ready view without offering another start', () => {
    render(<RecordingPage
      sessionId="session-123"
      title="Planning"
      state="Ready"
      elapsedMs={5_250}
      completed
      onStart={vi.fn()}
      onPause={vi.fn()}
      onResume={vi.fn()}
      onRequestEnd={vi.fn()}
      onConfirmEnd={vi.fn()}
      onCancelEnd={vi.fn()}
    />);
    expect(screen.getByRole('status').textContent).toBe('녹음이 종료되었습니다.');
    expect(screen.getByLabelText('경과 시간').textContent).toBe('00:00:05');
    expect(screen.queryByRole('button', { name: '녹음 시작' })).toBeNull();
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
    expect(screen.getByText('녹음 중').textContent).toBe('녹음 중');
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
    expect(screen.getByText('일시정지됨').textContent).toBe('일시정지됨');
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
    expect(screen.getByText('녹음 중').getAttribute('aria-live')).toBe('polite');
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

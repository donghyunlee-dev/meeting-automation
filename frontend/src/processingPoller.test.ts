import { afterEach, describe, expect, it, vi } from 'vitest';
import { ProcessingApiError, type ProcessingSnapshot } from './processingApi';
import { ProcessingPoller } from './processingPoller';

const snapshot = (status: ProcessingSnapshot['status'] = 'PROCESSING'): ProcessingSnapshot => ({
  sessionId: 's1', version: 1, status, processing: { stage: 'AUDIO_ASSEMBLY', progressPercent: 10, audioExpiresAt: null }, allowedActions: [],
});

describe('ProcessingPoller', () => {
  afterEach(() => vi.useRealTimers());

  it('starts immediately, allows only one in-flight request, and polls again after one second', async () => {
    vi.useFakeTimers();
    let resolve!: (value: ProcessingSnapshot) => void;
    const getStatus = vi.fn(() => new Promise<ProcessingSnapshot>((done) => { resolve = done; }));
    const poller = new ProcessingPoller({ sessionId: 's1', getStatus, onSnapshot: vi.fn(), onError: vi.fn() });
    poller.start();
    poller.start();
    expect(getStatus).toHaveBeenCalledTimes(1);
    resolve(snapshot());
    await vi.waitFor(() => expect(getStatus).toHaveBeenCalledTimes(1));
    await vi.advanceTimersByTimeAsync(999);
    expect(getStatus).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(getStatus).toHaveBeenCalledTimes(2);
    poller.stop();
  });

  it('runs a visibility-return refresh immediately after the current request settles', async () => {
    let resolve!: (value: ProcessingSnapshot) => void;
    const getStatus = vi.fn()
      .mockImplementationOnce(() => new Promise<ProcessingSnapshot>((done) => { resolve = done; }))
      .mockResolvedValue(snapshot());
    const poller = new ProcessingPoller({ sessionId: 's1', getStatus, onSnapshot: vi.fn(), onError: vi.fn() });
    poller.start();
    poller.refreshNow();
    expect(getStatus).toHaveBeenCalledTimes(1);
    resolve(snapshot());
    await vi.waitFor(() => expect(getStatus).toHaveBeenCalledTimes(2));
    poller.stop();
  });

  it.each([
    ['REVIEW', 'snapshot'] as const,
    ['SESSION_NOT_FOUND', 'error'] as const,
  ])('discards a queued visibility refresh after terminal %s', async (terminalResult, resultKind) => {
    void terminalResult;
    let resolve!: (value: ProcessingSnapshot) => void;
    let reject!: (reason: unknown) => void;
    const firstRequest = new Promise<ProcessingSnapshot>((done, fail) => { resolve = done; reject = fail; });
    const terminalSnapshot = snapshot('REVIEW');
    const getStatus = vi.fn().mockReturnValueOnce(firstRequest).mockResolvedValue(snapshot());
    const poller = new ProcessingPoller({ sessionId: 's1', getStatus, onSnapshot: vi.fn(), onError: vi.fn() });
    poller.start();
    poller.refreshNow();
    if (resultKind === 'snapshot') resolve(terminalSnapshot);
    else reject(new ProcessingApiError('SESSION_NOT_FOUND', 404));
    await Promise.resolve();
    await Promise.resolve();
    await new Promise((done) => setTimeout(done, 0));
    expect(getStatus).toHaveBeenCalledTimes(1);
    poller.stop();
  });

  it('backs off network errors and stops after SESSION_NOT_FOUND', async () => {
    vi.useFakeTimers();
    const getStatus = vi.fn()
      .mockRejectedValueOnce(new ProcessingApiError('NETWORK_ERROR'))
      .mockRejectedValueOnce(new ProcessingApiError('NETWORK_ERROR'))
      .mockRejectedValueOnce(new ProcessingApiError('SESSION_NOT_FOUND'));
    const poller = new ProcessingPoller({ sessionId: 's1', getStatus, onSnapshot: vi.fn(), onError: vi.fn() });
    poller.start();
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(1_000);
    await Promise.resolve();
    expect(getStatus).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1_999);
    expect(getStatus).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    await Promise.resolve();
    expect(getStatus).toHaveBeenCalledTimes(3);
    await vi.advanceTimersByTimeAsync(30_000);
    expect(getStatus).toHaveBeenCalledTimes(3);
    poller.stop();
  });

  it('aborts the active request on stop and never publishes after cancellation', async () => {
    let signal: AbortSignal | undefined;
    const onSnapshot = vi.fn();
    const getStatus = vi.fn((_id: string, requestSignal: AbortSignal) => {
      signal = requestSignal;
      return new Promise<ProcessingSnapshot>(() => undefined);
    });
    const poller = new ProcessingPoller({ sessionId: 's1', getStatus, onSnapshot, onError: vi.fn() });
    poller.start();
    poller.stop();
    expect(signal?.aborted).toBe(true);
    expect(onSnapshot).not.toHaveBeenCalled();
  });

  it('stops polling on PROCESSING_FAILED terminal snapshot', async () => {
    vi.useFakeTimers();
    const getStatus = vi.fn().mockResolvedValue(snapshot('PROCESSING_FAILED'));
    const poller = new ProcessingPoller({ sessionId: 's1', getStatus, onSnapshot: vi.fn(), onError: vi.fn() });
    poller.start();
    await vi.waitFor(() => expect(getStatus).toHaveBeenCalledTimes(1));
    await vi.advanceTimersByTimeAsync(10_000);
    expect(getStatus).toHaveBeenCalledTimes(1);
    poller.stop();
  });
});

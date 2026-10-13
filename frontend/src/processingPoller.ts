import { ProcessingApiError, type ProcessingSnapshot } from './processingApi';

export type ProcessingPollerOptions = {
  sessionId: string;
  getStatus: (sessionId: string, signal: AbortSignal) => Promise<ProcessingSnapshot>;
  onSnapshot: (snapshot: ProcessingSnapshot) => void;
  onError: (error: ProcessingApiError) => void;
  schedule?: (callback: () => void, delayMs: number) => ReturnType<typeof setTimeout>;
  cancel?: (timer: ReturnType<typeof setTimeout>) => void;
};

const terminal = new Set(['REVIEW', 'DRAFT_READY', 'COMPLETED_WITH_WARNINGS', 'PROCESSING_FAILED']);

export class ProcessingPoller {
  private timer: ReturnType<typeof setTimeout> | null = null;
  private controller: AbortController | null = null;
  private stopped = false;
  private refreshPending = false;
  private failures = 0;
  private readonly schedule: NonNullable<ProcessingPollerOptions['schedule']>;
  private readonly cancel: NonNullable<ProcessingPollerOptions['cancel']>;

  constructor(private readonly options: ProcessingPollerOptions) {
    this.schedule = options.schedule ?? ((callback, delay) => setTimeout(callback, delay));
    this.cancel = options.cancel ?? clearTimeout;
  }

  start() { if (!this.stopped && !this.controller && this.timer === null) void this.poll(); }

  stop() {
    this.stopped = true;
    if (this.timer !== null) this.cancel(this.timer);
    this.timer = null;
    this.refreshPending = false;
    this.controller?.abort();
    this.controller = null;
  }

  refreshNow() {
    if (this.stopped) return;
    if (this.controller) {
      this.refreshPending = true;
      return;
    }
    if (this.timer !== null) this.cancel(this.timer);
    this.timer = null;
    void this.poll();
  }

  private scheduleNext(delayMs: number) {
    if (this.stopped) return;
    this.timer = this.schedule(() => { this.timer = null; void this.poll(); }, delayMs);
  }

  private async poll() {
    if (this.stopped || this.controller) return;
    const controller = new AbortController();
    this.controller = controller;
    let queuedRefreshAllowed = false;
    try {
      const snapshot = await this.options.getStatus(this.options.sessionId, controller.signal);
      if (this.stopped || controller.signal.aborted) return;
      this.failures = 0;
      this.options.onSnapshot(snapshot);
      queuedRefreshAllowed = snapshot.sessionId === this.options.sessionId && !terminal.has(snapshot.status);
      if (queuedRefreshAllowed) this.scheduleNext(1_000);
    } catch (error) {
      if (this.stopped || controller.signal.aborted) return;
      const mapped = error instanceof ProcessingApiError ? error : new ProcessingApiError('NETWORK_ERROR');
      this.options.onError(mapped);
      const retryable = mapped.code === 'NETWORK_ERROR' || mapped.status === 408 || mapped.status === 429 || (mapped.status !== undefined && mapped.status >= 500);
      if (mapped.code === 'SESSION_NOT_FOUND' || !retryable) return;
      queuedRefreshAllowed = true;
      const delays = [1_000, 2_000, 4_000, 8_000, 10_000];
      const delay = delays[Math.min(this.failures, delays.length - 1)];
      this.failures += 1;
      this.scheduleNext(delay);
    } finally {
      if (this.controller === controller) this.controller = null;
      if (this.refreshPending) {
        this.refreshPending = false;
        if (queuedRefreshAllowed && !this.stopped) {
          if (this.timer !== null) this.cancel(this.timer);
          this.timer = null;
          void this.poll();
        }
      }
    }
  }
}

import type { RecordingViewState } from './RecordingPage';

export type RecordingUploadPolicy = {
  chunkDurationSeconds: number;
  acceptedMimeTypes: string[];
};

export type MediaTrackLike = { stop: () => void };
export type MediaStreamLike = { getTracks: () => MediaTrackLike[] };

export type RecorderLike = {
  mimeType: string;
  onstart: (() => void) | null;
  onpause: (() => void) | null;
  onresume: (() => void) | null;
  onstop: (() => void) | null;
  onerror: ((event: { error?: { name?: string } }) => void) | null;
  ondataavailable: ((event: { data: Blob }) => void) | null;
  start: (timeslice: number) => void;
  pause: () => void;
  resume: () => void;
  stop: () => void;
};

export type MediaRecorderAdapter = {
  isMediaRecorderSupported: () => boolean;
  isTypeSupported: (mimeType: string) => boolean;
  getUserMedia: () => Promise<MediaStreamLike>;
  monitorInput?: (stream: MediaStreamLike, onLevel: (level: number) => void) => () => void;
  createRecorder: (stream: MediaStreamLike, options: { mimeType: string }) => RecorderLike;
  now: () => number;
  nowEpoch: () => number;
  setInterval: (callback: () => void, delayMs: number) => unknown;
  clearInterval: (handle: unknown) => void;
  createChunkId: () => string;
};

export type RecordingSnapshot = {
  state: RecordingViewState;
  elapsedMs: number;
  microphoneStatus: 'checking' | 'ready' | 'error';
  microphoneLevel: number;
  microphoneInputDetected: boolean;
  errorMessage?: string;
  completed?: boolean;
};

export type RecordingChunk = {
  chunkId: string;
  blob: Blob;
  mimeType: string;
  recordedAtMs: number;
  recordedElapsedMs: number;
};

type ControllerOptions = {
  uploadPolicy: RecordingUploadPolicy;
  adapter: MediaRecorderAdapter;
  onChange: (snapshot: RecordingSnapshot) => void;
  onChunk: (chunk: RecordingChunk) => void;
  onStopComplete: (elapsedMs: number) => void;
};

const ELAPSED_REFRESH_MS = 1_000;
const ERRORS = {
  unsupported: '이 브라우저에서는 녹음을 지원하지 않습니다.',
  unsupportedMime: '지원되는 녹음 형식이 없습니다.',
  permission: '마이크 권한이 거부되었습니다. 브라우저에서 마이크 접근을 허용해 주세요.',
  missingDevice: '사용할 수 있는 마이크를 찾을 수 없습니다.',
  busyDevice: '마이크를 사용할 수 없습니다. 다른 앱에서 사용 중인지 확인해 주세요.',
  general: '녹음 중 오류가 발생했습니다. 다시 시도해 주세요.',
  invalid: '현재 상태에서는 이 작업을 할 수 없습니다.',
} as const;

function safeError(error: unknown): string {
  const name = (error as { name?: string } | null)?.name;
  if (name === 'NotAllowedError' || name === 'SecurityError') return ERRORS.permission;
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return ERRORS.missingDevice;
  if (name === 'NotReadableError') return ERRORS.busyDevice;
  return ERRORS.general;
}

export class RecordingController {
  private readonly options: ControllerOptions;
  private snapshot: RecordingSnapshot = { state: 'Ready', elapsedMs: 0, microphoneStatus: 'checking', microphoneLevel: 0, microphoneInputDetected: false };
  private stream: MediaStreamLike | null = null;
  private recorder: RecorderLike | null = null;
  private selectedMime = '';
  private timer: unknown;
  private recordingStartedAt: number | null = null;
  private accumulatedMs = 0;
  private startInProgress = false;
  private stopRequested = false;
  private disposed = false;
  private stopCompleted = false;
  private startGeneration = 0;
  private monitorCleanup: (() => void) | null = null;
  private microphoneCheckInProgress = false;

  constructor(options: ControllerOptions) { this.options = options; }

  getSnapshot(): RecordingSnapshot { return { ...this.snapshot }; }

  private publish(next: Partial<RecordingSnapshot>) {
    if (this.disposed) return;
    this.snapshot = { ...this.snapshot, ...next };
    this.options.onChange(this.getSnapshot());
  }

  private elapsedNow() {
    return this.accumulatedMs + (this.recordingStartedAt === null ? 0 : Math.max(0, this.options.adapter.now() - this.recordingStartedAt));
  }

  private startTimer() {
    if (this.timer !== undefined) return;
    this.timer = this.options.adapter.setInterval(() => this.publish({ elapsedMs: this.elapsedNow() }), ELAPSED_REFRESH_MS);
  }

  private clearTimer() {
    if (this.timer === undefined) return;
    this.options.adapter.clearInterval(this.timer);
    this.timer = undefined;
  }

  private releaseTracks() {
    this.monitorCleanup?.();
    this.monitorCleanup = null;
    const current = this.stream;
    this.stream = null;
    current?.getTracks().forEach((track) => {
      try { track.stop(); } catch { /* best effort cleanup */ }
    });
  }

  private clearRecorderHandlers() {
    if (!this.recorder) return;
    this.recorder.onstart = null;
    this.recorder.onpause = null;
    this.recorder.onresume = null;
    this.recorder.onstop = null;
    this.recorder.onerror = null;
    this.recorder.ondataavailable = null;
  }

  private cleanup() {
    this.clearTimer();
    this.recordingStartedAt = null;
    this.releaseTracks();
  }

  async prepareMicrophone(): Promise<void> {
    if (this.disposed || this.snapshot.state !== 'Ready' || this.stream || this.microphoneCheckInProgress) return;
    this.microphoneCheckInProgress = true;
    this.publish({ microphoneStatus: 'checking', microphoneLevel: 0, microphoneInputDetected: false, errorMessage: undefined });
    let acquired: MediaStreamLike | null = null;
    try {
      if (!this.options.adapter.isMediaRecorderSupported()) {
        this.publish({ microphoneStatus: 'error', errorMessage: ERRORS.unsupported });
        return;
      }
      if (!this.options.uploadPolicy.acceptedMimeTypes.some((type) => this.options.adapter.isTypeSupported(type))) {
        this.publish({ microphoneStatus: 'error', errorMessage: ERRORS.unsupportedMime });
        return;
      }
      acquired = await this.options.adapter.getUserMedia();
      if (this.disposed) {
        acquired.getTracks().forEach((track) => track.stop());
        return;
      }
      this.stream = acquired;
      this.publish({ microphoneStatus: 'ready' });
      try {
        this.monitorCleanup = this.options.adapter.monitorInput?.(acquired, (level) => {
          const normalizedLevel = Math.max(0, Math.min(1, level));
          this.publish({ microphoneLevel: normalizedLevel, microphoneInputDetected: this.snapshot.microphoneInputDetected || normalizedLevel > .025 });
        }) ?? null;
        if (!this.options.adapter.monitorInput) this.publish({ microphoneInputDetected: true });
      } catch {
        this.releaseTracks();
        this.publish({ microphoneStatus: 'error', microphoneLevel: 0, errorMessage: '마이크 입력 상태를 확인할 수 없습니다. 브라우저에서 마이크를 다시 확인해 주세요.' });
      }
    } catch (error) {
      if (acquired && !this.stream) acquired.getTracks().forEach((track) => { try { track.stop(); } catch { /* best effort cleanup */ } });
      this.publish({ microphoneStatus: 'error', microphoneLevel: 0, errorMessage: safeError(error) });
    } finally {
      this.microphoneCheckInProgress = false;
    }
  }

  async start(): Promise<void> {
    if (this.disposed || this.snapshot.state !== 'Ready' || this.startInProgress || this.recorder || this.stream) {
      if (this.snapshot.state !== 'Ready') this.publish({ errorMessage: ERRORS.invalid });
      return;
    }
    this.startInProgress = true;
    const generation = ++this.startGeneration;
    this.stopCompleted = false;
    this.stopRequested = false;
    this.publish({ errorMessage: undefined, completed: false });
    let acquired: MediaStreamLike | null = null;
    try {
      if (this.snapshot.microphoneStatus === 'error') return;
      if (!this.options.adapter.isMediaRecorderSupported()) {
        this.publish({ errorMessage: ERRORS.unsupported });
        return;
      }
      const mimeType = this.options.uploadPolicy.acceptedMimeTypes.find((type) => this.options.adapter.isTypeSupported(type));
      if (!mimeType) {
        this.publish({ errorMessage: ERRORS.unsupportedMime });
        return;
      }
      acquired = this.stream ?? await this.options.adapter.getUserMedia();
      if (this.disposed || generation !== this.startGeneration) {
        acquired.getTracks().forEach((track) => track.stop());
        return;
      }
      this.stream = acquired;
      this.selectedMime = mimeType;
      const recorder = this.options.adapter.createRecorder(acquired, { mimeType });
      this.recorder = recorder;
      recorder.onstart = () => {
        if (this.disposed || this.recorder !== recorder) return;
        this.accumulatedMs = 0;
        this.recordingStartedAt = this.options.adapter.now();
        this.publish({ state: 'Recording', elapsedMs: 0, errorMessage: undefined });
        this.startTimer();
      };
      recorder.onpause = () => {
        if (this.disposed || this.recorder !== recorder) return;
        this.accumulatedMs = this.elapsedNow();
        this.recordingStartedAt = null;
        this.clearTimer();
        this.publish({ state: 'Paused', elapsedMs: this.accumulatedMs, errorMessage: undefined });
      };
      recorder.onresume = () => {
        if (this.disposed || this.recorder !== recorder) return;
        this.recordingStartedAt = this.options.adapter.now();
        this.publish({ state: 'Recording', elapsedMs: this.accumulatedMs, errorMessage: undefined });
        this.startTimer();
      };
      recorder.ondataavailable = ({ data }) => {
        if (this.disposed || data.size === 0) return;
        this.options.onChunk({ chunkId: this.options.adapter.createChunkId(), blob: data, mimeType: this.selectedMime, recordedAtMs: this.options.adapter.nowEpoch(), recordedElapsedMs: this.elapsedNow() });
      };
      recorder.onstop = () => {
        if (this.disposed || this.stopCompleted) return;
        this.accumulatedMs = this.elapsedNow();
        this.recordingStartedAt = null;
        this.cleanup();
        this.stopCompleted = true;
        this.publish({ state: 'Ready', elapsedMs: this.accumulatedMs, errorMessage: undefined, completed: true });
        this.options.onStopComplete(this.accumulatedMs);
      };
      recorder.onerror = () => {
        if (this.disposed) return;
        this.cleanup();
        this.clearRecorderHandlers();
        this.recorder = null;
        this.publish({ state: 'Ready', errorMessage: ERRORS.general });
      };
      recorder.start(this.options.uploadPolicy.chunkDurationSeconds * 1_000);
    } catch (error) {
      if (acquired && !this.stream) {
        acquired.getTracks().forEach((track) => { try { track.stop(); } catch { /* best effort */ } });
      }
      this.cleanup();
      this.clearRecorderHandlers();
      this.recorder = null;
      this.publish({ state: 'Ready', errorMessage: acquired ? ERRORS.general : safeError(error) });
    } finally {
      if (generation === this.startGeneration) this.startInProgress = false;
    }
  }

  pause(): void {
    if (this.disposed) return;
    if (this.snapshot.state !== 'Recording' || !this.recorder || this.stopRequested) {
      this.publish({ errorMessage: ERRORS.invalid });
      return;
    }
    try { this.recorder.pause(); } catch { this.publish({ errorMessage: ERRORS.general }); }
  }

  resume(): void {
    if (this.disposed) return;
    if (this.snapshot.state !== 'Paused' || !this.recorder || this.stopRequested) {
      this.publish({ errorMessage: ERRORS.invalid });
      return;
    }
    try { this.recorder.resume(); } catch { this.publish({ errorMessage: ERRORS.general }); }
  }

  stop(): void {
    if (this.disposed || this.stopRequested || !this.recorder || (this.snapshot.state !== 'Recording' && this.snapshot.state !== 'Paused')) {
      if (!this.disposed) this.publish({ errorMessage: ERRORS.invalid });
      return;
    }
    this.stopRequested = true;
    try {
      this.recorder.stop();
      if (!this.stopCompleted) {
        this.accumulatedMs = this.elapsedNow();
        this.recordingStartedAt = null;
        this.clearTimer();
      }
    } catch {
      this.stopRequested = false;
      this.publish({ errorMessage: ERRORS.general });
    }
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.startGeneration++;
    this.clearTimer();
    this.recordingStartedAt = null;
    this.releaseTracks();
    const recorder = this.recorder;
    this.clearRecorderHandlers();
    if (recorder && !this.stopRequested && !this.stopCompleted) {
      this.stopRequested = true;
      try { recorder.stop(); } catch { /* stream has already been released */ }
    }
  }
}

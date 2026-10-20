import { describe, expect, it, vi } from 'vitest';
import { RecordingController, type MediaRecorderAdapter, type RecorderLike } from './recordingController';

const acceptedMimeTypes = ['audio/webm;codecs=opus', 'audio/mp4'];
const uploadPolicy = { chunkDurationSeconds: 15, acceptedMimeTypes };

function createFixture(options: {
  supported?: boolean;
  supportedTypes?: string[];
  getUserMedia?: () => Promise<{ getTracks: () => Array<{ stop: () => void }> }>;
  monitorInput?: (stream: { getTracks: () => Array<{ stop: () => void }> }, onLevel: (level: number) => void) => () => void;
  constructionError?: Error;
  recorderStartError?: Error;
  recorderStopError?: Error;
} = {}) {
  let monotonicNow = 0;
  let intervalHandler: (() => void) | undefined;
  const track = { stop: vi.fn() };
  const stream = { getTracks: () => [track] };
  const recorder: RecorderLike & { timeslice?: number } = {
    mimeType: '',
    onstart: null,
    onpause: null,
    onresume: null,
    onstop: null,
    onerror: null,
    ondataavailable: null,
    start: vi.fn((timeslice: number) => {
      recorder.timeslice = timeslice;
      if (options.recorderStartError) throw options.recorderStartError;
    }),
    pause: vi.fn(),
    resume: vi.fn(),
    stop: vi.fn(() => { if (options.recorderStopError) throw options.recorderStopError; }),
  };
  const adapter: MediaRecorderAdapter = {
    isMediaRecorderSupported: vi.fn(() => options.supported ?? true),
    isTypeSupported: vi.fn((mimeType) => options.supportedTypes?.includes(mimeType) ?? true),
    getUserMedia: vi.fn(options.getUserMedia ?? (() => Promise.resolve(stream))),
    ...(options.monitorInput ? { monitorInput: vi.fn(options.monitorInput) } : {}),
    createRecorder: vi.fn((_mediaStream, { mimeType }) => {
      if (options.constructionError) throw options.constructionError;
      recorder.mimeType = mimeType;
      return recorder;
    }),
    now: vi.fn(() => monotonicNow),
    nowEpoch: vi.fn(() => 1_700_000_000_000 + monotonicNow),
    setInterval: vi.fn((handler) => { intervalHandler = handler; return 1; }),
    clearInterval: vi.fn(() => { intervalHandler = undefined; }),
    createChunkId: vi.fn(() => 'chunk-stable-1'),
  };
  const onChange = vi.fn();
  const onChunk = vi.fn();
  const onStopComplete = vi.fn();
  const controller = new RecordingController({ uploadPolicy, adapter, onChange, onChunk, onStopComplete });
  const advanceTo = (time: number) => { monotonicNow = time; intervalHandler?.(); };
  return { adapter, controller, recorder, track, stream, onChange, onChunk, onStopComplete, advanceTo };
}

async function startRecording(fixture: ReturnType<typeof createFixture>) {
  await fixture.controller.start();
  fixture.recorder.onstart?.();
}

describe('RecordingController', () => {
  it('reuses the microphone stream acquired during the preflight when recording starts', async () => {
    const fixture = createFixture();
    expect(fixture.adapter.getUserMedia).not.toHaveBeenCalled();
    await fixture.controller.prepareMicrophone();
    expect(fixture.adapter.getUserMedia).toHaveBeenCalledTimes(1);
    expect(fixture.controller.getSnapshot()).toMatchObject({ microphoneStatus: 'ready', microphoneInputDetected: true });
    await fixture.controller.start();
    expect(fixture.adapter.getUserMedia).toHaveBeenCalledTimes(1);
    expect(fixture.track.stop).not.toHaveBeenCalled();
    expect(fixture.recorder.start).toHaveBeenCalledTimes(1);
    expect(fixture.recorder.timeslice).toBe(15_000);
    expect(fixture.controller.getSnapshot().state).toBe('Ready');
    expect(fixture.adapter.setInterval).not.toHaveBeenCalled();
    fixture.recorder.onstart?.();
    expect(fixture.controller.getSnapshot().state).toBe('Recording');
    expect(fixture.adapter.setInterval).toHaveBeenCalledTimes(1);
    expect(vi.mocked(fixture.adapter.setInterval).mock.calls[0][1]).toBe(1_000);
  });

  it('restarts the input monitor on an explicit retry without reacquiring the microphone', async () => {
    const firstCleanup = vi.fn();
    const monitorInput = vi.fn()
      .mockReturnValueOnce(firstCleanup)
      .mockReturnValueOnce(vi.fn());
    const fixture = createFixture({ monitorInput });

    await fixture.controller.prepareMicrophone();
    await fixture.controller.prepareMicrophone();

    expect(fixture.adapter.getUserMedia).toHaveBeenCalledTimes(1);
    expect(monitorInput).toHaveBeenCalledTimes(2);
    expect(firstCleanup).toHaveBeenCalledTimes(1);
  });

  it('releases the microphone stream if input monitoring cannot be initialized', async () => {
    const fixture = createFixture({ monitorInput: () => { throw new Error('monitor unavailable'); } });

    await fixture.controller.prepareMicrophone();

    expect(fixture.track.stop).toHaveBeenCalledTimes(1);
    expect(fixture.controller.getSnapshot()).toMatchObject({
      microphoneStatus: 'error',
      errorMessage: '마이크 입력 상태를 확인할 수 없습니다. 브라우저에서 마이크를 다시 확인해 주세요.',
    });
  });

  it('shows a safe unsupported-browser error without requesting microphone access', async () => {
    const fixture = createFixture({ supported: false });
    await fixture.controller.start();
    expect(fixture.adapter.getUserMedia).not.toHaveBeenCalled();
    expect(fixture.controller.getSnapshot()).toMatchObject({ state: 'Ready', errorMessage: '이 브라우저에서는 녹음을 지원하지 않습니다.' });
  });

  it('shows a safe MIME error without permission when no accepted type is supported', async () => {
    const fixture = createFixture({ supportedTypes: [] });
    await fixture.controller.start();
    expect(fixture.adapter.isTypeSupported).toHaveBeenCalledTimes(2);
    expect(fixture.adapter.getUserMedia).not.toHaveBeenCalled();
    expect(fixture.controller.getSnapshot()).toMatchObject({ state: 'Ready', errorMessage: '지원되는 녹음 형식이 없습니다.' });
  });

  it('chooses the first supported MIME in API policy order and uses that exact value', async () => {
    const fixture = createFixture({ supportedTypes: [acceptedMimeTypes[1]] });
    await fixture.controller.start();
    expect(vi.mocked(fixture.adapter.isTypeSupported).mock.calls.map(([mime]) => mime)).toEqual(acceptedMimeTypes);
    expect(fixture.adapter.createRecorder).toHaveBeenCalledWith(fixture.stream, { mimeType: acceptedMimeTypes[1] });
    expect(fixture.recorder.mimeType).toBe(acceptedMimeTypes[1]);
  });

  it.each([
    ['NotAllowedError', '마이크 권한이 거부되었습니다. 브라우저에서 마이크 접근을 허용해 주세요.'],
    ['NotFoundError', '사용할 수 있는 마이크를 찾을 수 없습니다.'],
    ['NotReadableError', '마이크를 사용할 수 없습니다. 다른 앱에서 사용 중인지 확인해 주세요.'],
  ])('maps %s to safe user guidance and returns to Ready', async (name, errorMessage) => {
    const fixture = createFixture({ getUserMedia: () => Promise.reject(Object.assign(new Error('private device detail'), { name })) });
    await fixture.controller.start();
    expect(fixture.controller.getSnapshot()).toMatchObject({ state: 'Ready', errorMessage });
    expect(JSON.stringify(fixture.onChange.mock.calls)).not.toContain('private device detail');
    expect(fixture.track.stop).not.toHaveBeenCalled();
  });

  it('changes view state only on recorder start, pause, and resume events', async () => {
    const fixture = createFixture();
    await fixture.controller.start();
    expect(fixture.controller.getSnapshot().state).toBe('Ready');
    fixture.recorder.onstart?.();
    expect(fixture.controller.getSnapshot().state).toBe('Recording');
    fixture.controller.pause();
    expect(fixture.controller.getSnapshot().state).toBe('Recording');
    fixture.recorder.onpause?.();
    expect(fixture.controller.getSnapshot().state).toBe('Paused');
    fixture.controller.resume();
    expect(fixture.controller.getSnapshot().state).toBe('Paused');
    fixture.recorder.onresume?.();
    expect(fixture.controller.getSnapshot().state).toBe('Recording');
  });

  it('accumulates monotonic elapsed time across pauses without advancing while paused', async () => {
    const fixture = createFixture();
    await startRecording(fixture);
    fixture.advanceTo(2_750);
    expect(fixture.controller.getSnapshot().elapsedMs).toBe(2_750);
    fixture.controller.pause();
    fixture.advanceTo(3_000);
    fixture.recorder.onpause?.();
    expect(fixture.controller.getSnapshot()).toMatchObject({ state: 'Paused', elapsedMs: 3_000 });
    fixture.advanceTo(10_000);
    expect(fixture.controller.getSnapshot().elapsedMs).toBe(3_000);
    fixture.controller.resume();
    fixture.advanceTo(10_250);
    fixture.recorder.onresume?.();
    fixture.advanceTo(11_500);
    expect(fixture.controller.getSnapshot().elapsedMs).toBe(4_250);
  });

  it('delivers nonempty 15-second chunks with stable identifiers, selected MIME, and recorded timestamp', async () => {
    const fixture = createFixture();
    await startRecording(fixture);
    fixture.advanceTo(2_750);
    fixture.recorder.ondataavailable?.({ data: new Blob([]) });
    fixture.recorder.ondataavailable?.({ data: new Blob(['audio-data'], { type: acceptedMimeTypes[0] }) });
    expect(fixture.recorder.timeslice).toBe(15_000);
    expect(fixture.onChunk).toHaveBeenCalledTimes(1);
    expect(fixture.onChunk).toHaveBeenCalledWith({
      chunkId: 'chunk-stable-1',
      blob: expect.any(Blob),
      mimeType: acceptedMimeTypes[0],
      recordedAtMs: 1_700_000_002_750,
      recordedElapsedMs: 2_750,
    });
    expect(fixture.adapter.createChunkId).toHaveBeenCalledTimes(1);
  });

  it('delivers the final Blob before stop completion and stops once with cleanup', async () => {
    const fixture = createFixture();
    await startRecording(fixture);
    fixture.advanceTo(5_000);
    fixture.controller.stop();
    fixture.controller.stop();
    expect(fixture.recorder.stop).toHaveBeenCalledTimes(1);
    const finalBlob = new Blob(['final audio']);
    fixture.recorder.ondataavailable?.({ data: finalBlob });
    fixture.recorder.onstop?.();
    expect(fixture.onChunk).toHaveBeenCalledWith(expect.objectContaining({ blob: finalBlob }));
    expect(fixture.onChunk).toHaveBeenCalledWith(expect.objectContaining({ blob: finalBlob, recordedElapsedMs: 5_000 }));
    expect(fixture.onStopComplete).toHaveBeenCalledTimes(1);
    expect(fixture.onChunk.mock.invocationCallOrder[0]).toBeLessThan(fixture.onStopComplete.mock.invocationCallOrder[0]);
    expect(fixture.track.stop).toHaveBeenCalledTimes(1);
    expect(fixture.adapter.clearInterval).toHaveBeenCalledTimes(1);
    expect(fixture.controller.getSnapshot().elapsedMs).toBe(5_000);
  });

  it('cleans up tracks and timer after recorder error and on controller disposal', async () => {
    const failed = createFixture();
    await startRecording(failed);
    failed.advanceTo(2_000);
    failed.recorder.onerror?.({ error: Object.assign(new Error('sensitive detail'), { name: 'UnknownError' }) });
    expect(failed.controller.getSnapshot()).toMatchObject({ state: 'Ready', errorMessage: '녹음 중 오류가 발생했습니다. 다시 시도해 주세요.' });
    expect(failed.track.stop).toHaveBeenCalledTimes(1);
    expect(failed.adapter.clearInterval).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(failed.onChange.mock.calls)).not.toContain('sensitive detail');

    const unmounted = createFixture();
    await startRecording(unmounted);
    unmounted.controller.dispose();
    expect(unmounted.track.stop).toHaveBeenCalledTimes(1);
    expect(unmounted.adapter.clearInterval).toHaveBeenCalledTimes(1);
    expect(unmounted.recorder.stop).toHaveBeenCalledTimes(1);
  });

  it('stops an assigned recorder and releases tracks if unmounted before the start event', async () => {
    const fixture = createFixture();
    await fixture.controller.start();
    expect(fixture.controller.getSnapshot().state).toBe('Ready');
    expect(fixture.adapter.setInterval).not.toHaveBeenCalled();
    fixture.controller.dispose();
    expect(fixture.recorder.stop).toHaveBeenCalledTimes(1);
    expect(fixture.track.stop).toHaveBeenCalledTimes(1);
    expect(fixture.adapter.clearInterval).not.toHaveBeenCalled();
  });

  it('keeps active elapsed time moving when synchronous stop throws', async () => {
    const fixture = createFixture({ recorderStopError: new Error('private stop detail') });
    await startRecording(fixture);
    fixture.advanceTo(1_000);
    fixture.controller.stop();
    expect(fixture.controller.getSnapshot()).toMatchObject({ state: 'Recording', errorMessage: expect.any(String) });
    fixture.advanceTo(2_500);
    expect(fixture.controller.getSnapshot().elapsedMs).toBe(2_500);
    expect(fixture.onStopComplete).not.toHaveBeenCalled();
    expect(fixture.track.stop).not.toHaveBeenCalled();
  });

  it.each([
    ['construction', { constructionError: new Error('private constructor detail') }],
    ['start', { recorderStartError: new Error('private start detail') }],
  ])('releases acquired microphone tracks after synchronous recorder %s failure', async (_failure, errorOptions) => {
    const fixture = createFixture(errorOptions);
    await fixture.controller.start();
    expect(fixture.track.stop).toHaveBeenCalledTimes(1);
    expect(fixture.controller.getSnapshot()).toMatchObject({ state: 'Ready' });
    expect(fixture.adapter.setInterval).not.toHaveBeenCalled();
    expect(JSON.stringify(fixture.onChange.mock.calls)).not.toContain('private');
  });

  it('ignores invalid pause, resume, stop, and duplicate start commands safely', async () => {
    const fixture = createFixture();
    fixture.controller.pause();
    fixture.controller.resume();
    fixture.controller.stop();
    expect(fixture.recorder.pause).not.toHaveBeenCalled();
    expect(fixture.recorder.resume).not.toHaveBeenCalled();
    expect(fixture.recorder.stop).not.toHaveBeenCalled();
    expect(fixture.controller.getSnapshot().state).toBe('Ready');
    expect(fixture.controller.getSnapshot().errorMessage).toBeTruthy();
    await fixture.controller.start();
    await fixture.controller.start();
    expect(fixture.adapter.getUserMedia).toHaveBeenCalledTimes(1);
  });
});

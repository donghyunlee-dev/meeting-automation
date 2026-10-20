import { afterEach, describe, expect, it, vi } from 'vitest';
import { browserMediaAdapter } from './browserMediaAdapter';

describe('browserMediaAdapter microphone monitoring', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('resumes a suspended AudioContext so live microphone input can be detected', () => {
    const source = { connect: vi.fn(), disconnect: vi.fn() };
    const analyser = { fftSize: 0, getByteTimeDomainData: vi.fn(), disconnect: vi.fn() };
    const context = {
      state: 'suspended',
      createMediaStreamSource: vi.fn(() => source),
      createAnalyser: vi.fn(() => analyser),
      resume: vi.fn(async () => { context.state = 'running'; }),
      close: vi.fn(async () => undefined),
    };
    class MockAudioContext {
      constructor() { return context as unknown as AudioContext; }
    }
    vi.stubGlobal('AudioContext', MockAudioContext);

    const cleanup = browserMediaAdapter.monitorInput?.({} as MediaStream, vi.fn());

    expect(context.resume).toHaveBeenCalledTimes(1);
    cleanup?.();
  });
});

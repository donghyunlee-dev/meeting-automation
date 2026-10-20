import type { MediaRecorderAdapter, MediaStreamLike, RecorderLike } from './recordingController';

export const browserMediaAdapter: MediaRecorderAdapter = {
  isMediaRecorderSupported: () => typeof globalThis.MediaRecorder !== 'undefined' && Boolean(navigator.mediaDevices?.getUserMedia),
  isTypeSupported: (mimeType) => globalThis.MediaRecorder?.isTypeSupported(mimeType) ?? false,
  getUserMedia: async () => navigator.mediaDevices.getUserMedia({ audio: true }) as Promise<MediaStreamLike>,
  monitorInput: (stream, onLevel) => {
    const context = new AudioContext();
    const source = context.createMediaStreamSource(stream as MediaStream);
    const analyser = context.createAnalyser();
    analyser.fftSize = 512;
    source.connect(analyser);
    const samples = new Uint8Array(analyser.fftSize);
    const timer = globalThis.setInterval(() => {
      analyser.getByteTimeDomainData(samples);
      let sum = 0;
      for (const sample of samples) {
        const normalized = (sample - 128) / 128;
        sum += normalized * normalized;
      }
      onLevel(Math.min(1, Math.sqrt(sum / samples.length) * 4));
    }, 80);
    return () => {
      globalThis.clearInterval(timer);
      source.disconnect();
      analyser.disconnect();
      void context.close();
    };
  },
  createRecorder: (stream, options) => new MediaRecorder(stream as MediaStream, options) as unknown as RecorderLike,
  now: () => performance.now(),
  nowEpoch: () => Date.now(),
  setInterval: (callback, delayMs) => globalThis.setInterval(callback, delayMs),
  clearInterval: (handle) => globalThis.clearInterval(handle as number),
  createChunkId: () => globalThis.crypto.randomUUID(),
};

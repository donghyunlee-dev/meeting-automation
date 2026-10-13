import type { MediaRecorderAdapter, MediaStreamLike, RecorderLike } from './recordingController';

export const browserMediaAdapter: MediaRecorderAdapter = {
  isMediaRecorderSupported: () => typeof globalThis.MediaRecorder !== 'undefined' && Boolean(navigator.mediaDevices?.getUserMedia),
  isTypeSupported: (mimeType) => globalThis.MediaRecorder?.isTypeSupported(mimeType) ?? false,
  getUserMedia: async () => navigator.mediaDevices.getUserMedia({ audio: true }) as Promise<MediaStreamLike>,
  createRecorder: (stream, options) => new MediaRecorder(stream as MediaStream, options) as unknown as RecorderLike,
  now: () => performance.now(),
  nowEpoch: () => Date.now(),
  setInterval: (callback, delayMs) => globalThis.setInterval(callback, delayMs),
  clearInterval: (handle) => globalThis.clearInterval(handle as number),
  createChunkId: () => globalThis.crypto.randomUUID(),
};

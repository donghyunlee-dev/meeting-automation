import 'fake-indexeddb/auto';
import { describe, expect, it, vi } from 'vitest';
import { ChunkRepository } from './chunkRepository';
import { ChunkUploadCoordinator } from './chunkUploadCoordinator';
import { ProcessingStartApiError, type ProcessingStartRequest, type ProcessingStartResponse } from './processingStartApi';
import { ProcessingAttemptStore, RecordingEndCoordinator } from './recordingEndCoordinator';
// @ts-expect-error Node Blob preserves bytes through fake-indexeddb structured cloning in jsdom.
import { Blob as NodeBlob } from 'node:buffer';

async function createRepository(testName: string) {
  const repository = new ChunkRepository({ dbName: `recording-end-${testName}-${crypto.randomUUID()}`, maxChunkBytes: 1024 });
  await repository.append('session-end', {
    chunkId: 'last-chunk',
    blob: new NodeBlob(['last audio chunk']) as Blob,
    mimeType: 'audio/webm',
    recordedAtMs: 1_700_000_000_000,
    recordedElapsedMs: 4_250,
  });
  return repository;
}

function response(sessionId: string): ProcessingStartResponse {
  return { sessionId, status: 'PROCESSING', stage: 'QUEUED' };
}

describe('RecordingEndCoordinator', () => {
  it('reconciles AUDIO_CHUNKS_INCOMPLETE and requires a deliberate same-key retry before navigation', async () => {
    const repository = await createRepository('incomplete');
    try {
      const events: string[] = [];
      const uploader = new ChunkUploadCoordinator({
        repository,
        api: {
          getReceivedSequences: async () => { events.push('API-008'); return []; },
          putChunk: async (_sessionId, chunk) => { events.push('API-007'); return { sequence: chunk.sequence, received: true }; },
        },
        sleep: async () => undefined,
      });
      const createKey = vi.fn(() => 'stable-processing-key');
      const requests: Array<{ request: ProcessingStartRequest; key: string }> = [];
      const startProcessing = vi.fn(async (request: ProcessingStartRequest, key: string) => {
        events.push('API-009');
        requests.push({ request: { ...request }, key });
        if (requests.length === 1) throw new ProcessingStartApiError('AUDIO_CHUNKS_INCOMPLETE', 409, false);
        return response('session-end');
      });
      const navigateToProcessing = vi.fn();
      const coordinator = new RecordingEndCoordinator({
        sessionId: 'session-end',
        stopRecorderAndWait: async () => 4_250,
        waitForPersistence: async () => undefined,
        flushUploads: () => uploader.uploadPending('session-end'),
        reconcileUploads: () => uploader.reconcile('session-end'),
        getAudioState: () => repository.getPending('session-end'),
        startProcessing,
        clearLocalAudio: () => repository.clearSessionAudio('session-end'),
        navigateToProcessing,
        attemptStore: new ProcessingAttemptStore(),
        createIdempotencyKey: createKey,
      });

      await coordinator.start();

      expect(coordinator.getSnapshot().status).toBe('incomplete');
      expect(startProcessing).toHaveBeenCalledTimes(1);
      expect(events).toEqual(['API-008', 'API-007', 'API-009', 'API-008', 'API-007']);
      expect(navigateToProcessing).not.toHaveBeenCalled();
      expect((await repository.getRecoverable('session-end')).chunks).toHaveLength(1);

      await coordinator.start();

      expect(coordinator.getSnapshot().status).toBe('complete');
      expect(createKey).toHaveBeenCalledTimes(1);
      expect(requests).toEqual([
        { request: { expectedChunks: 1, durationMs: 4_250, mimeType: 'audio/webm' }, key: 'stable-processing-key' },
        { request: { expectedChunks: 1, durationMs: 4_250, mimeType: 'audio/webm' }, key: 'stable-processing-key' },
      ]);
      expect(navigateToProcessing).toHaveBeenCalledExactlyOnceWith('session-end');
      expect((await repository.getRecoverable('session-end')).chunks).toHaveLength(0);
    } finally {
      repository.close();
    }
  });

  it('does not automatically retry a definitive API-009 4xx and retains the ACKed recovery audio', async () => {
    const repository = await createRepository('client-error');
    try {
      const uploader = new ChunkUploadCoordinator({
        repository,
        api: {
          getReceivedSequences: async () => [],
          putChunk: async (_sessionId, chunk) => ({ sequence: chunk.sequence, received: true }),
        },
        sleep: async () => undefined,
      });
      const startProcessing = vi.fn(async () => { throw new ProcessingStartApiError('INVALID_SESSION_STATE', 409, false); });
      const navigateToProcessing = vi.fn();
      const coordinator = new RecordingEndCoordinator({
        sessionId: 'session-end',
        stopRecorderAndWait: async () => 4_250,
        waitForPersistence: async () => undefined,
        flushUploads: () => uploader.uploadPending('session-end'),
        reconcileUploads: () => uploader.reconcile('session-end'),
        getAudioState: () => repository.getPending('session-end'),
        startProcessing,
        clearLocalAudio: () => repository.clearSessionAudio('session-end'),
        navigateToProcessing,
      });

      await coordinator.start();

      expect(coordinator.getSnapshot().status).toBe('failed');
      expect(startProcessing).toHaveBeenCalledTimes(1);
      expect(navigateToProcessing).not.toHaveBeenCalled();
      expect((await repository.getRecoverable('session-end')).chunks.map(({ uploadState, blob }) => [uploadState, blob.size])).toEqual([
        ['ACKNOWLEDGED', new Blob(['last audio chunk']).size],
      ]);
    } finally {
      repository.close();
    }
  });

  it('merges concurrent end confirmations into one in-flight stop and processing submission', async () => {
    const repository = await createRepository('duplicate');
    try {
      const uploader = new ChunkUploadCoordinator({
        repository,
        api: { getReceivedSequences: async () => [], putChunk: async (_sessionId, chunk) => ({ sequence: chunk.sequence, received: true }) },
        sleep: async () => undefined,
      });
      let finishStop!: (durationMs: number) => void;
      const stopRecorderAndWait = vi.fn(() => new Promise<number>((resolve) => { finishStop = resolve; }));
      const startProcessing = vi.fn(async () => response('session-end'));
      const coordinator = new RecordingEndCoordinator({
        sessionId: 'session-end',
        stopRecorderAndWait,
        waitForPersistence: async () => undefined,
        flushUploads: () => uploader.uploadPending('session-end'),
        reconcileUploads: () => uploader.reconcile('session-end'),
        getAudioState: () => repository.getPending('session-end'),
        startProcessing,
        clearLocalAudio: () => repository.clearSessionAudio('session-end'),
        navigateToProcessing: () => undefined,
        createIdempotencyKey: () => 'duplicate-key',
      });

      const first = coordinator.start();
      const duplicate = coordinator.start();
      expect(duplicate).toBe(first);
      finishStop(4_250);
      await Promise.all([first, duplicate]);

      expect(stopRecorderAndWait).toHaveBeenCalledTimes(1);
      expect(startProcessing).toHaveBeenCalledTimes(1);
    } finally {
      repository.close();
    }
  });
});

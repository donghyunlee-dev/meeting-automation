import 'fake-indexeddb/auto';
import { describe, expect, it, vi } from 'vitest';
import { ChunkUploadCoordinator } from './chunkUploadCoordinator';
import { ChunkRepository, type StoredChunk } from './chunkRepository';
import { AudioUploadApiError, getReceivedAudioStatus, putAudioChunk } from './audioUploadApi';
// @ts-expect-error Node Blob preserves bytes through fake-indexeddb structured cloning in jsdom.
import { Blob as NodeBlob } from 'node:buffer';
// @ts-expect-error Node WebCrypto exercises the production digest path in jsdom.
import { webcrypto } from 'node:crypto';

function stored(sequence: number, chunkId = `chunk-${sequence}`): StoredChunk {
  const blob = new Blob([`audio-${sequence}`], { type: 'audio/webm' });
  return { sessionId: 'session-1', sequence, chunkId, blob, mimeType: 'audio/webm', sizeBytes: blob.size, recordedAtMs: sequence, recordedElapsedMs: sequence * 15_000, uploadState: 'PENDING' };
}

function harness(chunks: StoredChunk[], received: number[] = []) {
  const acknowledge = vi.fn(async (_sessionId: string, sequence: number) => {
    chunks = chunks.filter((chunk) => chunk.sequence !== sequence);
  });
  const getPending = vi.fn(async () => ({ session: null, chunks: [...chunks], nextSequence: chunks.length }));
  const getReceivedSequences = vi.fn(async () => [...received]);
  const putChunk = vi.fn(async (_sessionId: string, chunk: StoredChunk) => ({ sequence: chunk.sequence, received: true as const }));
  const sleep = vi.fn((milliseconds: number) => Promise.resolve(milliseconds).then(() => undefined));
  const coordinator = new ChunkUploadCoordinator({ repository: { getPending, acknowledge }, api: { getReceivedSequences, putChunk }, sleep });
  return { coordinator, getPending, acknowledge, getReceivedSequences, putChunk, sleep };
}

describe('ChunkUploadCoordinator', () => {
  it('reconciles API-008 first, ACKs already received local chunks, and PUTs only missing sequences in order', async () => {
    const h = harness([stored(2), stored(0), stored(1)], [0, 2]);

    await h.coordinator.uploadPending('session-1');

    expect(h.getReceivedSequences).toHaveBeenCalledTimes(1);
    expect(h.acknowledge.mock.calls.map((call) => call[1])).toEqual([0, 2, 1]);
    expect(h.putChunk.mock.calls.map((call) => call[1].sequence)).toEqual([1]);
  });

  it('serially uploads each chunk and deletes a chunk only after its matching ACK', async () => {
    const h = harness([stored(0), stored(1), stored(2)]);
    h.putChunk.mockImplementation(async (_id, item) => {
      expect(h.acknowledge.mock.calls.map((call) => call[1])).toEqual(Array.from({ length: item.sequence }, (_, index) => index));
      return { sequence: item.sequence, received: true };
    });

    await h.coordinator.uploadPending('session-1');

    expect(h.putChunk.mock.calls.map((call) => call[1].sequence)).toEqual([0, 1, 2]);
    expect(h.acknowledge.mock.calls.map((call) => call[1])).toEqual([0, 1, 2]);
  });

  it('serializes overlapping upload requests for the same session', async () => {
    const h = harness([stored(0)]);
    let finishUpload!: () => void;
    let markStarted!: () => void;
    const started = new Promise<void>((resolve) => { markStarted = resolve; });
    const blocked = new Promise<void>((resolve) => { finishUpload = resolve; });
    let inFlight = 0;
    let maxInFlight = 0;
    h.putChunk.mockImplementation(async (_id, item) => {
      inFlight += 1;
      maxInFlight = Math.max(maxInFlight, inFlight);
      markStarted();
      await blocked;
      inFlight -= 1;
      return { sequence: item.sequence, received: true };
    });

    const first = h.coordinator.uploadPending('session-1');
    await started;
    const second = h.coordinator.uploadPending('session-1');
    finishUpload();
    await Promise.all([first, second]);

    expect(maxInFlight).toBe(1);
    expect(h.putChunk).toHaveBeenCalledTimes(1);
    expect(h.acknowledge.mock.calls.map((call) => call[1])).toEqual([0]);
  });

  it('recovers a lost PUT acknowledgement by querying API-008 before any resend', async () => {
    const h = harness([stored(0)]);
    h.getReceivedSequences.mockResolvedValueOnce([]).mockResolvedValueOnce([0]);
    h.putChunk.mockRejectedValueOnce(new TypeError('connection lost'));

    await h.coordinator.uploadPending('session-1');

    expect(h.putChunk).toHaveBeenCalledTimes(1);
    expect(h.getReceivedSequences).toHaveBeenCalledTimes(2);
    expect(h.acknowledge.mock.calls.map((call) => call[1])).toEqual([0]);
  });

  it('reconciles an API timeout before resending the same chunk', async () => {
    const h = harness([stored(0)]);
    const events: string[] = [];
    h.getReceivedSequences.mockImplementation(async () => { events.push('API-008'); return []; });
    h.putChunk.mockImplementationOnce(async () => {
      events.push('API-007');
      throw new AudioUploadApiError('REQUEST_TIMEOUT', undefined, true);
    }).mockImplementationOnce(async (_id, item) => {
      events.push('API-007');
      return { sequence: item.sequence, received: true };
    });

    const result = await h.coordinator.uploadPending('session-1');

    expect(events).toEqual(['API-008', 'API-007', 'API-008', 'API-007']);
    expect(h.putChunk.mock.calls.map((call) => call[1].chunkId)).toEqual(['chunk-0', 'chunk-0']);
    expect(result.status).toBe('complete');
  });

  it.each([408, 429, 503])('uses at most five attempts with bounded backoff for HTTP %i then leaves data for manual retry', async (status) => {
    const h = harness([stored(0)]);
    h.putChunk.mockRejectedValue(Object.assign(new Error(`temporary ${status}`), { status }));

    const result = await h.coordinator.uploadPending('session-1');

    expect(h.putChunk).toHaveBeenCalledTimes(5);
    expect(h.sleep.mock.calls.map((call) => call[0])).toEqual([1_000, 2_000, 4_000, 8_000]);
    expect(h.putChunk.mock.calls.every((call) => call[1] === h.putChunk.mock.calls[0][1])).toBe(true);
    expect(result.status).toBe('retryable');
    expect(h.acknowledge).not.toHaveBeenCalled();
  });

  it('does not automatically retry a definitive 4xx and preserves that chunk', async () => {
    const h = harness([stored(0)]);
    h.putChunk.mockRejectedValue(Object.assign(new Error('conflict'), { status: 409, code: 'CHUNK_CONFLICT' }));

    const result = await h.coordinator.uploadPending('session-1');

    expect(h.putChunk).toHaveBeenCalledTimes(1);
    expect(h.sleep).not.toHaveBeenCalled();
    expect(result.status).toBe('failed');
    expect(h.acknowledge).not.toHaveBeenCalled();
  });

  it('does not retry non-network local failures such as digest preparation errors', async () => {
    const h = harness([stored(0)]);
    h.putChunk.mockRejectedValue(Object.assign(new Error('digest unavailable'), { code: 'DIGEST_UNAVAILABLE' }));

    const result = await h.coordinator.uploadPending('session-1');

    expect(h.putChunk).toHaveBeenCalledTimes(1);
    expect(h.sleep).not.toHaveBeenCalled();
    expect(result.status).toBe('failed');
    expect(h.acknowledge).not.toHaveBeenCalled();
  });

  it('preserves local audio and disables automatic retries when the backend session is missing', async () => {
    const h = harness([stored(0)]);
    h.getReceivedSequences.mockRejectedValue(Object.assign(new Error('missing'), { code: 'SESSION_NOT_FOUND' }));

    const result = await h.coordinator.uploadPending('session-1');

    expect(result.status).toBe('session-not-found');
    expect(h.putChunk).not.toHaveBeenCalled();
    expect(h.acknowledge).not.toHaveBeenCalled();
  });

  it('manual retry reconciles remote state and uploads only remaining chunks', async () => {
    const h = harness([stored(0), stored(1)]);
    h.putChunk.mockRejectedValue(new TypeError('offline'));
    await h.coordinator.uploadPending('session-1');
    h.getReceivedSequences.mockResolvedValueOnce([0]);
    h.putChunk.mockResolvedValueOnce({ sequence: 1, received: true });

    const result = await h.coordinator.retryManually('session-1');

    expect(h.getReceivedSequences.mock.calls.length).toBeGreaterThan(1);
    expect(h.putChunk.mock.calls.map((call) => call[1].sequence)).toEqual([0, 0, 0, 0, 0, 1]);
    expect(result.status).toBe('complete');
  });

  it('with IndexedDB, deletes only a remotely received sequence and retains the failed pending Blob', async () => {
    const repository = new ChunkRepository({ dbName: `upload-ack-${crypto.randomUUID()}`, maxChunkBytes: 1024 });
    try {
      await repository.append('session-1', { ...stored(0), blob: new NodeBlob(['audio-0']) as Blob, recordedAtMs: 1 });
      await repository.append('session-1', { ...stored(1), blob: new NodeBlob(['audio-1']) as Blob, recordedAtMs: 2 });
      const coordinator = new ChunkUploadCoordinator({
        repository,
        api: {
          getReceivedSequences: async () => [],
          putChunk: async (_sessionId, chunk) => {
            if (chunk.sequence === 1) throw Object.assign(new Error('validation failed'), { status: 422 });
            return { sequence: chunk.sequence, received: true };
          },
        },
        sleep: async () => undefined,
      });

      const result = await coordinator.uploadPending('session-1');
      const pending = await repository.getPending('session-1');

      expect(result.status).toBe('failed');
      expect(pending.chunks.map(({ sequence, chunkId, blob }) => [sequence, chunkId, blob.size])).toEqual([[1, 'chunk-1', new Blob(['audio-1']).size]]);
    } finally {
      repository.close();
    }
  });

  it('with IndexedDB, does not report completion when local ACK deletion fails', async () => {
    const repository = new ChunkRepository({ dbName: `upload-ack-fail-${crypto.randomUUID()}`, maxChunkBytes: 1024 });
    try {
      await repository.append('session-1', { ...stored(0), blob: new NodeBlob(['audio-0']) as Blob, recordedAtMs: 1 });
      const coordinator = new ChunkUploadCoordinator({
        repository: {
          getPending: repository.getPending.bind(repository),
          acknowledge: async () => { throw new Error('IndexedDB transaction aborted'); },
        },
        api: { getReceivedSequences: async () => [0], putChunk: async (_id, item) => ({ sequence: item.sequence, received: true }) },
        sleep: async () => undefined,
      });

      const result = await coordinator.uploadPending('session-1');
      const pending = await repository.getPending('session-1');

      expect(result.status).toBe('failed');
      expect(pending.chunks.map(({ sequence, chunkId }) => [sequence, chunkId])).toEqual([[0, 'chunk-0']]);
    } finally {
      repository.close();
    }
  });

  it('with IndexedDB, preserves the pending Blob when an API timeout cannot be reconciled', async () => {
    const repository = new ChunkRepository({ dbName: `upload-timeout-${crypto.randomUUID()}`, maxChunkBytes: 1024 });
    try {
      await repository.append('session-1', { ...stored(0), blob: new NodeBlob(['audio-timeout']) as Blob, recordedAtMs: 1 });
      let statusReads = 0;
      const coordinator = new ChunkUploadCoordinator({
        repository,
        api: {
          getReceivedSequences: async () => {
            statusReads += 1;
            if (statusReads === 1) return [];
            throw new AudioUploadApiError('REQUEST_TIMEOUT', undefined, true);
          },
          putChunk: async () => { throw new AudioUploadApiError('REQUEST_TIMEOUT', undefined, true); },
        },
        sleep: async () => undefined,
      });

      const result = await coordinator.uploadPending('session-1');
      const pending = await repository.getPending('session-1');

      expect(result.status).toBe('retryable');
      expect(statusReads).toBe(6);
      expect(pending.chunks.map(({ sequence, chunkId, blob }) => [sequence, chunkId, blob.size])).toEqual([[0, 'chunk-0', new Blob(['audio-timeout']).size]]);
    } finally {
      repository.close();
    }
  });

  it('with IndexedDB, treats a timed-out 422 body as terminal and retains its local Blob', async () => {
    const repository = new ChunkRepository({ dbName: `upload-422-timeout-${crypto.randomUUID()}`, maxChunkBytes: 1024 });
    try {
      await repository.append('session-1', { ...stored(0), blob: new NodeBlob(['audio-422']) as Blob, recordedAtMs: 1 });
      const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
        if (String(input).endsWith('/audio')) return new Response(JSON.stringify({ data: { receivedSequences: [], receivedChunks: 0, totalBytes: 0 } }), { status: 200 });
        return { ok: false, status: 422, json: () => new Promise<unknown>(() => undefined) } as Response;
      });
      vi.stubGlobal('crypto', webcrypto);
      const coordinator = new ChunkUploadCoordinator({
        repository,
        api: {
          getReceivedSequences: async (sessionId) => (await getReceivedAudioStatus(sessionId, fetchMock as typeof fetch, { timeoutMs: 5 })).receivedSequences,
          putChunk: async (_sessionId, chunk) => putAudioChunk(chunk, fetchMock as typeof fetch, { timeoutMs: 5 }),
        },
        sleep: async () => undefined,
      });

      const result = await coordinator.uploadPending('session-1');
      const pending = await repository.getPending('session-1');

      expect(result.status).toBe('failed');
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(pending.chunks.map(({ sequence, chunkId, blob }) => [sequence, chunkId, blob.size])).toEqual([[0, 'chunk-0', new Blob(['audio-422']).size]]);
    } finally {
      repository.close();
    }
  });

  it('keeps ACKed audio for API-009 recovery without re-uploading it during an ordinary flush', async () => {
    const repository = new ChunkRepository({ dbName: `upload-recovery-${crypto.randomUUID()}`, maxChunkBytes: 1024 });
    try {
      await repository.append('session-recovery', {
        chunkId: 'retained-chunk', blob: new NodeBlob(['retained audio']) as Blob, mimeType: 'audio/webm',
        recordedAtMs: 1, recordedElapsedMs: 4_200,
      });
      await repository.acknowledge('session-recovery', 0);
      const getReceivedSequences = vi.fn(async () => []);
      const putChunk = vi.fn(async (_sessionId: string, chunk: StoredChunk) => ({ sequence: chunk.sequence, received: true as const }));
      const coordinator = new ChunkUploadCoordinator({
        repository,
        api: { getReceivedSequences, putChunk },
        sleep: async () => undefined,
      });

      const ordinaryFlush = await coordinator.uploadPending('session-recovery');

      expect(ordinaryFlush.status).toBe('complete');
      expect(getReceivedSequences).not.toHaveBeenCalled();
      expect(putChunk).not.toHaveBeenCalled();
      expect((await repository.getRecoverable('session-recovery')).chunks.map(({ uploadState }) => uploadState)).toEqual(['ACKNOWLEDGED']);

      const reconciliation = await coordinator.reconcile('session-recovery');

      expect(reconciliation.status).toBe('complete');
      expect(getReceivedSequences).toHaveBeenCalledTimes(1);
      expect(putChunk.mock.calls.map(([, chunk]) => [chunk.sequence, chunk.uploadState])).toEqual([[0, 'ACKNOWLEDGED']]);
    } finally {
      repository.close();
    }
  });

  it('turns repository-open failures into a visible terminal snapshot instead of rejecting', async () => {
    const h = harness([]);
    h.getPending.mockRejectedValue(new Error('IndexedDB unavailable'));

    await expect(h.coordinator.uploadPending('session-1')).resolves.toMatchObject({ status: 'failed', message: expect.any(String) });
  });
});

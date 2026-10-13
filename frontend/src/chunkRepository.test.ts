import 'fake-indexeddb/auto';
// @ts-expect-error Node's Blob is used so fake IndexedDB exercises structured cloning like a browser.
import { Blob as NodeBlob } from 'node:buffer';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ChunkRecorderAdapter } from './chunkRecorderAdapter';
import { ChunkRepository, type RecordingChunkInput } from './chunkRepository';

let repository: ChunkRepository | undefined;

afterEach(() => {
  repository?.close();
  repository = undefined;
});

function createRepository(options: { maxChunkBytes?: number; indexedDB?: IDBFactory | null } = {}) {
  repository = new ChunkRepository({
    dbName: `chunk-store-${crypto.randomUUID()}`,
    maxChunkBytes: options.maxChunkBytes ?? 1_024,
    indexedDB: options.indexedDB,
  });
  return repository;
}

function makeChunk(chunkId: string, contents = chunkId, recordedElapsedMs = 0): RecordingChunkInput & { recordedElapsedMs: number } {
  return { chunkId, blob: new NodeBlob([contents], { type: 'audio/webm' }), mimeType: 'audio/webm', recordedAtMs: 1_700_000_000_000, recordedElapsedMs };
}

describe('ChunkRepository', () => {
  it('atomically stores the first chunk at sequence zero with session metadata', async () => {
    const store = createRepository();
    const result = await store.append('session-a', makeChunk('chunk-a', 'chunk-a', 4_200));
    const pending = await store.getPending('session-a');

    expect(result).toEqual({ sequence: 0, duplicate: false });
    expect(pending.nextSequence).toBe(1);
    expect(pending.chunks).toHaveLength(1);
    expect(pending.chunks[0]).toMatchObject({ sessionId: 'session-a', sequence: 0, chunkId: 'chunk-a', mimeType: 'audio/webm', sizeBytes: 7, recordedAtMs: 1_700_000_000_000, uploadState: 'PENDING' });
    expect(pending.chunks[0].blob.size).toBe(7);
    expect(pending.session).toMatchObject({ sessionId: 'session-a', nextSequence: 1, pendingCount: 1, mimeType: 'audio/webm', totalRecordingDurationMs: 4_200 });
  });

  it('assigns independent zero-based sequences per session and preserves append order', async () => {
    const store = createRepository();
    const first = await store.append('session-a', makeChunk('a-0', 'a-0', 1_200));
    const second = await store.append('session-a', makeChunk('a-1', 'a-1', 4_600));
    const other = await store.append('session-b', makeChunk('b-0'));

    expect([first.sequence, second.sequence, other.sequence]).toEqual([0, 1, 0]);
    expect((await store.getPending('session-a')).chunks.map(({ sequence }) => sequence)).toEqual([0, 1]);
    expect((await store.getPending('session-a')).session?.totalRecordingDurationMs).toBe(4_600);
  });

  it('serializes concurrent recorder callbacks into unique consecutive sequences', async () => {
    const store = createRepository();
    const outcomes = await Promise.all(Array.from({ length: 8 }, (_, index) => store.append('session-a', makeChunk(`chunk-${index}`))));

    expect(outcomes.map(({ sequence }) => sequence).sort((a, b) => a - b)).toEqual([0, 1, 2, 3, 4, 5, 6, 7]);
    expect((await store.getPending('session-a')).chunks.map(({ sequence }) => sequence)).toEqual([0, 1, 2, 3, 4, 5, 6, 7]);
  });

  it('returns the existing sequence for a repeated session and chunk id without replacing stored audio', async () => {
    const store = createRepository();
    const first = await store.append('session-a', makeChunk('same-id', 'original audio'));
    const retry = await store.append('session-a', makeChunk('same-id', 'different retry payload'));
    const pending = await store.getPending('session-a');

    expect(first).toEqual({ sequence: 0, duplicate: false });
    expect(retry).toEqual({ sequence: 0, duplicate: true });
    expect(pending.nextSequence).toBe(1);
    expect(pending.chunks).toHaveLength(1);
    expect(await pending.chunks[0].blob.text()).toBe('original audio');
  });

  it('recovers sorted pending chunks and the next sequence after closing and reopening the database', async () => {
    const store = createRepository();
    await Promise.all([store.append('session-a', makeChunk('chunk-0')), store.append('session-a', makeChunk('chunk-1'))]);
    const dbName = store.dbName;
    store.close();
    repository = new ChunkRepository({ dbName, maxChunkBytes: 1_024 });

    const recovered = await repository.getPending('session-a');
    expect(recovered.chunks.map(({ sequence, chunkId }) => [sequence, chunkId])).toEqual([[0, 'chunk-0'], [1, 'chunk-1']]);
    expect(recovered.nextSequence).toBe(2);
    expect(await repository.append('session-a', makeChunk('chunk-2'))).toEqual({ sequence: 2, duplicate: false });
  });

  it('deletes only the acknowledged sequence and removes metadata after the final pending chunk', async () => {
    const store = createRepository();
    await store.append('session-a', makeChunk('chunk-0'));
    await store.append('session-a', makeChunk('chunk-1'));

    await store.acknowledge('session-a', 1);
    let pending = await store.getPending('session-a');
    expect(pending.chunks.map(({ sequence }) => sequence)).toEqual([0]);
    expect(pending.session).toMatchObject({ pendingCount: 1, nextSequence: 2 });

    await store.acknowledge('session-a', 0);
    pending = await store.getPending('session-a');
    expect(pending.chunks).toEqual([]);
    expect(pending.session).toBeNull();
    await expect(store.acknowledge('session-a', 0)).resolves.toBeUndefined();
  });

  it.each([
    ['empty', new NodeBlob([]), 1_024, 'EMPTY_CHUNK'],
    ['oversize', new NodeBlob(['x'.repeat(1_025)]), 1_024, 'CHUNK_TOO_LARGE'],
  ] as const)('rejects %s chunks without creating session metadata', async (_name, blob, maxChunkBytes, code) => {
    const store = createRepository({ maxChunkBytes });
    await expect(store.append('session-a', { chunkId: 'invalid', blob, mimeType: 'audio/webm', recordedAtMs: 0, recordedElapsedMs: 0 }))
      .rejects.toMatchObject({ code });
    expect((await store.getPending('session-a')).session).toBeNull();
  });

  it('returns differentiated open and transaction failures without reporting successful persistence', async () => {
    const unavailable = createRepository({ indexedDB: null });
    await expect(unavailable.append('session-a', makeChunk('chunk-a'))).rejects.toMatchObject({ name: 'ChunkStoreError', code: 'OPEN_FAILED' });

    const store = createRepository();
    const originalTransaction = IDBDatabase.prototype.transaction;
    vi.spyOn(IDBDatabase.prototype, 'transaction').mockImplementation(function (this: IDBDatabase, ...args) {
      if (args[1] === 'readwrite') throw new DOMException('blocked write', 'InvalidStateError');
      return originalTransaction.apply(this, args);
    });
    await expect(store.append('session-a', makeChunk('chunk-a'))).rejects.toMatchObject({ code: 'TRANSACTION_FAILED' });
    vi.restoreAllMocks();
    expect((await store.getPending('session-a')).session).toBeNull();
  });

  it('maps quota failures distinctly and leaves both sequence and chunk uncommitted', async () => {
    const store = createRepository();
    const originalPut = IDBObjectStore.prototype.put;
    vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(function (this: IDBObjectStore, value, key) {
      if (this.name === 'sessions') throw new DOMException('storage quota', 'QuotaExceededError');
      return originalPut.call(this, value, key);
    });

    await expect(store.append('session-a', makeChunk('chunk-a'))).rejects.toMatchObject({ code: 'QUOTA_EXCEEDED' });
    vi.restoreAllMocks();
    expect(await store.append('session-a', makeChunk('chunk-a'))).toEqual({ sequence: 0, duplicate: false });
    expect((await store.getPending('session-a')).chunks.map(({ sequence }) => sequence)).toEqual([0]);
  });

  it('rolls back chunk and metadata when a transaction aborts after both writes are queued', async () => {
    const store = createRepository();
    const originalPut = IDBObjectStore.prototype.put;
    vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(function (this: IDBObjectStore, value, key) {
      const request = originalPut.call(this, value, key);
      if (this.name === 'sessions') this.transaction.abort();
      return request;
    });

    await expect(store.append('session-a', makeChunk('chunk-a'))).rejects.toMatchObject({ code: 'TRANSACTION_FAILED' });
    vi.restoreAllMocks();
    expect(await store.getPending('session-a')).toMatchObject({ session: null, chunks: [], nextSequence: 0 });
    expect(await store.append('session-a', makeChunk('chunk-a'))).toEqual({ sequence: 0, duplicate: false });
    expect((await store.getPending('session-a')).session).toMatchObject({ nextSequence: 1, pendingCount: 1 });
  });

  it('marks a backend-missing session as nonrecoverable without deleting its pending blob', async () => {
    const store = createRepository();
    await store.append('memory-only-session', makeChunk('chunk-a'));

    const [reported] = await store.listPendingSessions(new Set(['different-valid-session']));
    const pending = await store.getPending('memory-only-session');
    expect(reported).toMatchObject({ sessionId: 'memory-only-session', recoverable: false, pendingCount: 1 });
    expect(pending.chunks.map(({ chunkId }) => chunkId)).toEqual(['chunk-a']);
    expect(pending.session).not.toBeNull();
  });
});

describe('ChunkRecorderAdapter', () => {
  it('persists callbacks in arrival order and hands successful sequences back to the caller', async () => {
    const store = createRepository();
    const onStored = vi.fn();
    const adapter = new ChunkRecorderAdapter({ repository: store, sessionId: 'session-a', onStored });

    await Promise.all([adapter.accept(makeChunk('chunk-0')), adapter.accept(makeChunk('chunk-1')), adapter.accept(makeChunk('chunk-2'))]);

    expect(onStored.mock.calls.map(([result]) => result.sequence)).toEqual([0, 1, 2]);
    expect((await store.getPending('session-a')).chunks.map(({ chunkId }) => chunkId)).toEqual(['chunk-0', 'chunk-1', 'chunk-2']);
  });

  it('reports persistence errors and continues serializing later callbacks', async () => {
    const store = createRepository({ maxChunkBytes: 4 });
    const onError = vi.fn();
    const adapter = new ChunkRecorderAdapter({ repository: store, sessionId: 'session-a', onError });

    await expect(adapter.accept(makeChunk('oversize', 'too long'))).rejects.toMatchObject({ code: 'CHUNK_TOO_LARGE' });
    expect(await adapter.accept(makeChunk('valid', 'ok'))).toEqual({ sequence: 0, duplicate: false });
    expect(onError).toHaveBeenCalledTimes(1);
    expect((await store.getPending('session-a')).chunks.map(({ chunkId }) => chunkId)).toEqual(['valid']);
  });
});

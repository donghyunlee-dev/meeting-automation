export type RecordingChunkInput = {
  chunkId: string;
  blob: Blob;
  mimeType: string;
  recordedAtMs: number;
  recordedElapsedMs: number;
};

export type StoredChunk = RecordingChunkInput & {
  sessionId: string;
  sequence: number;
  sizeBytes: number;
  uploadState: 'PENDING' | 'ACKNOWLEDGED';
};

export type ChunkSessionMetadata = {
  sessionId: string;
  mimeType: string;
  nextSequence: number;
  pendingCount: number;
  totalRecordingDurationMs: number;
  createdAtMs: number;
  updatedAtMs: number;
};

export type AppendResult = { sequence: number; duplicate: boolean };
export type PendingChunks = { session: ChunkSessionMetadata | null; chunks: StoredChunk[]; nextSequence: number };
export type PendingSessionSummary = { sessionId: string; recoverable: boolean; pendingCount: number };

export type ChunkStoreErrorCode = 'OPEN_FAILED' | 'TRANSACTION_FAILED' | 'QUOTA_EXCEEDED' | 'EMPTY_CHUNK' | 'CHUNK_TOO_LARGE';

export class ChunkStoreError extends Error {
  readonly code: ChunkStoreErrorCode;
  constructor(code: ChunkStoreErrorCode) {
    super(code);
    this.name = 'ChunkStoreError';
    this.code = code;
  }
}

const DB_VERSION = 3;
const CHUNKS = 'chunks';
const SESSIONS = 'sessions';
const SEQUENCES = 'sequences';
const SESSION_INDEX = 'sessionId';
const CHUNK_ID_INDEX = 'sessionId_chunkId';

function mapFailure(error: unknown, fallback: ChunkStoreErrorCode): ChunkStoreError {
  if (error instanceof ChunkStoreError) return error;
  if ((error as { name?: string } | null)?.name === 'QuotaExceededError') return new ChunkStoreError('QUOTA_EXCEEDED');
  return new ChunkStoreError(fallback);
}

export class ChunkRepository {
  readonly dbName: string;
  private readonly maxChunkBytes: number;
  private readonly factory: IDBFactory | undefined;
  private databasePromise: Promise<IDBDatabase> | null = null;

  constructor(options: { dbName: string; maxChunkBytes: number; indexedDB?: IDBFactory | null }) {
    this.dbName = options.dbName;
    this.maxChunkBytes = options.maxChunkBytes;
    this.factory = options.indexedDB === undefined ? globalThis.indexedDB : options.indexedDB ?? undefined;
  }

  private open(): Promise<IDBDatabase> {
    if (this.databasePromise) return this.databasePromise;
    if (!this.factory) return Promise.reject(new ChunkStoreError('OPEN_FAILED'));
    this.databasePromise = new Promise<IDBDatabase>((resolve, reject) => {
      let request: IDBOpenDBRequest;
      try { request = this.factory!.open(this.dbName, DB_VERSION); }
      catch { reject(new ChunkStoreError('OPEN_FAILED')); return; }
      let blocked = false;
      request.onupgradeneeded = () => {
        const db = request.result;
        const hadSequenceStore = db.objectStoreNames.contains(SEQUENCES);
        const chunkStore = db.objectStoreNames.contains(CHUNKS)
          ? request.transaction!.objectStore(CHUNKS)
          : db.createObjectStore(CHUNKS, { keyPath: ['sessionId', 'sequence'] });
        if (!chunkStore.indexNames.contains(SESSION_INDEX)) chunkStore.createIndex(SESSION_INDEX, 'sessionId', { unique: false });
        if (!chunkStore.indexNames.contains(CHUNK_ID_INDEX)) chunkStore.createIndex(CHUNK_ID_INDEX, ['sessionId', 'chunkId'], { unique: true });
        if (!db.objectStoreNames.contains(SESSIONS)) db.createObjectStore(SESSIONS, { keyPath: 'sessionId' });
        if (!hadSequenceStore) {
          const sequenceStore = db.createObjectStore(SEQUENCES, { keyPath: 'sessionId' });
          const legacySessions = request.transaction!.objectStore(SESSIONS).getAll() as IDBRequest<ChunkSessionMetadata[]>;
          legacySessions.onsuccess = () => {
            for (const session of legacySessions.result) {
              sequenceStore.put({ sessionId: session.sessionId, nextSequence: session.nextSequence });
            }
          };
        }
      };
      request.onblocked = () => { blocked = true; reject(new ChunkStoreError('OPEN_FAILED')); };
      request.onerror = () => reject(new ChunkStoreError('OPEN_FAILED'));
      request.onsuccess = () => {
        if (blocked) { request.result.close(); return; }
        request.result.onversionchange = () => request.result.close();
        resolve(request.result);
      };
    }).catch((error: unknown) => {
      this.databasePromise = null;
      throw mapFailure(error, 'OPEN_FAILED');
    });
    return this.databasePromise!;
  }

  private async transaction<T>(stores: string[], mode: IDBTransactionMode, run: (tx: IDBTransaction, setResult: (value: T) => void, setFailure: (error: unknown) => void) => void): Promise<T> {
    const db = await this.open();
    return new Promise<T>((resolve, reject) => {
      let tx: IDBTransaction;
      let result: T;
      let hasResult = false;
      let failure: unknown;
      const setResult = (value: T) => { result = value; hasResult = true; };
      const setFailure = (error: unknown) => { failure = error; };
      try {
        tx = db.transaction(stores, mode);
        tx.oncomplete = () => {
          if (hasResult) resolve(result);
          else reject(new ChunkStoreError('TRANSACTION_FAILED'));
        };
        tx.onabort = () => reject(mapFailure(failure ?? tx.error, 'TRANSACTION_FAILED'));
        tx.onerror = () => { failure ??= tx.error; };
        run(tx, setResult, setFailure);
      } catch (error) {
        failure = error;
        try { tx!.abort(); } catch { reject(mapFailure(error, 'TRANSACTION_FAILED')); }
      }
    });
  }

  append(sessionId: string, chunk: RecordingChunkInput): Promise<AppendResult> {
    if (!sessionId || !chunk.chunkId || chunk.blob.size === 0) return Promise.reject(new ChunkStoreError('EMPTY_CHUNK'));
    if (chunk.blob.size > this.maxChunkBytes) return Promise.reject(new ChunkStoreError('CHUNK_TOO_LARGE'));
    return this.appendTransaction(sessionId, chunk);
  }

  private async appendTransaction(sessionId: string, chunk: RecordingChunkInput): Promise<AppendResult> {
    const db = await this.open();
    return new Promise<AppendResult>((resolve, reject) => {
      let tx: IDBTransaction;
      let result: AppendResult | undefined;
      let failure: unknown;
      try {
        tx = db.transaction([CHUNKS, SESSIONS, SEQUENCES], 'readwrite');
        const chunks = tx.objectStore(CHUNKS);
        const sessions = tx.objectStore(SESSIONS);
        const sequences = tx.objectStore(SEQUENCES);
        const duplicateRequest = chunks.index(CHUNK_ID_INDEX).get([sessionId, chunk.chunkId]) as IDBRequest<StoredChunk | undefined>;
        duplicateRequest.onsuccess = () => {
          const duplicate = duplicateRequest.result;
          if (duplicate) { result = { sequence: duplicate.sequence, duplicate: true }; return; }
          const sessionRequest = sessions.get(sessionId) as IDBRequest<ChunkSessionMetadata | undefined>;
          sessionRequest.onsuccess = () => {
            const previous = sessionRequest.result;
            const sequenceRequest = sequences.get(sessionId) as IDBRequest<{ sessionId: string; nextSequence: number } | undefined>;
            sequenceRequest.onsuccess = () => {
              const nextSequence = sequenceRequest.result?.nextSequence ?? previous?.nextSequence ?? 0;
              const metadata: ChunkSessionMetadata = {
                sessionId,
                mimeType: chunk.mimeType,
                nextSequence: nextSequence + 1,
                pendingCount: (previous?.pendingCount ?? 0) + 1,
                totalRecordingDurationMs: Math.max(previous?.totalRecordingDurationMs ?? 0, chunk.recordedElapsedMs),
                createdAtMs: previous?.createdAtMs ?? chunk.recordedAtMs,
                updatedAtMs: chunk.recordedAtMs,
              };
              const stored: StoredChunk = { ...chunk, sessionId, sequence: nextSequence, sizeBytes: chunk.blob.size, uploadState: 'PENDING' };
              try {
                chunks.add(stored);
                sessions.put(metadata);
                sequences.put({ sessionId, nextSequence: nextSequence + 1 });
                result = { sequence: nextSequence, duplicate: false };
              } catch (error) {
                failure = error;
                try { tx.abort(); } catch { /* transaction already inactive */ }
              }
            };
            sequenceRequest.onerror = () => { failure = sequenceRequest.error; try { tx.abort(); } catch { /* inactive */ } };
          };
          sessionRequest.onerror = () => { failure = sessionRequest.error; try { tx.abort(); } catch { /* inactive */ } };
        };
        duplicateRequest.onerror = () => { failure = duplicateRequest.error; try { tx.abort(); } catch { /* inactive */ } };
        tx.oncomplete = () => result ? resolve(result) : reject(new ChunkStoreError('TRANSACTION_FAILED'));
        tx.onabort = () => reject(mapFailure(failure ?? tx.error, 'TRANSACTION_FAILED'));
        tx.onerror = () => { failure ??= tx.error; };
      } catch (error) {
        reject(mapFailure(error, 'TRANSACTION_FAILED'));
      }
    });
  }

  async getPending(sessionId: string): Promise<PendingChunks> {
    const stored = await this.getStoredSession(sessionId);
    return { ...stored, chunks: stored.chunks.filter(({ uploadState }) => uploadState === 'PENDING') };
  }

  async getRecoverable(sessionId: string): Promise<PendingChunks> {
    return this.getStoredSession(sessionId);
  }

  private async getStoredSession(sessionId: string): Promise<PendingChunks> {
    return this.transaction([CHUNKS, SESSIONS, SEQUENCES], 'readonly', (tx, setResult, setFailure) => {
      const chunksRequest = tx.objectStore(CHUNKS).index(SESSION_INDEX).getAll(sessionId) as IDBRequest<StoredChunk[]>;
      const sessionRequest = tx.objectStore(SESSIONS).get(sessionId) as IDBRequest<ChunkSessionMetadata | undefined>;
      const sequenceRequest = tx.objectStore(SEQUENCES).get(sessionId) as IDBRequest<{ sessionId: string; nextSequence: number } | undefined>;
      let chunks: StoredChunk[] | undefined;
      let session: ChunkSessionMetadata | undefined;
      let sessionLoaded = false;
      let sequence: { sessionId: string; nextSequence: number } | undefined;
      let sequenceLoaded = false;
      const finish = () => {
        if (chunks && sessionLoaded && sequenceLoaded) setResult({ session: session ?? null, chunks: chunks.sort((a, b) => a.sequence - b.sequence), nextSequence: sequence?.nextSequence ?? session?.nextSequence ?? chunks.reduce((next, item) => Math.max(next, item.sequence + 1), 0) });
      };
      chunksRequest.onsuccess = () => { chunks = chunksRequest.result; finish(); };
      sessionRequest.onsuccess = () => { session = sessionRequest.result; sessionLoaded = true; finish(); };
      sequenceRequest.onsuccess = () => { sequence = sequenceRequest.result; sequenceLoaded = true; finish(); };
      chunksRequest.onerror = () => setFailure(chunksRequest.error);
      sessionRequest.onerror = () => setFailure(sessionRequest.error);
      sequenceRequest.onerror = () => setFailure(sequenceRequest.error);
    });
  }

  async acknowledge(sessionId: string, sequence: number): Promise<void> {
    await this.transaction<void>([CHUNKS, SESSIONS], 'readwrite', (tx, setResult, setFailure) => {
      const chunks = tx.objectStore(CHUNKS);
      const sessions = tx.objectStore(SESSIONS);
      const key: IDBValidKey = [sessionId, sequence];
      const target = chunks.get(key) as IDBRequest<StoredChunk | undefined>;
      target.onsuccess = () => {
        const chunk = target.result;
        if (!chunk || chunk.uploadState === 'ACKNOWLEDGED') { setResult(undefined); return; }
        const session = sessions.get(sessionId) as IDBRequest<ChunkSessionMetadata | undefined>;
        session.onsuccess = () => {
          try {
            chunks.put({ ...chunk, uploadState: 'ACKNOWLEDGED' });
            if (session.result) sessions.put({
              ...session.result,
              pendingCount: Math.max(0, session.result.pendingCount - 1),
              updatedAtMs: Date.now(),
            });
            setResult(undefined);
          } catch (error) { setFailure(error); try { tx.abort(); } catch { /* inactive */ } }
        };
        session.onerror = () => setFailure(session.error);
      };
      target.onerror = () => setFailure(target.error);
    });
  }

  async clearSessionAudio(sessionId: string): Promise<void> {
    await this.transaction<void>([CHUNKS, SESSIONS, SEQUENCES], 'readwrite', (tx, setResult, setFailure) => {
      const cursorRequest = tx.objectStore(CHUNKS).index(SESSION_INDEX).openCursor(sessionId);
      cursorRequest.onsuccess = () => {
        const cursor = cursorRequest.result;
        if (cursor) {
          try { cursor.delete(); cursor.continue(); }
          catch (error) { setFailure(error); try { tx.abort(); } catch { /* inactive */ } }
          return;
        }
        try {
          tx.objectStore(SESSIONS).delete(sessionId);
          tx.objectStore(SEQUENCES).delete(sessionId);
          setResult(undefined);
        } catch (error) { setFailure(error); try { tx.abort(); } catch { /* inactive */ } }
      };
      cursorRequest.onerror = () => setFailure(cursorRequest.error);
    });
  }

  async listPendingSessions(validSessionIds?: ReadonlySet<string>): Promise<PendingSessionSummary[]> {
    return this.transaction([CHUNKS, SESSIONS], 'readonly', (tx, setResult, setFailure) => {
      const sessionsRequest = tx.objectStore(SESSIONS).getAll() as IDBRequest<ChunkSessionMetadata[]>;
      sessionsRequest.onsuccess = () => {
        const summaries = sessionsRequest.result.filter(({ pendingCount }) => pendingCount > 0).map(({ sessionId, pendingCount }) => ({
          sessionId,
          pendingCount,
          recoverable: validSessionIds === undefined || validSessionIds.has(sessionId),
        }));
        setResult(summaries);
      };
      sessionsRequest.onerror = () => setFailure(sessionsRequest.error);
    });
  }

  close(): void {
    const current = this.databasePromise;
    this.databasePromise = null;
    void current?.then((db) => db.close(), () => undefined);
  }
}

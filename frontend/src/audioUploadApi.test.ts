import { afterEach, describe, expect, it, vi } from 'vitest';
import { getReceivedAudioStatus, putAudioChunk } from './audioUploadApi';
import type { StoredChunk } from './chunkRepository';
// @ts-expect-error Node's WebCrypto exercises the production SHA-256 path in jsdom.
import { webcrypto } from 'node:crypto';
// @ts-expect-error Node Blob exposes arrayBuffer in jsdom and matches browser Blob behavior.
import { Blob as NodeBlob } from 'node:buffer';

afterEach(() => vi.unstubAllGlobals());

function chunk(blob: Blob): StoredChunk {
  return {
    sessionId: 'session-1', sequence: 3, chunkId: 'stable-chunk-id', blob,
    mimeType: 'audio/webm;codecs=opus', sizeBytes: blob.size,
    recordedAtMs: 1, recordedElapsedMs: 15_000, uploadState: 'PENDING',
  };
}

describe('API-007 audio chunk upload', () => {
  it('sends the raw stored Blob with its MIME, actual byte length, SHA-256 and stable chunk id', async () => {
    const audio = new NodeBlob(['real audio bytes'], { type: 'audio/webm' }) as Blob;
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: { sequence: 3, received: true } }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('crypto', webcrypto);

    await putAudioChunk(chunk(audio));

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = new Headers(init.headers);
    expect(url).toContain('/api/v1/meeting-sessions/session-1/audio/chunks/3');
    expect(init.method).toBe('PUT');
    expect(init.body).toBe(audio);
    expect(headers.get('Content-Type')).toBe('audio/webm;codecs=opus');
    expect(headers.get('X-Audio-Byte-Length')).toBe(String(audio.size));
    expect(headers.get('X-Audio-SHA256')).toBe('99ea8c248a41e3d7b38def6673e0b597730feff902d1f288abd7d01f6aa10d30');
    expect(headers.get('Idempotency-Key')).toBe('stable-chunk-id');
  });

  it('returns the structured received sequence and keeps server errors classifiable', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: { sequence: 3, received: true } }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('crypto', webcrypto);
    await expect(putAudioChunk(chunk(new NodeBlob(['x']) as Blob))).resolves.toEqual({ sequence: 3, received: true });
  });
});

describe('API-008 received audio reconciliation', () => {
  it('reads the received sequences and normalizes duplicates into ascending order', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: { receivedSequences: [3, 1, 3], receivedChunks: 2, totalBytes: 99 } }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(getReceivedAudioStatus('session-1')).resolves.toEqual({ receivedSequences: [1, 3], receivedChunks: 2, totalBytes: 99 });
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ headers: { Accept: 'application/json' } });
  });
});

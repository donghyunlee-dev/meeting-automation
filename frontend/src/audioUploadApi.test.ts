import { afterEach, describe, expect, it, vi } from 'vitest';
import { getReceivedAudioStatus, putAudioChunk } from './audioUploadApi';
import type { StoredChunk } from './chunkRepository';
// @ts-expect-error Node's WebCrypto exercises the production SHA-256 path in jsdom.
import { webcrypto } from 'node:crypto';
// @ts-expect-error Node Blob exposes arrayBuffer in jsdom and matches browser Blob behavior.
import { Blob as NodeBlob } from 'node:buffer';

afterEach(() => vi.unstubAllGlobals());

const uploadMock = vi.fn();

function chunk(blob: Blob): StoredChunk {
  return {
    sessionId: 'session-1', sequence: 3, chunkId: 'stable-chunk-id', blob,
    mimeType: 'audio/webm;codecs=opus', sizeBytes: blob.size,
    recordedAtMs: 1, recordedElapsedMs: 15_000, uploadState: 'PENDING',
  };
}

async function settleWithin<T>(promise: Promise<T>, timeoutMs = 100): Promise<{ kind: 'value'; value: T } | { kind: 'error'; error: unknown } | { kind: 'pending' }> {
  return Promise.race([
    promise.then((value) => ({ kind: 'value' as const, value }), (error: unknown) => ({ kind: 'error' as const, error })),
    new Promise<{ kind: 'pending' }>((resolve) => setTimeout(() => resolve({ kind: 'pending' }), timeoutMs)),
  ]);
}

describe('API-007 audio chunk upload', () => {
  it('uploads privately using a Render-issued grant, then asks Render to confirm the Blob metadata', async () => {
    const audio = new NodeBlob(['real audio bytes'], { type: 'audio/webm' }) as Blob;
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { pathname: 'audio/session-1/chunks/3', clientPayload: 'signed-grant' } }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { sequence: 3, received: true } }), { status: 200 }));
    uploadMock.mockResolvedValue({ url: 'private-blob', downloadUrl: 'private-blob', pathname: 'audio/session-1/chunks/3', contentType: 'audio/webm;codecs=opus', contentDisposition: '', etag: 'etag' });
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('crypto', webcrypto);

    await putAudioChunk(chunk(audio), fetchMock, { blobUpload: uploadMock as never });

    const [grantUrl, grantInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(grantUrl).toContain('/audio/chunks/3/upload-grant');
    expect(grantInit.method).toBe('POST');
    expect(JSON.parse(String(grantInit.body))).toEqual({ mimeType: 'audio/webm;codecs=opus', bytes: audio.size, sha256: '99ea8c248a41e3d7b38def6673e0b597730feff902d1f288abd7d01f6aa10d30' });
    expect(uploadMock).toHaveBeenCalledWith('audio/session-1/chunks/3', audio, expect.objectContaining({ access: 'private', clientPayload: 'signed-grant' }));
    const [confirmUrl, confirmInit] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(confirmUrl).toContain('/audio/chunks/3/confirm');
    expect(confirmInit.method).toBe('POST');
    expect(confirmInit.body).toBeUndefined();
    expect(new Headers(confirmInit.headers).get('X-Audio-SHA256')).toBe('99ea8c248a41e3d7b38def6673e0b597730feff902d1f288abd7d01f6aa10d30');
  });

  it('returns the structured received sequence and keeps server errors classifiable', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { pathname: 'audio/session-1/chunks/3', clientPayload: 'signed-grant' } }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { sequence: 3, received: true } }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('crypto', webcrypto);
    uploadMock.mockResolvedValue({ url: 'private-blob', downloadUrl: 'private-blob', pathname: 'audio/session-1/chunks/3', contentType: 'audio/webm;codecs=opus', contentDisposition: '', etag: 'etag' });
    await expect(putAudioChunk(chunk(new NodeBlob(['x']) as Blob), fetchMock as typeof fetch, { blobUpload: uploadMock as never })).resolves.toEqual({ sequence: 3, received: true });
  });
});

describe('API-008 received audio reconciliation', () => {
  it('reads the received sequences and normalizes duplicates into ascending order', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: { receivedSequences: [3, 1, 3], receivedChunks: 2, totalBytes: 99 } }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(getReceivedAudioStatus('session-1')).resolves.toEqual({ receivedSequences: [1, 3], receivedChunks: 2, totalBytes: 99 });
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ headers: { Accept: 'application/json' } });
  });

  it('aborts a fetch that does not return response headers and classifies the timeout as uncertain', async () => {
    let signal: AbortSignal | undefined;
    const fetchMock = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      signal = init?.signal as AbortSignal;
      return new Promise<Response>(() => undefined);
    });

    const outcome = await settleWithin(getReceivedAudioStatus('session-1', fetchMock as typeof fetch, { timeoutMs: 5 }));

    expect(outcome.kind).toBe('error');
    if (outcome.kind === 'error') expect(outcome.error).toMatchObject({ code: 'REQUEST_TIMEOUT', responseUncertain: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(signal?.aborted).toBe(true);
  });

  it('bounds response.json() after PUT headers arrive and aborts the request with an uncertain result', async () => {
    let signal: AbortSignal | undefined;
    const fetchMock = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      signal = init?.signal as AbortSignal;
      return Promise.resolve({ ok: true, status: 200, json: () => new Promise<unknown>(() => undefined) } as Response);
    });
    vi.stubGlobal('crypto', webcrypto);

    const outcome = await settleWithin(putAudioChunk(chunk(new NodeBlob(['audio']) as Blob), fetchMock as typeof fetch, { timeoutMs: 5 }));

    expect(outcome.kind).toBe('error');
    if (outcome.kind === 'error') expect(outcome.error).toMatchObject({ code: 'REQUEST_TIMEOUT', responseUncertain: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(signal?.aborted).toBe(true);
  });

  it('preserves terminal HTTP 4xx status when its response body stalls', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 422, json: () => new Promise<unknown>(() => undefined) } as Response);
    vi.stubGlobal('crypto', webcrypto);

    const outcome = await settleWithin(putAudioChunk(chunk(new NodeBlob(['audio']) as Blob), fetchMock as typeof fetch, { timeoutMs: 5 }));

    expect(outcome.kind).toBe('error');
    if (outcome.kind === 'error') expect(outcome.error).toMatchObject({ code: 'REQUEST_TIMEOUT', status: 422, responseUncertain: true });
  });
});

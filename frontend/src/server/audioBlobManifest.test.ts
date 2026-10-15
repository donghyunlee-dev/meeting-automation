import { beforeEach, describe, expect, it, vi } from 'vitest';
import { BlobPreconditionFailedError } from '@vercel/blob';
import { safeManifestPath, updateManifestWith } from './audioBlobManifest';

beforeEach(() => vi.restoreAllMocks());

describe('audio manifest storage', () => {
  it('keeps session IDs inside the manifest key namespace', () => {
    expect(safeManifestPath('session-1')).toBe('audio/manifests/session-1.json');
    expect(() => safeManifestPath('../other')).toThrow();
  });

  it('re-reads and retries after a conditional ETag write conflict', async () => {
    const result = (etag: string) => ({ statusCode: 200, stream: new Response('{"sessionId":"s1","objects":[]}').body!, headers: new Headers(), blob: { url: '', downloadUrl: '', pathname: '', contentDisposition: '', cacheControl: '', uploadedAt: new Date(), etag, contentType: 'application/json', size: 0 } });
    const get = vi.fn().mockResolvedValueOnce(result('etag-1')).mockResolvedValueOnce(result('etag-2'));
    const put = vi.fn().mockRejectedValueOnce(new BlobPreconditionFailedError()).mockResolvedValueOnce({});
    const entry = { pathname: 'audio/s1/chunks/0', type: 'CHUNK' as const, sequence: 0, mimeType: 'audio/webm', bytes: 1, sha256: 'a'.repeat(64), expiresAt: '2030-01-01T00:00:00Z' };
    const value = await updateManifestWith('s1', (manifest) => ({ ...manifest, objects: [entry] }), { get, put } as never);

    expect(value.objects).toEqual([entry]);
    expect(put).toHaveBeenCalledTimes(2);
    expect(put.mock.calls[0][2]).toMatchObject({ ifMatch: 'etag-1' });
    expect(put.mock.calls[1][2]).toMatchObject({ ifMatch: 'etag-2' });
  });
});

import { BlobPreconditionFailedError, get, list, put } from '@vercel/blob';

export type AudioBlobEntry = {
  pathname: string;
  type: 'CHUNK' | 'ASSEMBLED';
  sequence: number;
  mimeType: string;
  bytes: number;
  sha256: string;
  expiresAt: string;
  confirmed?: boolean;
};

export type AudioSessionManifest = { sessionId: string; objects: AudioBlobEntry[] };

export function safeManifestPath(sessionId: string): string {
  if (!/^[\w.-]{1,128}$/.test(sessionId) || sessionId === '.' || sessionId === '..') {
    throw new Error('Invalid session ID');
  }
  return `audio/manifests/${sessionId}.json`;
}

export async function readManifest(sessionId: string): Promise<AudioSessionManifest | undefined> {
  const pathname = safeManifestPath(sessionId);
  const result = await get(pathname, { access: 'private' });
  if (!result || result.statusCode !== 200) return undefined;
  const text = await new Response(result.stream).text();
  const value = JSON.parse(text) as AudioSessionManifest;
  if (value.sessionId !== sessionId || !Array.isArray(value.objects)) throw new Error('Invalid audio manifest');
  return value;
}

type ManifestBlobClient = Pick<typeof import('@vercel/blob'), 'get' | 'put'>;

export function updateManifest(sessionId: string, update: (manifest: AudioSessionManifest) => AudioSessionManifest): Promise<AudioSessionManifest> {
  return updateManifestWith(sessionId, update, { get, put });
}

export async function updateManifestWith(sessionId: string, update: (manifest: AudioSessionManifest) => AudioSessionManifest,
  blobClient: ManifestBlobClient): Promise<AudioSessionManifest> {
  const pathname = safeManifestPath(sessionId);
  for (let attempt = 0; attempt < 5; attempt++) {
    const current = await blobClient.get(pathname, { access: 'private' });
    const oldValue = current?.statusCode === 200
      ? JSON.parse(await new Response(current.stream).text()) as AudioSessionManifest
      : { sessionId, objects: [] };
    const next = update(oldValue);
    try {
      await blobClient.put(pathname, JSON.stringify(next), {
        access: 'private', contentType: 'application/json', addRandomSuffix: false,
        ...(current?.statusCode === 200 ? { ifMatch: current.blob.etag } : { allowOverwrite: false }),
      });
      return next;
    } catch (error) {
      if (!(error instanceof BlobPreconditionFailedError) || attempt === 4) throw error;
    }
  }
  throw new Error('Audio manifest update failed');
}

export async function listManifests(): Promise<string[]> {
  const manifests: string[] = [];
  let cursor: string | undefined;
  do {
    const page = await list({ prefix: 'audio/manifests/', cursor });
    manifests.push(...page.blobs.map((blob) => blob.pathname));
    cursor = page.cursor;
  } while (cursor);
  return manifests;
}

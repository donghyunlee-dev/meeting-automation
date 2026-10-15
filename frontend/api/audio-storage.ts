import { del, get, put } from '@vercel/blob';
import { listManifests, readManifest, updateManifest, type AudioBlobEntry } from '../src/server/audioBlobManifest.js';

declare const process: { env: Record<string, string | undefined> };

export async function POST(request: Request): Promise<Response> {
  if (!authorized(request)) return Response.json({ error: 'Unauthorized' }, { status: 401 });
  let body: { operation?: string; sessionId?: string; entry?: AudioBlobEntry; pathname?: string; expiresAt?: string; uploadId?: string; partCount?: number };
  try { body = await request.json(); } catch { return Response.json({ error: 'Invalid request' }, { status: 400 }); }
  try {
    if (body.operation === 'manifest:get' && body.sessionId) {
      return Response.json({ manifest: await readManifest(body.sessionId) ?? null });
    }
    if (body.operation === 'manifest:add' && body.sessionId && body.entry) {
      const manifest = await updateManifest(body.sessionId, (current) => {
        const prior = current.objects.find((item) => item.type === body.entry!.type && item.sequence === body.entry!.sequence);
        if (prior && (prior.sha256 !== body.entry!.sha256 || prior.bytes !== body.entry!.bytes || prior.mimeType !== body.entry!.mimeType)) {
          throw new Error('Conflicting audio object');
        }
        return { ...current, objects: prior
          ? current.objects.map((item) => item.pathname === prior.pathname ? { ...item, confirmed: true } : item)
          : [...current.objects, { ...body.entry!, confirmed: true }] };
      });
      return Response.json({ manifest });
    }
    if (body.operation === 'object:complete' && body.sessionId && body.uploadId && body.entry
      && Number.isInteger(body.partCount) && body.partCount! > 0 && body.partCount! <= 10_000) {
      const entry = body.entry;
      const expectedPath = entry.type === 'ASSEMBLED' ? `audio/${body.sessionId}/assembled` : `audio/${body.sessionId}/chunks/${entry.sequence}`;
      if (!/^audio\/[\w.-]{1,128}\/(?:chunks\/\d+|assembled)$/.test(expectedPath)
        || entry.pathname !== expectedPath || entry.bytes < 1 || !/^[a-f0-9]{64}$/.test(entry.sha256)) {
        return Response.json({ error: 'Invalid object metadata' }, { status: 400 });
      }
      const stream = multipartStream(body.uploadId, body.partCount!);
      await put(entry.pathname, stream, { access: 'private', contentType: entry.mimeType, addRandomSuffix: false, multipart: true });
      for (let index = 0; index < body.partCount!; index++) await del(`audio/staging/${body.uploadId}/${index}`);
      const manifest = await updateManifest(body.sessionId, (current) => {
        const prior = current.objects.find((item) => item.type === entry.type && item.sequence === entry.sequence);
        if (prior && (prior.sha256 !== entry.sha256 || prior.bytes !== entry.bytes || prior.mimeType !== entry.mimeType)) {
          throw new Error('Conflicting audio object');
        }
        return { ...current, objects: prior ? current.objects : [...current.objects, { ...entry, confirmed: true }] };
      });
      return Response.json({ manifest });
    }
    if (body.operation === 'manifest:confirm' && body.sessionId && body.pathname) {
      const manifest = await updateManifest(body.sessionId, (current) => {
        let found = false;
        const objects = current.objects.map((item) => {
          if (item.pathname !== body.pathname) return item;
          found = true;
          return { ...item, confirmed: true };
        });
        if (!found) throw new Error('Audio object not found');
        return { ...current, objects };
      });
      return Response.json({ manifest });
    }
    if (body.operation === 'manifest:extend' && body.sessionId && body.pathname && body.expiresAt) {
      const manifest = await updateManifest(body.sessionId, (current) => {
        const found = current.objects.some((item) => item.pathname === body.pathname);
        if (!found) throw new Error('Audio object not found');
        return { ...current, objects: current.objects.map((item) => item.pathname === body.pathname ? { ...item, expiresAt: body.expiresAt! } : item) };
      });
      return Response.json({ manifest });
    }
    if (body.operation === 'object:delete' && body.sessionId && body.pathname) {
      const manifest = await updateManifest(body.sessionId, (current) => ({
        ...current, objects: current.objects.filter((item) => item.pathname !== body.pathname),
      }));
      await del(body.pathname);
      return Response.json({ deleted: true, manifest });
    }
    if (body.operation === 'object:discardUnconfirmed' && body.sessionId && body.pathname) {
      if (!/^audio\/[\w.-]{1,128}\/chunks\/\d+$/.test(body.pathname)) {
        return Response.json({ error: 'Invalid pathname' }, { status: 400 });
      }
      let discarded = false;
      await updateManifest(body.sessionId, (current) => {
        discarded = false;
        const item = current.objects.find((entry) => entry.pathname === body.pathname);
        if (item?.confirmed) return current;
        discarded = true;
        return { ...current, objects: current.objects.filter((entry) => entry.pathname !== body.pathname) };
      });
      if (discarded) await del(body.pathname);
      return Response.json({ discarded });
    }
    if (body.operation === 'object:deleteExpired' && body.sessionId && body.pathname && body.expiresAt) {
      let deleted = false;
      await updateManifest(body.sessionId, (current) => {
        deleted = false;
        const target = current.objects.find((item) => item.pathname === body.pathname);
        if (!target || target.expiresAt > body.expiresAt!) return current;
        deleted = true;
        return { ...current, objects: current.objects.filter((item) => item.pathname !== body.pathname) };
      });
      if (deleted) await del(body.pathname);
      return Response.json({ deleted });
    }
    if (body.operation === 'manifest:listExpired' && body.expiresAt) {
      const expired: Array<{ sessionId: string; entry: AudioBlobEntry }> = [];
      for (const pathname of await listManifests()) {
        const sessionId = pathname.slice('audio/manifests/'.length, -'.json'.length);
        const manifest = await readManifest(sessionId);
        for (const entry of manifest?.objects ?? []) {
          if (entry.expiresAt <= body.expiresAt) expired.push({ sessionId, entry });
        }
      }
      return Response.json({ expired });
    }
    return Response.json({ error: 'Unsupported operation' }, { status: 400 });
  } catch {
    return Response.json({ error: 'Storage operation failed' }, { status: 502 });
  }
}

export async function GET(request: Request): Promise<Response> {
  if (!authorized(request)) return Response.json({ error: 'Unauthorized' }, { status: 401 });
  const pathname = new URL(request.url).searchParams.get('pathname');
  if (!pathname || !/^audio\/[\w.-]{1,128}\/(?:chunks\/\d+|assembled)$/.test(pathname)) {
    return Response.json({ error: 'Invalid pathname' }, { status: 400 });
  }
  try {
    const result = await get(pathname, { access: 'private' });
    if (!result || result.statusCode !== 200) return new Response(null, { status: 404 });
    return new Response(result.stream, { headers: { 'Content-Type': result.blob.contentType, 'Content-Length': String(result.blob.size) } });
  } catch {
    return new Response(null, { status: 404 });
  }
}

export async function PUT(request: Request): Promise<Response> {
  if (!authorized(request)) return Response.json({ error: 'Unauthorized' }, { status: 401 });
  const uploadId = request.headers.get('x-audio-upload-id');
  const partNumber = request.headers.get('x-audio-part-number');
  if (!uploadId || !/^[a-f0-9-]{36}$/.test(uploadId) || !partNumber || !/^\d{1,4}$/.test(partNumber)) {
    return Response.json({ error: 'Invalid multipart part' }, { status: 400 });
  }
  const buffer = await request.arrayBuffer();
  if (buffer.byteLength < 1 || buffer.byteLength > 4 * 1024 * 1024) return Response.json({ error: 'Invalid part size' }, { status: 413 });
  try {
    await put(`audio/staging/${uploadId}/${partNumber}`, buffer, { access: 'private', contentType: 'application/octet-stream', addRandomSuffix: false });
    return Response.json({ stored: true });
  } catch {
    return Response.json({ error: 'Storage operation failed' }, { status: 502 });
  }
}

function multipartStream(uploadId: string, partCount: number): ReadableStream<Uint8Array> {
  let partNumber = 0;
  let current: ReadableStreamDefaultReader<Uint8Array> | undefined;
  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      while (partNumber < partCount) {
        if (!current) {
          const result = await get(`audio/staging/${uploadId}/${partNumber++}`, { access: 'private' });
          if (!result || result.statusCode !== 200) throw new Error('Missing multipart part');
          current = result.stream.getReader();
        }
        const item = await current.read();
        if (item.done) { current.releaseLock(); current = undefined; continue; }
        controller.enqueue(item.value);
        return;
      }
      controller.close();
    },
    async cancel() { await current?.cancel(); },
  });
}

function authorized(request: Request): boolean {
  const expected = process.env.RENDER_AUDIO_STORAGE_SECRET;
  const supplied = request.headers.get('authorization')?.replace(/^Bearer\s+/i, '');
  if (!expected || !supplied) return false;
  const left = new TextEncoder().encode(expected);
  const right = new TextEncoder().encode(supplied);
  let difference = left.length ^ right.length;
  for (let index = 0; index < Math.max(left.length, right.length); index++) {
    difference |= (left[index] ?? 0) ^ (right[index] ?? 0);
  }
  return difference === 0;
}

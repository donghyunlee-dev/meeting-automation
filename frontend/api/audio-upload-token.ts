import { handleUpload, type HandleUploadBody } from '@vercel/blob/client';
declare const process: { env: Record<string, string | undefined> };

type Grant = {
  sessionId: string;
  sequence: number;
  mimeType: string;
  bytes: number;
  sha256: string;
  expiresAt: number;
};

export async function POST(request: Request, handleUploadImpl: typeof handleUpload = handleUpload): Promise<Response> {
  const body = (await request.json()) as HandleUploadBody;
  try {
    const jsonResponse = await handleUploadImpl({
      request,
      body,
      onBeforeGenerateToken: async (pathname, clientPayload) => {
        const grant = await verifyGrant(pathname, clientPayload ?? undefined);
        return {
          addRandomSuffix: false,
          allowOverwrite: false,
          maximumSizeInBytes: grant.bytes,
          allowedContentTypes: [grant.mimeType],
          validUntil: grant.expiresAt,
          tokenPayload: clientPayload,
        };
      },
    });
    return Response.json(jsonResponse);
  } catch {
    return Response.json({ error: 'Invalid upload grant' }, { status: 401 });
  }
}

async function verifyGrant(pathname: string, clientPayload?: string): Promise<Grant> {
  const secret = process.env.RENDER_AUDIO_GRANT_SECRET;
  if (!secret || !clientPayload || clientPayload.length > 2048) throw new Error('Invalid grant');
  const [encoded, signature, extra] = clientPayload.split('.');
  if (!encoded || !signature || extra) throw new Error('Invalid grant');
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['verify']);
  const signatureBytes = Uint8Array.from(atob(signature.replaceAll('-', '+').replaceAll('_', '/')), (value) => value.charCodeAt(0));
  const encodedBytes = Uint8Array.from(atob(encoded.replaceAll('-', '+').replaceAll('_', '/')), (value) => value.charCodeAt(0));
  if (!await crypto.subtle.verify('HMAC', key, signatureBytes, new TextEncoder().encode(encoded))) throw new Error('Invalid grant');
  const grant = JSON.parse(new TextDecoder().decode(encodedBytes)) as Grant;
  if (!grant.sessionId || !Number.isSafeInteger(grant.sequence) || grant.sequence < 0
    || !/^[\w.-]{1,128}$/.test(grant.sessionId)
    || pathname !== `audio/${grant.sessionId}/chunks/${grant.sequence}`
    || !Number.isSafeInteger(grant.bytes) || grant.bytes < 1 || grant.bytes > 5 * 1024 * 1024
    || !/^[a-f0-9]{64}$/.test(grant.sha256)
    || !Number.isSafeInteger(grant.expiresAt) || grant.expiresAt <= Date.now() || grant.expiresAt > Date.now() + 120_000
    || !/^audio\/[a-z0-9.+-]+(?:;[a-z0-9=._-]+)*$/i.test(grant.mimeType)) throw new Error('Invalid grant');
  return grant;
}

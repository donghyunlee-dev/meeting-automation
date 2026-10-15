import { describe, expect, it, vi } from 'vitest';
import { POST } from '../../api/audio-upload-token';

function encode(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
}

async function signedGrant(pathname = 'audio/s1/chunks/0'): Promise<string> {
  const claims = { sessionId: 's1', sequence: 0, mimeType: 'audio/webm', bytes: 3, sha256: 'a'.repeat(64), expiresAt: Date.now() + 30_000 };
  const encoded = encode(new TextEncoder().encode(JSON.stringify(claims)));
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode('test-secret'), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const signature = encode(new Uint8Array(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(encoded))));
  return `${encoded}.${signature}${pathname === 'audio/s1/chunks/0' ? '' : '.tampered'}`;
}

function request(clientPayload: string, pathname = 'audio/s1/chunks/0'): Request {
  return new Request('https://example.test/api/audio-upload-token', {
    method: 'POST',
    body: JSON.stringify({ type: 'blob.generate-client-token', payload: { pathname, clientPayload, multipart: false } }),
  });
}

type MockTokenOptions = {
  onBeforeGenerateToken: (pathname: string, clientPayload: string | null) => Promise<unknown>;
  body: { payload: { pathname: string; clientPayload: string } };
};

describe('audio upload token route', () => {
  it('rejects requests without a valid Render-signed grant', async () => {
    vi.stubEnv('RENDER_AUDIO_GRANT_SECRET', 'test-secret');
    const response = await POST(request('{}'));
    expect(response.status).toBe(401);
  });

  it('restricts an accepted grant to its private path, MIME type, and byte limit', async () => {
    vi.stubEnv('RENDER_AUDIO_GRANT_SECRET', 'test-secret');
    const handleUpload = vi.fn().mockImplementation(async ({ onBeforeGenerateToken, body }: MockTokenOptions) => ({
      type: 'token',
      token: await onBeforeGenerateToken(body.payload.pathname, body.payload.clientPayload),
    }));
    const response = await POST(request(await signedGrant()), handleUpload as never);
    expect(response.status).toBe(200);
    const options = await handleUpload.mock.results[0].value;
    expect(options.token).toMatchObject({
      allowedContentTypes: ['audio/webm'], maximumSizeInBytes: 3,
      addRandomSuffix: false, allowOverwrite: false,
    });
  });

  it('rejects a signed grant when its requested pathname changes', async () => {
    vi.stubEnv('RENDER_AUDIO_GRANT_SECRET', 'test-secret');
    const handleUpload = vi.fn().mockImplementation(async ({ onBeforeGenerateToken, body }: MockTokenOptions) => ({
      type: 'token', token: await onBeforeGenerateToken(body.payload.pathname, body.payload.clientPayload),
    }));
    const response = await POST(request(await signedGrant(), 'audio/s1/chunks/1'), handleUpload as never);
    expect(response.status).toBe(401);
  });
});

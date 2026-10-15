import { describe, expect, it, vi } from 'vitest';
import { GET, POST } from '../../api/audio-storage';

describe('audio storage control route', () => {
  it('rejects storage control and private reads without the Render service secret', async () => {
    vi.stubEnv('RENDER_AUDIO_STORAGE_SECRET', 'render-only-secret');
    const read = await GET(new Request('https://example.test/api/audio-storage?pathname=audio/s1/chunks/0'));
    const write = await POST(new Request('https://example.test/api/audio-storage', { method: 'POST', body: '{}' }));

    expect(read.status).toBe(401);
    expect(write.status).toBe(401);
  });

  it('rejects an invalid object path after authenticating the Render service', async () => {
    vi.stubEnv('RENDER_AUDIO_STORAGE_SECRET', 'render-only-secret');
    const response = await GET(new Request('https://example.test/api/audio-storage?pathname=../private', {
      headers: { Authorization: 'Bearer render-only-secret' },
    }));

    expect(response.status).toBe(400);
  });
});

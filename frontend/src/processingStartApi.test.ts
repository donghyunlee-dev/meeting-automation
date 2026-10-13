import { describe, expect, it, vi } from 'vitest';
import { ProcessingStartApiError, startProcessing } from './processingStartApi';

describe('startProcessing', () => {
  it('sends the API-009 body and stable idempotency key and accepts only the matching 202 session', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ data: { sessionId: 'session/42', status: 'PROCESSING', stage: 'QUEUED' } }), { status: 202 }));
    const request = { expectedChunks: 3, durationMs: 61_500, mimeType: 'audio/webm' };

    const result = await startProcessing('session/42', request, 'processing-key-42', fetchMock as typeof fetch);

    expect(result).toEqual({ sessionId: 'session/42', status: 'PROCESSING', stage: 'QUEUED' });
    const [url, init] = fetchMock.mock.calls[0] as unknown as [RequestInfo | URL, RequestInit];
    expect(String(url)).toContain('/api/v1/meeting-sessions/session%2F42/process');
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual(request);
    expect(init.headers).toMatchObject({ 'Content-Type': 'application/json', 'Idempotency-Key': 'processing-key-42' });
  });

  it('reports a mismatched 202 session as uncertain and does not accept it', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ data: { sessionId: 'other-session', status: 'PROCESSING', stage: 'QUEUED' } }), { status: 202 }));

    await expect(startProcessing('session-expected', { expectedChunks: 1, durationMs: 1_000, mimeType: 'audio/webm' }, 'same-key', fetchMock as typeof fetch))
      .rejects.toMatchObject({ code: 'PROCESSING_SESSION_MISMATCH', status: 202, responseUncertain: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('does not convert a definitive API-009 4xx into an uncertain response', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ error: { code: 'INVALID_SESSION_STATE' } }), { status: 409 }));

    await expect(startProcessing('session-1', { expectedChunks: 1, durationMs: 1_000, mimeType: 'audio/webm' }, 'key', fetchMock as typeof fetch))
      .rejects.toMatchObject<Partial<ProcessingStartApiError>>({ code: 'INVALID_SESSION_STATE', status: 409, responseUncertain: false });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

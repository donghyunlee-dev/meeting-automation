import { afterEach, describe, expect, it, vi } from 'vitest';
import { getMinutesRegenerationSnapshot, regenerateMinutes } from './minutesRegenerationApi';

afterEach(() => vi.unstubAllGlobals());

const reviewResponse = (overrides: Record<string, unknown> = {}) => ({ data: {
  sessionId: 'review-42', version: 9, status: 'REVIEW',
  meeting: { participantIds: ['p1'] }, speakers: [{ speakerId: 's1', label: 'Speaker 1', participantId: 'p1' }],
  transcript: [{ segmentId: 't1', speakerId: 's1', startMs: 0, endMs: 1_000, text: 'Transcript' }],
  minutes: { templateId: 'weekly', templateVersion: '5', summary: 'New', discussionPoints: [], decisions: [], actionItems: [], followUps: [] },
  allowedActions: ['UPDATE_MINUTES'],
  lastOperation: { type: 'MINUTES_REGENERATION', outcome: 'SUCCEEDED', requestedTemplateId: 'weekly' },
  ...overrides,
} });

describe('minutes regeneration API', () => {
  it('sends one confirmed API-013 intent with the current version and opaque idempotency key', async () => {
    const fetchMock = vi.fn(async (...args: Parameters<typeof fetch>) => {
      void args;
      return new Response(JSON.stringify({ data: {
        sessionId: 'review-42', status: 'PROCESSING', stage: 'DRAFT_REGENERATION', templateId: 'weekly',
      } }), { status: 202 });
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(regenerateMinutes('review-42', 8, 'weekly', 'intent-key-1')).resolves.toEqual({
      sessionId: 'review-42', status: 'PROCESSING', stage: 'DRAFT_REGENERATION', templateId: 'weekly',
    });
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(String(url)).toBe('http://localhost:8080/api/v1/meeting-sessions/review-42/minutes/regenerate');
    expect(init?.method).toBe('POST');
    expect(new Headers(init?.headers).get('If-Match')).toBe('"8"');
    expect(new Headers(init?.headers).get('Idempotency-Key')).toBe('intent-key-1');
    expect(JSON.parse(String(init?.body))).toEqual({ templateId: 'weekly' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('returns a complete API-010 Review snapshot with regeneration outcome for terminal correlation', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(reviewResponse()), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(getMinutesRegenerationSnapshot('review-42', new AbortController().signal)).resolves.toMatchObject({
      sessionId: 'review-42', version: 9, status: 'REVIEW',
      review: {
        minutes: { templateId: 'weekly', summary: 'New' }, speakers: [{ speakerId: 's1' }],
        transcript: [{ segmentId: 't1', text: 'Transcript' }], allowedActions: ['UPDATE_MINUTES'],
      },
      lastOperation: { type: 'MINUTES_REGENERATION', outcome: 'SUCCEEDED', requestedTemplateId: 'weekly' },
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('aborts a stalled POST and reports its acceptance outcome as unknown', async () => {
    let signal: AbortSignal | undefined;
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      void _input;
      signal = init?.signal ?? undefined;
      return new Promise<Response>(() => undefined);
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(regenerateMinutes('review-42', 8, 'weekly', 'intent-key-2', { timeoutMs: 2 })).rejects.toMatchObject({
      name: 'MinutesRegenerationApiError', code: 'NETWORK_ERROR', outcomeUnknown: true,
    });
    expect(signal?.aborted).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

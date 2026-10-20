import { withHealthyBackend } from './testUtils/withHealthyBackend';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ReviewApiError, updateReviewMinutes, type StructuredMinutes } from './reviewApi';

const minutes: StructuredMinutes = {
  templateId: 'weekly',
  templateVersion: '4',
  summary: 'Reviewed summary',
  discussionPoints: ['Topic'],
  decisions: ['Decision'],
  actionItems: [{ ownerParticipantId: 'p1', task: 'Follow up', dueDate: '2026-10-20' }],
  followUps: ['Check next week'],
};

afterEach(() => vi.unstubAllGlobals());

async function settleWithin<T>(promise: Promise<T>, timeoutMs: number) {
  return Promise.race([
    promise.then((value) => ({ kind: 'value' as const, value }), (error: unknown) => ({ kind: 'error' as const, error })),
    new Promise<{ kind: 'pending' }>((resolve) => setTimeout(() => resolve({ kind: 'pending' }), timeoutMs)),
  ]);
}

describe('updateReviewMinutes', () => {
  it('sends the complete Structured Minutes object with the API-010 version precondition', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      void input;
      void init;
      return new Response(JSON.stringify({ data: { version: 8, minutes } }), { status: 200 });
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));

    const result = await updateReviewMinutes('review/42', 7, minutes);

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(String(url)).toBe('http://localhost:8080/api/v1/meeting-sessions/review%2F42/minutes');
    expect(init?.method).toBe('PUT');
    expect(new Headers(init?.headers).get('If-Match')).toBe('"7"');
    expect(JSON.parse(String(init?.body))).toEqual(minutes);
    expect(result).toEqual({ version: 8, minutes });
  });

  it('maps version conflicts to safe structured errors without exposing server messages', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ error: {
      code: 'SESSION_VERSION_CONFLICT', message: 'private provider response', details: { fieldErrors: [] },
    } }), { status: 412 }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));

    await expect(updateReviewMinutes('review-42', 7, minutes)).rejects.toMatchObject({
      name: 'ReviewApiError', code: 'MINUTES_UPDATE_FAILED', apiCode: 'SESSION_VERSION_CONFLICT', status: 412,
    } satisfies Partial<ReviewApiError>);
  });

  it('marks a lost or malformed successful response as an unknown update outcome', async () => {
    const fetchMock = vi.fn(async () => new Response('{not-json', { status: 200 }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));

    await expect(updateReviewMinutes('review-42', 7, minutes)).rejects.toMatchObject({
      name: 'ReviewApiError', code: 'MINUTES_UPDATE_FAILED', outcomeUnknown: true,
    } satisfies Partial<ReviewApiError>);
  });

  it('aborts a stalled update request and reports its commit outcome as unknown', async () => {
    let requestInit: RequestInit | undefined;
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      requestInit = init;
      return new Promise<Response>(() => undefined);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));

    const outcome = await settleWithin(updateReviewMinutes('review-42', 7, minutes, undefined, { timeoutMs: 5 }), 100);

    expect(outcome.kind).toBe('error');
    expect(outcome.kind === 'error' && outcome.error).toMatchObject({
      name: 'ReviewApiError', code: 'MINUTES_UPDATE_FAILED', outcomeUnknown: true,
    });
    expect(requestInit?.signal?.aborted).toBe(true);
  });
});

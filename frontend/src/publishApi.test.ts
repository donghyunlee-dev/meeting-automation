import { afterEach, describe, expect, it, vi } from 'vitest';
import { confirmReview, getPublishSnapshot, publishSession, retryDelivery, PublishApiError } from './publishApi';

function response(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ data }), { status, headers: { 'Content-Type': 'application/json' } });
}

afterEach(() => vi.unstubAllGlobals());

describe('publish API', () => {
  it('retries exactly one delivery with the caller-owned idempotency key and accepts only a 202 pending result', async () => {
    const fetchMock = vi.fn(async () => response({ deliveryId: 'd/1', status: 'PENDING' }, 202));
    vi.stubGlobal('fetch', fetchMock);

    await expect(retryDelivery('session-1', 'd/1', 'retry-key')).resolves.toEqual({ deliveryId: 'd/1', status: 'PENDING' });

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain('/meeting-sessions/session-1/deliveries/d%2F1/retry');
    expect(init.method).toBe('POST');
    expect(new Headers(init.headers).get('Idempotency-Key')).toBe('retry-key');
    expect(init.body).toBeUndefined();
  });

  it('does not accept a non-pending retry response as successful admission', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ deliveryId: 'd1', status: 'SENT' }, 200)));
    await expect(retryDelivery('session-1', 'd1', 'retry-key')).rejects.toMatchObject({ code: 'INVALID_RESPONSE', outcomeUnknown: true });
  });
  it('confirms the current Review version before publishing', async () => {
    const fetchMock = vi.fn(async () => response({ status: 'CONFIRMED', version: 12 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(confirmReview('session/7', 11, 'confirm-key')).resolves.toBe(12);

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain('/api/v1/meeting-sessions/session%2F7/confirm');
    expect(init.method).toBe('POST');
    expect(new Headers(init.headers).get('If-Match')).toBe('"11"');
    expect(new Headers(init.headers).get('Idempotency-Key')).toBe('confirm-key');
    expect(JSON.parse(String(init.body))).toEqual({ confirm: true });
  });

  it('returns validation issue paths without exposing arbitrary error text', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ error: {
      code: 'REVIEW_VALIDATION_FAILED', message: 'private meeting text', details: { issues: [
        { path: 'speakers[0].participantId', code: 'SPEAKER_UNMAPPED' },
        { path: 'minutes.actionItems[0].task', code: 'REQUIRED' },
      ] },
    } }), { status: 422, headers: { 'Content-Type': 'application/json' } })));

    const error = await confirmReview('session-1', 4, 'key').catch((reason: unknown) => reason);
    expect(error).toBeInstanceOf(PublishApiError);
    expect(error).toMatchObject({ code: 'REVIEW_VALIDATION_FAILED', status: 422, issues: [
      { path: 'speakers[0].participantId', code: 'SPEAKER_UNMAPPED' },
      { path: 'minutes.actionItems[0].task', code: 'REQUIRED' },
    ] });
    expect((error as Error).message).not.toContain('private meeting text');
  });

  it('publishes only explicit channel selections using the confirmed version and a fresh key', async () => {
    const fetchMock = vi.fn(async () => response({ sessionId: 'session-1', status: 'PUBLISHING' }, 202));
    vi.stubGlobal('fetch', fetchMock);

    await expect(publishSession('session-1', 12, 'publish-key', ['p1', 'p3'], false)).resolves.toBeUndefined();

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain('/api/v1/meeting-sessions/session-1/publish');
    expect(new Headers(init.headers).get('If-Match')).toBe('"12"');
    expect(new Headers(init.headers).get('Idempotency-Key')).toBe('publish-key');
    expect(JSON.parse(String(init.body))).toEqual({ emailRecipientParticipantIds: ['p1', 'p3'], notificationEnabled: false });
  });

  it('reads full publish status and document reference from API-010', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({
      sessionId: 'session-1', version: 13, status: 'DELIVERING', allowedActions: [],
      meeting: { participantIds: ['p1', 'p2'] },
      deliveries: [
        { deliveryId: 'd1', channel: 'EMAIL', recipientParticipantId: 'p1', status: 'SENT', retryable: false },
        { deliveryId: 'd2', channel: 'EMAIL', recipientParticipantId: 'p2', status: 'FAILED', retryable: true, errorCode: 'EMAIL_FAILED' },
      ],
      document: { documentId: 'doc-1', documentUrl: 'https://docs.example/doc-1' },
    })));

    await expect(getPublishSnapshot('session-1', new AbortController().signal)).resolves.toEqual({
      sessionId: 'session-1', version: 13, status: 'DELIVERING', allowedActions: [],
      meeting: { participantIds: ['p1', 'p2'] },
      deliveries: [
        { deliveryId: 'd1', channel: 'EMAIL', recipientParticipantId: 'p1', status: 'SENT', retryable: false },
        { deliveryId: 'd2', channel: 'EMAIL', recipientParticipantId: 'p2', status: 'FAILED', retryable: true, errorCode: 'EMAIL_FAILED' },
      ],
      document: { documentId: 'doc-1', documentUrl: 'https://docs.example/doc-1' },
    });
  });

  it('accepts older API-010 snapshots without optional recipient fields', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({
      sessionId: 'session-1', version: 13, status: 'DELIVERING', allowedActions: [],
    })));

    await expect(getPublishSnapshot('session-1')).resolves.toMatchObject({
      sessionId: 'session-1', version: 13, status: 'DELIVERING', allowedActions: [],
    });
  });

  it('retains current Review mappings and Minutes from API-010 for the Share dirty-draft guard', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({
      sessionId: 'session-1', version: 13, status: 'REVIEW', meeting: { participantIds: ['p1'] },
      speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p1' }], transcript: [],
      minutes: { templateId: 'weekly', templateVersion: '3', summary: 'Server minutes', discussionPoints: [], decisions: [], actionItems: [], followUps: [] },
      allowedActions: ['CONFIRM'],
    })));

    const snapshot = await getPublishSnapshot('session-1');

    expect(snapshot.review?.speakers).toEqual([{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p1' }]);
    expect(snapshot.review?.minutes?.summary).toBe('Server minutes');
    expect(snapshot.version).toBe(snapshot.review?.version);
  });

  it('marks a network-lost confirm response as uncertain for API-010 reconciliation', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('private transport detail'); }));

    await expect(confirmReview('session-1', 4, 'key')).rejects.toMatchObject({ outcomeUnknown: true, code: 'NETWORK_ERROR' });
  });
});

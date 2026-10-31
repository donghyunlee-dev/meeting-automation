import { describe, expect, it, vi } from 'vitest';
import { finalizeProcessingFailure, getProcessingStatus, ProcessingApiError, retryProcessing } from './processingApi';

describe('getProcessingStatus', () => {
  it('bypasses browser caches when reading live processing status', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response(JSON.stringify({ data: {
      sessionId: 'live', version: 1, status: 'PROCESSING', processing: { stage: 'TRANSCRIPTION' }, allowedActions: [],
    } }), { status: 200 }));

    await getProcessingStatus('live', undefined, fetchMock);

    expect(fetchMock.mock.calls[0]?.[1]?.cache).toBe('no-store');
  });

  it('maps the API-010 data envelope and excludes review payload fields', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => { void input; return new Response(JSON.stringify({ data: {
      sessionId: 'session/a', version: 3, status: 'PROCESSING', processing: { stage: 'DIARIZATION', progressPercent: 100, errorCode: 'PRIVATE_PROVIDER_CODE', audioExpiresAt: null },
      allowedActions: [], audioExpiresAt: null, transcript: { text: 'private partial result' }, minutes: { text: 'private partial result' },
    } }), { status: 200 }); });
    const snapshot = await getProcessingStatus('session/a', undefined, fetchMock);
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/v1/meeting-sessions/session%2Fa');
    expect(snapshot).toEqual({ sessionId: 'session/a', version: 3, status: 'PROCESSING', processing: { stage: 'DIARIZATION', progressPercent: 100, audioExpiresAt: null }, allowedActions: [] });
  });

  it('maps a missing Session to a safe terminal error', async () => {
    await expect(getProcessingStatus('gone', undefined, vi.fn(async () => new Response('', { status: 404 })))).rejects.toMatchObject({ name: 'ProcessingApiError', code: 'SESSION_NOT_FOUND' } satisfies Partial<ProcessingApiError>);
  });

  it('keeps a mismatched response Session ID available for the route guard', async () => {
    const snapshot = await getProcessingStatus('expected', undefined, vi.fn(async () => new Response(JSON.stringify({ data: {
      sessionId: 'other', version: 1, status: 'REVIEW', processing: { stage: 'MINUTES_GENERATION', progressPercent: 100 }, allowedActions: [],
    } }), { status: 200 })));
    expect(snapshot.sessionId).toBe('other');
  });

  it('uses a general stage when the server adds an unknown stage', async () => {
    const snapshot = await getProcessingStatus('future-stage', undefined, vi.fn(async () => new Response(JSON.stringify({ data: {
      sessionId: 'future-stage', version: 1, status: 'PROCESSING', processing: { stage: 'FUTURE_PIPELINE_STEP' }, allowedActions: [],
    } }), { status: 200 })));
    expect(snapshot.processing).toEqual({ audioExpiresAt: null });
  });

  it('rejects a response without the required processing object', async () => {
    await expect(getProcessingStatus('missing-processing', undefined, vi.fn(async () => new Response(JSON.stringify({ data: {
      sessionId: 'missing-processing', version: 1, status: 'PROCESSING', allowedActions: [],
    } }), { status: 200 })))).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('rejects DRAFT_READY as a top-level status', async () => {
    await expect(getProcessingStatus('invalid-status', undefined, vi.fn(async () => new Response(JSON.stringify({ data: {
      sessionId: 'invalid-status', version: 1, status: 'DRAFT_READY', processing: { stage: 'MINUTES_GENERATION', progressPercent: 100 }, allowedActions: [],
    } }), { status: 200 })))).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('maps completion as REVIEW with processing.stage DRAFT_READY', async () => {
    const snapshot = await getProcessingStatus('review-ready', undefined, vi.fn(async () => new Response(JSON.stringify({ data: {
      sessionId: 'review-ready', version: 5, status: 'REVIEW', processing: { stage: 'DRAFT_READY', progressPercent: 100 }, allowedActions: [],
    } }), { status: 200 })));
    expect(snapshot).toMatchObject({ sessionId: 'review-ready', status: 'REVIEW', processing: { stage: 'DRAFT_READY', progressPercent: 100 } });
  });

  it('maps API-010 recovery metadata and only recognized server actions', async () => {
    const snapshot = await getProcessingStatus('failed', undefined, vi.fn(async () => new Response(JSON.stringify({ data: {
      sessionId: 'failed', version: 12, status: 'PROCESSING_FAILED',
      processing: { stage: 'TRANSCRIPTION', retryable: false, audioAvailable: true, audioExpiresAt: '2026-10-15T00:00:00Z', errorCode: 'PRIVATE' },
      allowedActions: ['DOWNLOAD_AUDIO', 'FINALIZE_PROCESSING_FAILURE', 'UNRECOGNIZED'],
    } }), { status: 200 })));
    expect(snapshot.processing).toMatchObject({ stage: 'TRANSCRIPTION', retryable: false, audioAvailable: true, audioExpiresAt: '2026-10-15T00:00:00Z' });
    expect(snapshot.allowedActions).toEqual(['DOWNLOAD_AUDIO', 'FINALIZE_PROCESSING_FAILURE']);
    expect(JSON.stringify(snapshot)).not.toContain('PRIVATE');
  });

  it('sends API-020 with the current version and a fresh idempotency key', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(async () => new Response(JSON.stringify({ data: { sessionId: 'retry-me', status: 'PROCESSING', stage: 'TRANSCRIPTION' } }), { status: 202 }));
    await expect(retryProcessing('retry-me', 9, 'attempt-key', fetchMock)).resolves.toMatchObject({ sessionId: 'retry-me', status: 'PROCESSING' });
    expect(fetchMock.mock.calls[0]?.[0]).toContain('/api/v1/meeting-sessions/retry-me/processing/retry');
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get('If-Match')).toBe('"9"');
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get('Idempotency-Key')).toBe('attempt-key');
  });

  it.each(['DOWNLOADED', 'DISCARDED'] as const)('sends API-022 %s with version and idempotency key', async (audioDisposition) => {
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(async () => new Response('', { status: 202 }));
    await expect(finalizeProcessingFailure('failed', 12, audioDisposition, 'finalize-key', fetchMock)).resolves.toBeUndefined();
    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toEqual({ audioDisposition });
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get('If-Match')).toBe('"12"');
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get('Idempotency-Key')).toBe('finalize-key');
  });
});

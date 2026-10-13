import { describe, expect, it, vi } from 'vitest';
import { getProcessingStatus, ProcessingApiError } from './processingApi';

describe('getProcessingStatus', () => {
  it('maps the API-010 data envelope and excludes review payload fields', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => { void input; return new Response(JSON.stringify({ data: {
      sessionId: 'session/a', version: 3, status: 'PROCESSING', processing: { stage: 'DIARIZATION', progressPercent: 100, errorCode: 'PRIVATE_PROVIDER_CODE', audioExpiresAt: null },
      allowedActions: [], audioExpiresAt: null, transcript: { text: 'private partial result' }, minutes: { text: 'private partial result' },
    } }), { status: 200 }); });
    const snapshot = await getProcessingStatus('session/a', undefined, fetchMock);
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/v1/meeting-sessions/session%2Fa');
    expect(snapshot).toEqual({ sessionId: 'session/a', version: 3, status: 'PROCESSING', processing: { stage: 'DIARIZATION', progressPercent: 100, errorCode: 'PRIVATE_PROVIDER_CODE', audioExpiresAt: null }, allowedActions: [] });
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
});

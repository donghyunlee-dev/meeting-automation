import { withHealthyBackend } from './testUtils/withHealthyBackend';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { listMeetings, listProcessingFailures, MeetingListApiError } from './meetingListApi';

afterEach(() => vi.unstubAllGlobals());

describe('listMeetings', () => {
  it('requests the requested list size and preserves the API summary fields', async () => {
    const items = [{ documentId: 'doc-1', title: 'Planning', meetingAt: '2026-10-14T09:00:00+09:00', participants: [{ id: 'p1', name: 'Ada' }], documentUrl: 'https://private.example/doc' }];
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(async () => new Response(JSON.stringify({ data: { items } }), { status: 200 }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));

    await expect(listMeetings(5)).resolves.toEqual(items);

    expect(String(fetchMock.mock.calls[0]?.[0])).toBe('http://localhost:8080/api/v1/meetings?limit=5');
  });

  it('uses API-017 default maximum of 100 when no limit is supplied', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(async () => new Response(JSON.stringify({ data: { items: [] } }), { status: 200 }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));

    await listMeetings();

    expect(String(fetchMock.mock.calls[0]?.[0])).toBe('http://localhost:8080/api/v1/meetings');
  });

  it('shares only pending requests with the same limit and refetches after they settle', async () => {
    const pendingResponses = new Map<string, (value: Response) => void>();
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>((input) => new Promise((resolve) => {
      pendingResponses.set(String(input), resolve);
    }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));

    const firstHomeRequest = listMeetings(5);
    const duplicateHomeRequest = listMeetings(5);
    const fullListRequest = listMeetings();
    expect(fetchMock).toHaveBeenCalledTimes(2);

    pendingResponses.forEach((resolve) => resolve(new Response(JSON.stringify({ data: { items: [] } }), { status: 200 })));
    await Promise.all([firstHomeRequest, duplicateHomeRequest, fullListRequest]);
    const afterSettledHomeRequest = listMeetings(5);
    pendingResponses.get('http://localhost:8080/api/v1/meetings?limit=5')?.(new Response(JSON.stringify({ data: { items: [] } }), { status: 200 }));
    await afterSettledHomeRequest;

    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('does not share a pending response across different fetch clients', async () => {
    let resolveFirst: ((response: Response) => void) | undefined;
    let resolveSecond: ((response: Response) => void) | undefined;
    const firstFetch = vi.fn(() => new Promise<Response>((resolve) => { resolveFirst = resolve; }));
    vi.stubGlobal('fetch', withHealthyBackend(firstFetch));
    const firstRequest = listMeetings(5);

    const secondFetch = vi.fn(() => new Promise<Response>((resolve) => { resolveSecond = resolve; }));
    vi.stubGlobal('fetch', withHealthyBackend(secondFetch));
    const secondRequest = listMeetings(5);

    expect(firstFetch).toHaveBeenCalledOnce();
    expect(secondFetch).toHaveBeenCalledOnce();
    const response = () => new Response(JSON.stringify({ data: { items: [] } }), { status: 200 });
    resolveFirst?.(response());
    resolveSecond?.(response());
    await Promise.all([firstRequest, secondRequest]);
  });

  it('returns a safe error without exposing provider response details', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(async () => new Response(JSON.stringify({ error: { code: 'PROVIDER_FAILURE', message: 'secret provider payload' } }), { status: 502 }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));

    await expect(listMeetings()).rejects.toMatchObject({ name: 'MeetingListApiError', code: 'PROVIDER_FAILURE' } satisfies Partial<MeetingListApiError>);
  });
});

describe('listProcessingFailures', () => {
  it('loads failed processing sessions with server-authorized actions', async () => {
    const items = [{ sessionId: 'session-1', version: 3, title: 'Planning', meetingAt: '2026-10-14T09:00:00+09:00', stage: 'TRANSCRIPTION', errorCode: 'TRANSCRIPTION_PROCESSING_FAILED', audioExpiresAt: '2026-10-15T09:00:00Z', allowedActions: ['RETRY_PROCESSING'] }];
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(async () => new Response(JSON.stringify({ data: { items } }), { status: 200 }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));

    await expect(listProcessingFailures()).resolves.toEqual(items);
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe('http://localhost:8080/api/v1/meeting-sessions/processing-failures');
  });
});

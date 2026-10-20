import { withHealthyBackend } from './testUtils/withHealthyBackend';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { getMeetingDetail, MeetingDetailApiError } from './meetingDetailApi';

afterEach(() => vi.unstubAllGlobals());

describe('getMeetingDetail', () => {
  it('requests one encoded API-018 identifier and returns only the detail DTO', async () => {
    const data = { documentId: 'provider/id', title: 'Planning', meetingAt: '2026-10-14T09:00:00+09:00', participants: [{ id: 'p1', name: 'Ada' }], minutes: { summary: 'Summary', discussionPoints: [], decisions: [], actionItems: [], followUps: [] }, transcript: [], documentUrl: null };
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(async () => new Response(JSON.stringify({ data }), { status: 200 }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));

    await expect(getMeetingDetail('provider/id')).resolves.toEqual(data);
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe('http://localhost:8080/api/v1/meetings/provider%2Fid');
  });

  it('distinguishes not found and hides provider error details', async () => {
    vi.stubGlobal('fetch', withHealthyBackend(vi.fn(async () => new Response(JSON.stringify({ error: { code: 'MEETING_NOT_FOUND', message: 'private provider detail' } }), { status: 404 }))));
    await expect(getMeetingDetail('missing')).rejects.toMatchObject({ code: 'MEETING_NOT_FOUND' } satisfies Partial<MeetingDetailApiError>);
    vi.stubGlobal('fetch', withHealthyBackend(vi.fn(async () => new Response(JSON.stringify({ error: { code: 'SECRET', message: 'private provider detail' } }), { status: 502 }))));
    await expect(getMeetingDetail('doc')).rejects.toMatchObject({ code: 'MEETING_DETAIL_FAILED', message: expect.not.stringContaining('private') });
  });

  it('keeps an unrecognized HTTP 404 on the generic safe failure path', async () => {
    vi.stubGlobal('fetch', withHealthyBackend(vi.fn(async () => new Response(JSON.stringify({
      error: { code: 'UNEXPECTED_PROXY_404', message: 'private proxy detail' },
    }), { status: 404 }))));

    await expect(getMeetingDetail('doc')).rejects.toMatchObject({
      code: 'MEETING_DETAIL_FAILED',
      safe: { kind: 'unavailable', message: expect.not.stringContaining('private') },
    });
  });
});


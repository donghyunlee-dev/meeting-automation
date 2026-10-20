import { withHealthyBackend } from './testUtils/withHealthyBackend';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { StrictMode } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { HomePage, MeetingsPage } from './MeetingHistory';
import type { MeetingSummary } from './meetingListApi';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const meetings: MeetingSummary[] = Array.from({ length: 7 }, (_, index) => ({
  documentId: `doc-${index + 1}`,
  title: `Meeting ${index + 1}`,
  meetingAt: `2026-10-${String(14 - index).padStart(2, '0')}T09:00:00+09:00`,
  participants: [{ id: `p-${index}`, name: `Person ${index + 1}`, email: `private-${index}@example.com` }],
  documentUrl: `https://private.example/${index}`,
  status: 'COMPLETED', providerPayload: 'private provider details',
} as unknown as MeetingSummary));

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function installListFetch(items: MeetingSummary[]) {
  const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(async (input) => {
    const path = new URL(String(input)).pathname;
    if (path.endsWith('/document-setup')) return response({ data: {
      version: 1, status: 'READY', active: { provider: 'NOTION', connectionVersion: 1, rootUrl: 'https://notion.example' },
      draft: null, operation: null, canCreateMeeting: true,
    } });
    return response({ data: { items } });
  });
  vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
  return fetchMock;
}

describe('Home recent meetings', () => {
  it('requests and renders only the first three API-ordered summaries without private fields or status badges', async () => {
    const fetchMock = installListFetch(meetings);
    render(<MemoryRouter><HomePage /></MemoryRouter>);

    expect(await screen.findByText('Meeting 1')).toBeTruthy();
    expect(screen.getByText('Meeting 3')).toBeTruthy();
    expect(screen.queryByText('Meeting 4')).toBeNull();
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/api/v1/meetings?limit=3'))).toBe(true);
    expect(screen.getAllByText('참석자 1명')).toHaveLength(3);
    expect(screen.queryByText('Person 1')).toBeNull();
    expect(screen.queryByText(/private\.example|private-\d+@example\.com|COMPLETED|private provider details/)).toBeNull();
    expect(screen.getByRole('button', { name: '전체 보기' })).toBeTruthy();
    expect(screen.queryByText('Meeting Automation')).toBeNull();
  });

  it('keeps the create-meeting CTA visible when there are no recent meetings', async () => {
    installListFetch([]);
    render(<MemoryRouter><HomePage /></MemoryRouter>);

    expect(await screen.findByText(/최근 회의가 없습니다/)).toBeTruthy();
    expect(screen.getByRole('img', { name: '회의 기록 없음' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: '최근 회의가 없습니다. 새 회의를 시작해 보세요.' }).className).toContain('visually-hidden');
    expect(screen.getByRole('button', { name: /새 회의 시작/ })).toBeTruthy();
  });

  it('opens a document service status dialog with structure and connection actions', async () => {
    installListFetch([]);
    render(<MemoryRouter><Routes><Route path="/" element={<HomePage />} /><Route path="/setup/document" element={<h1>문서 연결 설정</h1>} /></Routes></MemoryRouter>);

    fireEvent.click(await screen.findByRole('button', { name: /Notion 연결됨/ }));
    expect(screen.getByRole('dialog', { name: '문서 연결 상태' })).toBeTruthy();
    expect(screen.getByText('Meetings')).toBeTruthy();
    expect(screen.getByText('Participants')).toBeTruthy();
    expect(screen.getByText('Templates')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '문서 연결 변경' }));
    expect(await screen.findByRole('heading', { name: '문서 연결 설정' })).toBeTruthy();
  });

  it('shows a safe error and retries the same five-meeting query', async () => {
    let meetingReads = 0;
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>((input) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(response({ data: { version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false } }));
      meetingReads += 1;
      return Promise.resolve(meetingReads === 1
        ? response({ error: { code: 'PROVIDER_FAILURE', message: 'sensitive provider data' } }, 502)
        : response({ data: { items: meetings.slice(0, 2) } }));
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter><HomePage /></MemoryRouter>);

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText(/sensitive provider data/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));

    expect(await screen.findByText('Meeting 2')).toBeTruthy();
    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('limit=3'))).toHaveLength(2);
  });

  it('announces the loading state while the list request is pending', async () => {
    const pending: Array<{ path: string; resolve: (value: Response) => void }> = [];
    vi.stubGlobal('fetch', withHealthyBackend(vi.fn((input: RequestInfo | URL) => new Promise<Response>((resolve) => {
      pending.push({ path: new URL(String(input)).pathname, resolve });
    }))));
    render(<MemoryRouter><HomePage /></MemoryRouter>);

    expect(screen.getByRole('heading', { name: '최근 회의' })).toBeTruthy();
    expect(screen.getByRole('status').textContent).toContain('회의 목록');
    await act(async () => { pending.forEach(({ resolve }) => resolve(response({ data: { items: [] } }))); });
  });
});

describe('Meetings page', () => {
  it('offers a retry after the initial meeting list request fails', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>()
      .mockResolvedValueOnce(response({ error: { code: 'PROVIDER_FAILURE', message: 'token=private-provider-secret' } }, 502))
      .mockResolvedValueOnce(response({ data: { items: meetings.slice(0, 3) } }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/meetings']}><Routes><Route path="/meetings" element={<MeetingsPage />} /></Routes></MemoryRouter>);

    fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('button', { name: /Meeting 1 상세 보기/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Meeting 2 상세 보기/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Meeting 3 상세 보기/ })).toBeTruthy();
    expect(document.body.textContent).not.toContain('private-provider-secret');
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('deduplicates StrictMode effect replay by query key while keeping Home and Meetings requests independent', async () => {
    const pendingResponses: Array<{ url: string; resolve: (value: Response) => void }> = [];
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>((input) => new Promise((resolve) => {
      pendingResponses.push({ url: String(input), resolve });
    }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<StrictMode><MemoryRouter><HomePage /><MeetingsPage /></MemoryRouter></StrictMode>);

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const requestedUrls = fetchMock.mock.calls.map(([input]) => String(input));
    expect(requestedUrls).toHaveLength(4);
    expect(requestedUrls.filter((url) => url.endsWith('/meetings?limit=3'))).toHaveLength(1);
    expect(requestedUrls.filter((url) => url.endsWith('/meetings'))).toHaveLength(1);
    expect(requestedUrls.filter((url) => url.endsWith('/document-setup'))).toHaveLength(2);

    await act(async () => {
      pendingResponses.forEach(({ resolve }) => resolve(response({ data: { items: [] } })));
    });
    expect(await screen.findByText(/최근 회의가 없습니다/)).toBeTruthy();
    expect(await screen.findByText(/회의 기록이 없습니다/)).toBeTruthy();
  });

  it('requests the API default of 100 and renders summaries in the API-provided order', async () => {
    const orderedMeetings = [meetings[1]!, meetings[0]!, meetings[2]!];
    const fetchMock = installListFetch(orderedMeetings);
    render(<MemoryRouter initialEntries={['/meetings']}><Routes><Route path="/meetings" element={<MeetingsPage />} /></Routes></MemoryRouter>);

    const first = await screen.findByText('Meeting 2');
    expect(first.compareDocumentPosition(screen.getByText('Meeting 1')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe('http://localhost:8080/api/v1/meetings');
    expect(screen.getByText('Person 2')).toBeTruthy();
    expect(screen.getByRole('heading', { name: '회의 기록' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '새 회의 시작' }).parentElement?.className).toContain('meetings-create-dock');
  });

  it('shows empty, loading, and retryable safe error states', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(() => Promise.resolve(response({ data: { items: [] } })))
      .mockResolvedValueOnce(response({ data: { items: [] } }))
      .mockResolvedValueOnce(response({ error: { code: 'PROVIDER_FAILURE', message: 'private detail' } }, 502))
      .mockResolvedValueOnce(response({ data: { items: meetings.slice(0, 1) } }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    const view = render(<MemoryRouter initialEntries={['/meetings']}><Routes><Route path="/meetings" element={<MeetingsPage />} /></Routes></MemoryRouter>);

    expect(await screen.findByText(/회의 기록이 없습니다/)).toBeTruthy();
    view.unmount();
    render(<MemoryRouter initialEntries={['/meetings']}><Routes><Route path="/meetings" element={<MeetingsPage />} /></Routes></MemoryRouter>);
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText(/private detail/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('Meeting 1')).toBeTruthy();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
  });
});

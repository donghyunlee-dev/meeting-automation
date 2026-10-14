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
  const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(async () => response({ data: { items } }));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('Home recent meetings', () => {
  it('requests five and renders only the first five API-ordered summaries without private fields or status badges', async () => {
    const fetchMock = installListFetch(meetings);
    render(<MemoryRouter><HomePage /></MemoryRouter>);

    expect(await screen.findByText('Meeting 1')).toBeTruthy();
    expect(screen.getByText('Meeting 5')).toBeTruthy();
    expect(screen.queryByText('Meeting 6')).toBeNull();
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/api/v1/meetings?limit=5');
    expect(screen.getAllByText('참석자 1명')).toHaveLength(5);
    expect(screen.queryByText('Person 1')).toBeNull();
    expect(screen.queryByText(/private\.example|private-\d+@example\.com|COMPLETED|private provider details/)).toBeNull();
    expect(screen.getByRole('link', { name: '전체 보기' }).getAttribute('href')).toBe('/meetings');
  });

  it('keeps the create-meeting CTA visible when there are no recent meetings', async () => {
    installListFetch([]);
    render(<MemoryRouter><HomePage /></MemoryRouter>);

    expect(await screen.findByText(/최근 회의가 없습니다/)).toBeTruthy();
    expect(screen.getByRole('link', { name: '새 회의 시작' }).getAttribute('href')).toBe('/meetings/new');
  });

  it('shows a safe error and retries the same five-meeting query', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(() => Promise.resolve(response({ data: { items: [] } })))
      .mockResolvedValueOnce(response({ error: { code: 'PROVIDER_FAILURE', message: 'sensitive provider data' } }, 502))
      .mockResolvedValueOnce(response({ data: { items: meetings.slice(0, 2) } }));
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter><HomePage /></MemoryRouter>);

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText(/sensitive provider data/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));

    expect(await screen.findByText('Meeting 2')).toBeTruthy();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(String(fetchMock.mock.calls[1]?.[0])).toContain('limit=5');
  });

  it('announces the loading state while the list request is pending', async () => {
    let resolveFetch: ((value: Response) => void) | undefined;
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => { resolveFetch = resolve; })));
    render(<MemoryRouter><HomePage /></MemoryRouter>);

    expect(screen.getByRole('heading', { name: '최근 회의' })).toBeTruthy();
    expect(screen.getByRole('status').textContent).toContain('회의 목록');
    await act(async () => { resolveFetch?.(response({ data: { items: [] } })); });
  });
});

describe('Meetings page', () => {
  it('ASYNC-STALE / ASYNC-GET-RETRY preserves loaded meetings and retries only after the user asks', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(() => Promise.resolve(response({ data: { items: meetings.slice(0, 2) } })))
      .mockResolvedValueOnce(response({ data: { items: meetings.slice(0, 2) } }))
      .mockResolvedValueOnce(response({ error: { code: 'PROVIDER_FAILURE', message: 'token=private-provider-secret' } }, 502))
      .mockResolvedValueOnce(response({ data: { items: meetings.slice(0, 3) } }));
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings']}><Routes><Route path="/meetings" element={<MeetingsPage />} /></Routes></MemoryRouter>);

    expect(await screen.findByRole('link', { name: /Meeting 1/i })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '회의 목록 새로고침' }));

    expect(await screen.findByRole('status')).toBeTruthy();
    expect(screen.getByRole('link', { name: /Meeting 1/i })).toBeTruthy();
    expect(screen.getByRole('link', { name: /Meeting 2/i })).toBeTruthy();
    expect(screen.getByText('새로고침하지 못했습니다. 기존 내용을 유지합니다.')).toBeTruthy();
    expect(document.body.textContent).not.toContain('private-provider-secret');
    expect(fetchMock).toHaveBeenCalledTimes(2);

    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('link', { name: /Meeting 3/i })).toBeTruthy();
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('deduplicates StrictMode effect replay by query key while keeping Home and Meetings requests independent', async () => {
    const pendingResponses: Array<{ url: string; resolve: (value: Response) => void }> = [];
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>((input) => new Promise((resolve) => {
      pendingResponses.push({ url: String(input), resolve });
    }));
    vi.stubGlobal('fetch', fetchMock);
    render(<StrictMode><MemoryRouter><HomePage /><MeetingsPage /></MemoryRouter></StrictMode>);

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const requestedUrls = fetchMock.mock.calls.map(([input]) => String(input));
    expect(requestedUrls).toHaveLength(2);
    expect(requestedUrls.filter((url) => url.endsWith('/meetings?limit=5'))).toHaveLength(1);
    expect(requestedUrls.filter((url) => url.endsWith('/meetings'))).toHaveLength(1);

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
    expect(screen.getByRole('heading', { name: '회의록' })).toBeTruthy();
  });

  it('shows empty, loading, and retryable safe error states', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(() => Promise.resolve(response({ data: { items: [] } })))
      .mockResolvedValueOnce(response({ data: { items: [] } }))
      .mockResolvedValueOnce(response({ error: { code: 'PROVIDER_FAILURE', message: 'private detail' } }, 502))
      .mockResolvedValueOnce(response({ data: { items: meetings.slice(0, 1) } }));
    vi.stubGlobal('fetch', fetchMock);
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

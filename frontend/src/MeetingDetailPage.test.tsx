import { withHealthyBackend } from './testUtils/withHealthyBackend';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router';
import { MeetingsPage } from './MeetingHistory';
import { MeetingDetailPage } from './MeetingDetailPage';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const list = [
  { documentId: 'doc/a', title: 'Ｆｕｌｌ width Planning', meetingAt: '2026-10-14T12:00:00', participants: [{ id: 'p1', name: 'Ada Lovelace' }], documentUrl: null },
  { documentId: 'doc-b', title: 'Retrospective', meetingAt: '2026-09-30T12:00:00', participants: [{ id: 'p2', name: 'Grace Hopper' }], documentUrl: null },
];
const detail = { documentId: 'doc/a', title: 'Planning', meetingAt: '2026-10-14T09:00:00+09:00', participants: [{ id: 'p1', name: 'Ada Lovelace' }], minutes: { summary: 'Summary text', discussionPoints: ['Discussion text'], decisions: ['Decision text'], actionItems: [{ ownerParticipantId: 'p1', task: 'Ship it', dueDate: '2026-10-20' }, { ownerParticipantId: null, task: 'Ownerless task', dueDate: null }], followUps: ['Follow up text'] }, transcript: [{ segmentId: 's1', speakerId: 'sp1', startMs: 1000, endMs: 2500, text: 'Transcript words' }], documentUrl: 'https://docs.example/meeting' };
const ok = (data: unknown) => new Response(JSON.stringify({ data }), { status: 200 });

describe('Meetings filters and detail', () => {
  it('filters locally by text/date, distinguishes no matches, resets, preserves order, and links rows accessibly', async () => {
    const fetchMock = vi.fn(async () => ok({ items: list }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/meetings']}><Routes><Route path="/meetings" element={<MeetingsPage />} /></Routes></MemoryRouter>);
    expect(await screen.findByRole('button', { name: /Planning 상세 보기/i })).toBeTruthy();
    const before = fetchMock.mock.calls.length;
    const query = screen.getByRole('searchbox', { name: '회의 검색' });
    fireEvent.change(query, { target: { value: 'ADA' } });
    fireEvent.blur(query);
    expect(screen.getByRole('button', { name: /Planning 상세 보기/i })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Retrospective 상세 보기/i })).toBeNull();
    fireEvent.change(query, { target: { value: 'absent' } });
    fireEvent.blur(query);
    expect(screen.getByText('검색 결과가 없습니다.')).toBeTruthy();
    expect(screen.getByRole('button', { name: '검색어 초기화' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '검색어 초기화' }));
    expect(screen.getByRole('button', { name: /Retrospective 상세 보기/i })).toBeTruthy();
    expect(fetchMock).toHaveBeenCalledTimes(before);
  });

  it('shows reversed date guidance while leaving the result set visible', async () => {
    vi.stubGlobal('fetch', withHealthyBackend(vi.fn(async () => ok({ items: list }))));
    render(<MemoryRouter initialEntries={['/meetings']}><Routes><Route path="/meetings" element={<MeetingsPage />} /></Routes></MemoryRouter>);
    expect(await screen.findByRole('button', { name: /Planning 상세 보기/ })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /상세 조건/ }));
    fireEvent.change(screen.getByLabelText('시작일'), { target: { value: '2026-10-20' } });
    fireEvent.change(screen.getByLabelText('종료일'), { target: { value: '2026-10-01' } });
    expect(screen.getByRole('alert').textContent).toContain('시작일은 종료일보다 늦을 수 없습니다');
    expect(screen.getByRole('button', { name: /Planning 상세 보기/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Retrospective 상세 보기/ })).toBeTruthy();
  });

  it('loads read-only Minutes, opens and closes an accessible transcript drawer, and uses a safe external link', async () => {
    vi.stubGlobal('fetch', withHealthyBackend(vi.fn(async () => ok(detail))));
    render(<MemoryRouter initialEntries={['/meetings/doc%2Fa']}><Routes><Route path="/meetings/:documentId" element={<MeetingDetailPage />} /></Routes></MemoryRouter>);
    expect(await screen.findByText('Summary text')).toBeTruthy();
    expect(screen.getByText('Discussion text')).toBeTruthy();
    expect(screen.getByText('Decision text')).toBeTruthy();
    expect(screen.getByText('Ship it')).toBeTruthy();
    expect(screen.getByText('Ownerless task')).toBeTruthy();
    expect(screen.getByText('Follow up text')).toBeTruthy();
    expect(screen.queryByText(/@/)).toBeNull();
    expect(screen.queryByRole('button', { name: /편집|삭제/ })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Notion/Confluence에서 열기' })).toBeNull();
    const transcriptTrigger = screen.getByRole('button', { name: 'Transcript 보기' });
    fireEvent.click(transcriptTrigger);
    expect(await screen.findByRole('dialog', { name: 'Transcript' })).toBeTruthy();
    expect(screen.getByText('Transcript words')).toBeTruthy();
    fireEvent.keyDown(screen.getByRole('dialog', { name: 'Transcript' }), { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Transcript' })).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(transcriptTrigger));
  });

  it('contains forward and reverse keyboard focus inside the transcript modal', async () => {
    vi.stubGlobal('fetch', withHealthyBackend(vi.fn(async () => ok(detail))));
    const backgroundButton = document.createElement('button');
    backgroundButton.textContent = 'Background action';
    document.body.append(backgroundButton);
    try {
      render(<MemoryRouter initialEntries={['/meetings/doc%2Fa']}><Routes><Route path="/meetings/:documentId" element={<MeetingDetailPage />} /></Routes></MemoryRouter>);
      expect(await screen.findByText('Summary text')).toBeTruthy();
      fireEvent.click(screen.getByRole('button', { name: 'Transcript 보기' }));
      const closeButton = await screen.findByRole('button', { name: 'Transcript 닫기' });

      backgroundButton.focus();
      expect(document.activeElement).toBe(closeButton);
      expect(fireEvent.keyDown(document, { key: 'Tab' })).toBe(false);
      expect(document.activeElement).toBe(closeButton);

      expect(fireEvent.keyDown(document, { key: 'Tab', shiftKey: true })).toBe(false);
      expect(document.activeElement).toBe(closeButton);
    } finally {
      backgroundButton.remove();
    }
  });

  it('does not render unsafe external URLs and supports retry and not-found return', async () => {
    const fetchMock = vi.fn(async () => ok({ ...detail, documentUrl: 'javascript:alert(1)' }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ error: { code: 'PROVIDER_FAILURE', message: 'secret provider text' } }), { status: 502 }))
      .mockResolvedValueOnce(ok({ ...detail, documentUrl: 'data:text/html,unsafe' }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/meetings/doc-b']}><Routes><Route path="/meetings/:documentId" element={<MeetingDetailPage />} /><Route path="/meetings" element={<h1>회의록</h1>} /></Routes></MemoryRouter>);
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText(/secret provider text/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('Summary text')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Notion/Confluence에서 열기' })).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('explains missing records and returns to the Meetings list', async () => {
    vi.stubGlobal('fetch', withHealthyBackend(vi.fn(async () => new Response(JSON.stringify({ error: { code: 'MEETING_NOT_FOUND' } }), { status: 404 }))));
    render(<MemoryRouter initialEntries={['/meetings/missing']}><Routes><Route path="/meetings/:documentId" element={<MeetingDetailPage />} /><Route path="/meetings" element={<h1>회의록</h1>} /></Routes></MemoryRouter>);
    expect(await screen.findByText('회의 기록을 찾을 수 없습니다.')).toBeTruthy();
    fireEvent.click(screen.getByRole('link', { name: '회의록 목록으로' }));
    expect(await screen.findByRole('heading', { name: '회의록' })).toBeTruthy();
  });
});







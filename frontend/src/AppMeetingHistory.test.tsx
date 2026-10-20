import { withHealthyBackend } from './testUtils/withHealthyBackend';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router';
import { App } from './App';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

describe('meeting history routes', () => {
  it('opens the Meetings route from bottom navigation and retains the Home create-meeting CTA', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input));
      if (url.pathname.endsWith('/meetings')) return new Response(JSON.stringify({ data: { items: [{
        documentId: 'doc-1', title: 'Completed planning', meetingAt: '2026-10-14T09:00:00+09:00',
        participants: [{ id: 'p1', name: 'Ada' }], documentUrl: null,
      }] } }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      if (url.pathname.endsWith('/document-setup')) return new Response(JSON.stringify({ data: {
        version: 1, status: 'READY', active: { provider: 'NOTION', connectionVersion: 1, rootUrl: 'https://notion.example' },
        draft: null, operation: null, canCreateMeeting: true,
      } }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      throw new Error(`Unexpected request: ${url.pathname}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/meetings']}><App /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: '회의 기록' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '새 회의 시작' }).parentElement?.className).toContain('meetings-create-dock');
    expect(await screen.findByText('Completed planning')).toBeTruthy();
    fireEvent.click(screen.getByRole('link', { name: '홈' }));

    expect(await screen.findByRole('button', { name: /새 회의 시작/ })).toBeTruthy();
    expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith('/meetings?limit=3'))).toBe(true);
    expect(screen.getByRole('link', { name: '회의록' }).getAttribute('href')).toBe('/meetings');
  });
});

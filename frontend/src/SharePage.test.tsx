import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router';
import { withHealthyBackend } from './testUtils/withHealthyBackend';
import { SharePage } from './SharePage';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('SharePage', () => {
  it('shows a precise error toast when a mapped speaker is outside the meeting roster', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const pathname = new URL(String(input), 'http://localhost').pathname;
      if (pathname.endsWith('/app-config')) return response({ data: {
        company: { timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true },
        notification: { enabled: false },
      } });
      if (pathname.endsWith('/participants')) return response({ data: { items: [] } });
      if (pathname.endsWith('/meeting-sessions/session-1/confirm')) return response({ error: {
        code: 'REVIEW_VALIDATION_FAILED', details: { issues: [
          { path: 'speakers[0].participantId', code: 'PARTICIPANT_OUTSIDE_SESSION_ROSTER' },
        ] },
      } }, 422);
      if (pathname.endsWith('/meeting-sessions/session-1')) return response({ data: {
        sessionId: 'session-1', version: 3, status: 'REVIEW', allowedActions: ['CONFIRM'],
        meeting: { participantIds: ['p1'] },
        speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p1' }],
        transcript: [], minutes: { templateId: 'weekly', templateVersion: '1', summary: '',
          discussionPoints: [], decisions: [], actionItems: [], followUps: [] },
      } });
      throw new Error(`Unexpected request: ${pathname}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));

    render(<MemoryRouter initialEntries={['/meetings/session-1/share']}><Routes>
      <Route path="/meetings/:sessionId/share" element={<SharePage />} />
      <Route path="/meetings/:sessionId/review" element={<p>Review</p>} />
    </Routes></MemoryRouter>);

    fireEvent.click(await screen.findByRole('button', { name: '저장하고 공유' }));

    const toast = await screen.findByText((_, element) => element?.classList.contains('app-toast-error') ?? false);
    expect(toast.textContent).toContain(
      'Speaker A에 지정한 참석자가 이 회의 참석자 목록에 없습니다. 참석자 매핑을 수정한 뒤 다시 저장해 주세요.',
    );
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/meeting-sessions/session-1/confirm'), expect.anything(),
    ));
  });
});

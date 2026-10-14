import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { SharePage } from './SharePage';

function response(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ data }), { status, headers: { 'Content-Type': 'application/json' } });
}

const reviewState = {
  sessionId: 'session-1', version: 5, status: 'REVIEW', meeting: { participantIds: ['p1', 'p2'] },
  speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p1' }], transcript: [],
  minutes: { templateId: 'weekly', templateVersion: '3', summary: 'Review summary', discussionPoints: [], decisions: [], actionItems: [], followUps: [] },
  allowedActions: ['CONFIRM'],
};

function LocationState() {
  const location = useLocation();
  return <output data-testid="location-state">{JSON.stringify(location.state)}</output>;
}

function renderShare(fetchMock: ReturnType<typeof vi.fn>, state: Record<string, unknown> = { emailRecipientParticipantIds: ['p2'], notificationEnabled: false }) {
  vi.stubGlobal('fetch', fetchMock);
  return render(<MemoryRouter initialEntries={[{ pathname: '/meetings/session-1/share', state: { review: reviewState, ...state } }]}>
    <Routes>
      <Route path="/meetings/:sessionId/share" element={<><SharePage /><LocationState /></>} />
      <Route path="/settings" element={<><h1>Settings</h1><LocationState /></>} />
      <Route path="/meetings/:sessionId/review" element={<><h1>Review</h1><LocationState /></>} />
    </Routes>
  </MemoryRouter>);
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('Share page', () => {
  it('limits email recipients to API-010 roster IDs and excludes extra API-003 records', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: 'NOTION', configured: true, connectionVersion: 2 } });
      if (path.endsWith('/participants')) return response({ items: [
        { id: 'p1', name: 'Ada', email: 'ada@example.test' },
        { id: 'p2', name: 'Lin', email: 'lin@example.test' },
        { id: 'p3', name: 'Outside roster', email: 'outside@example.test' },
      ] });
      if (path.endsWith('/meeting-sessions/session-1')) return response({ ...reviewState });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderShare(fetchMock, {});

    expect(await screen.findByRole('checkbox', { name: /Ada/ })).toBeTruthy();
    expect(screen.getByRole('checkbox', { name: /Lin/ })).toBeTruthy();
    expect(screen.queryByText('Outside roster')).toBeNull();
    expect((screen.getByRole('checkbox', { name: /Ada/ }) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByRole('checkbox', { name: /Lin/ }) as HTMLInputElement).checked).toBe(true);
  });

  it('keeps API-010 roster IDs selectable with ID labels when participant metadata fails', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: 'NOTION', configured: true, connectionVersion: 2 } });
      if (path.endsWith('/participants')) return response({ error: { code: 'PARTICIPANT_LIST_FAILED' } }, 503);
      if (path.endsWith('/meeting-sessions/session-1')) return response({ ...reviewState });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderShare(fetchMock, { emailRecipientParticipantIds: ['p2'] });

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByText(/참석자 목록을 불러오지 못했습니다/)).toBeTruthy();
    expect((screen.getByRole('checkbox', { name: 'p1' }) as HTMLInputElement).checked).toBe(false);
    expect((screen.getByRole('checkbox', { name: 'p2' }) as HTMLInputElement).checked).toBe(true);
  });

  it('sends only selected API-010 roster IDs and notification preference from Share', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: 'NOTION', configured: true, connectionVersion: 2 } });
      if (path.endsWith('/participants')) return response({ items: [
        { id: 'p1', name: 'Ada', email: 'ada@example.test' },
        { id: 'p2', name: 'Lin', email: 'lin@example.test' },
        { id: 'p3', name: 'Outside roster', email: 'outside@example.test' },
      ] });
      if (path.endsWith('/confirm')) return response({ status: 'CONFIRMED', version: 6 });
      if (path.endsWith('/publish')) return response({ sessionId: 'session-1', status: 'PUBLISHING' }, 202);
      if (path.endsWith('/meeting-sessions/session-1')) return response({ ...reviewState });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderShare(fetchMock, { emailRecipientParticipantIds: ['p1', 'p3'], notificationEnabled: false });

    fireEvent.click(await screen.findByRole('button', { name: '저장하고 공유' }));
    await screen.findByText('문서 저장 중입니다. 저장 결과를 확인하고 있습니다.');

    const [, init] = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/publish')) as unknown as [RequestInfo | URL, RequestInit];
    expect(JSON.parse(String(init.body))).toEqual({ emailRecipientParticipantIds: ['p1'], notificationEnabled: false });
    expect(String(init.body)).not.toContain('ada@example.test');
    expect(String(init.body)).not.toContain('outside@example.test');
  });

  it('preserves an explicit empty email selection through settings navigation', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: null, configured: false } });
      if (path.endsWith('/participants')) return response({ items: [{ id: 'p1', name: 'Ada', email: 'ada@example.test' }] });
      if (path.endsWith('/meeting-sessions/session-1')) return response({ ...reviewState });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderShare(fetchMock, { emailRecipientParticipantIds: [] });

    expect((await screen.findByRole('checkbox', { name: /Ada/ }) as HTMLInputElement).checked).toBe(false);
    fireEvent.click(screen.getByRole('link', { name: '문서 설정 열기' }));

    const state = JSON.parse(screen.getByTestId('location-state').textContent ?? '{}');
    expect(state.returnState.emailRecipientParticipantIds).toEqual([]);
  });

  it('shows Slack setup guidance without changing the selected notification preference', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' },
        document: { provider: 'NOTION', configured: true, connectionVersion: 2 },
        notification: { provider: null, configured: false } });
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/meeting-sessions/session-1')) return response({ ...reviewState });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderShare(fetchMock, { emailRecipientParticipantIds: ['p2'], notificationEnabled: true });

    expect(await screen.findByText(/Slack 알림.*설정되지 않았습니다/)).toBeTruthy();
    expect((screen.getByRole('checkbox', { name: 'Slack 알림 사용' }) as HTMLInputElement).checked).toBe(true);
    fireEvent.click(screen.getByRole('link', { name: 'Slack 설정 열기' }));
    const settings = JSON.parse(screen.getByTestId('location-state').textContent ?? '{}');
    expect(settings.returnTo).toBe('/meetings/session-1/share');
    expect(settings.returnState.notificationEnabled).toBe(true);
    expect(settings.returnState.emailRecipientParticipantIds).toEqual(['p2']);
  });

  it('shows Notification deliveries independently and resumes API-010 polling without changing Email rows', async () => {
    let statusReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' },
        document: { provider: 'NOTION', configured: true, connectionVersion: 2 },
        notification: { provider: 'SLACK', configured: true } });
      if (path.endsWith('/participants')) return response({ items: [{ id: 'p2', name: 'Lin', email: 'lin@example.test' }] });
      if (path.endsWith('/meeting-sessions/session-1')) {
        statusReads += 1;
        return response({ sessionId: 'session-1', version: statusReads === 1 ? 9 : 10, status: 'DOCUMENT_SAVED', allowedActions: [],
          meeting: { participantIds: ['p2'] }, document: { documentId: 'doc-1', documentUrl: 'https://docs.example/doc-1' },
          deliveries: [
            { deliveryId: 'email-1', channel: 'EMAIL', recipientParticipantId: 'p2', status: 'SENT', retryable: false },
            { deliveryId: 'notification-1', channel: 'NOTIFICATION', status: statusReads === 1 ? 'PENDING' : 'FAILED', retryable: true, errorCode: 'NOTIFICATION_FAILED' },
          ] });
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    renderShare(fetchMock, { emailRecipientParticipantIds: ['p2'], notificationEnabled: true });

    expect(await screen.findByText(/Slack.*전송 중.*PENDING/)).toBeTruthy();
    expect(await screen.findByText(/Slack.*전송 실패.*FAILED/, {}, { timeout: 4_000 })).toBeTruthy();
    expect(screen.getByText(/다시 시도할 수 있습니다/)).toBeTruthy();
    expect(screen.getByText(/Lin:.*SENT/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Slack.*다시 보내기/ })).toBeNull();
    expect(statusReads).toBe(2);
  });

  it('shows independent email delivery states without claiming inbox delivery or offering resend', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: 'NOTION', configured: true, connectionVersion: 2 } });
      if (path.endsWith('/participants')) return response({ items: [
        { id: 'p1', name: 'Ada', email: 'ada@example.test' }, { id: 'p2', name: 'Lin', email: 'lin@example.test' },
        { id: 'p3', name: 'Jo', email: 'jo@example.test' }, { id: 'p4', name: 'Noor', email: 'noor@example.test' },
      ] });
      if (path.endsWith('/meeting-sessions/session-1')) return response({
        ...reviewState, meeting: { participantIds: ['p1', 'p2', 'p3', 'p4'] }, status: 'DELIVERING', version: 8, allowedActions: [],
        deliveries: [
          { deliveryId: 'd0', channel: 'EMAIL', recipientParticipantId: 'p3', status: 'PENDING', retryable: false },
          { deliveryId: 'd1', channel: 'EMAIL', recipientParticipantId: 'p1', status: 'SENT', retryable: false },
          { deliveryId: 'd2', channel: 'EMAIL', recipientParticipantId: 'p2', status: 'FAILED', retryable: true, errorCode: 'EMAIL_FAILED' },
          { deliveryId: 'd3', channel: 'EMAIL', recipientParticipantId: 'p4', status: 'SENDING', retryable: false },
        ],
      });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderShare(fetchMock, {});

    expect(await screen.findByText(/전송 요청 수락 \(SENT\)/)).toBeTruthy();
    expect(screen.getByText(/전송 실패 \(FAILED\)/)).toBeTruthy();
    expect(screen.getByText(/대기 중 \(PENDING\)/)).toBeTruthy();
    expect(screen.getByText(/전송 중 \(SENDING\)/)).toBeTruthy();
    expect(screen.getByText('나중에 재시도할 수 있습니다.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /재전송|다시 보내기/ })).toBeNull();
    expect(screen.queryByText(/받은편지함|수신함/)).toBeNull();
  });

  it('resumes polling when Share opens with a saved document and pending Email delivery', async () => {
    let statusReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: 'NOTION', configured: true, connectionVersion: 2 } });
      if (path.endsWith('/participants')) return response({ items: [{ id: 'p2', name: 'Lin', email: 'lin@example.test' }] });
      if (path.endsWith('/meeting-sessions/session-1')) {
        statusReads += 1;
        return response({ sessionId: 'session-1', version: statusReads === 1 ? 9 : 10, status: 'DOCUMENT_SAVED', allowedActions: [],
          meeting: { participantIds: ['p2'] }, document: { documentId: 'doc-1', documentUrl: 'https://docs.example/doc-1' },
          deliveries: [{ deliveryId: 'd1', channel: 'EMAIL', recipientParticipantId: 'p2',
            status: statusReads === 1 ? 'PENDING' : 'SENT', retryable: false }] });
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    renderShare(fetchMock, {});

    expect(await screen.findByText(/대기 중 \(PENDING\)/)).toBeTruthy();
    expect(await screen.findByText(/전송 요청 수락 \(SENT\)/, {}, { timeout: 4_000 })).toBeTruthy();
    expect(statusReads).toBe(2);
  });

  it('blocks confirmation when the Document provider is unconfigured and preserves user choices for Settings recovery', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: null, configured: false } });
      if (path.endsWith('/participants')) return response({ items: [{ id: 'p1', name: 'Ada', email: 'ada@example.test' }, { id: 'p2', name: 'Lin', email: 'lin@example.test' }] });
      if (path.endsWith('/meeting-sessions/session-1')) return response({ ...reviewState });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderShare(fetchMock);

    expect((await screen.findByRole('link', { name: '문서 설정 열기' })).getAttribute('href')).toBe('/settings');
    expect((screen.getByRole('button', { name: '저장하고 공유' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('checkbox', { name: /Lin/ }) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByRole('checkbox', { name: /Ada/ }) as HTMLInputElement).checked).toBe(false);
    expect((screen.getByRole('checkbox', { name: 'Slack 알림 사용' }) as HTMLInputElement).checked).toBe(false);
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/confirm') || String(url).endsWith('/publish'))).toBe(false);

    fireEvent.click(screen.getByRole('link', { name: '문서 설정 열기' }));
    const saved = JSON.parse(screen.getByTestId('location-state').textContent ?? '{}');
    expect(saved.returnTo).toBe('/meetings/session-1/share');
    expect(saved.returnState.emailRecipientParticipantIds).toEqual(['p2']);
    expect(saved.returnState.notificationEnabled).toBe(false);
  });

  it('blocks direct Share entry when carried mapping or Minutes edits differ from the server snapshot', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: 'NOTION', configured: true, connectionVersion: 2, rootUrl: 'https://notion.example' } });
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/meeting-sessions/session-1')) return response({ ...reviewState });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderShare(fetchMock, {
      speakerDrafts: { 'speaker-a': 'p2' },
      minutesDraft: { ...reviewState.minutes, summary: 'Unsaved summary' },
    });

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByText('화자 매핑 또는 회의록을 저장한 뒤 공유하세요.')).toBeTruthy();
    expect((screen.getByRole('button', { name: '저장하고 공유' }) as HTMLButtonElement).disabled).toBe(true);
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/confirm') || String(url).endsWith('/publish'))).toBe(false);

    fireEvent.click(screen.getByRole('link', { name: 'Review에서 변경사항 저장하기' }));
    const saved = JSON.parse(screen.getByTestId('location-state').textContent ?? '{}');
    expect(saved.speakerDrafts).toEqual({ 'speaker-a': 'p2' });
    expect(saved.minutesDraft.summary).toBe('Unsaved summary');
  });

  it('keeps the same confirmation idempotency key when API-010 confirms a lost API-014 response', async () => {
    let statusReads = 0;
    let confirmCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: 'NOTION', configured: true, connectionVersion: 2 } });
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/confirm')) {
        confirmCalls += 1;
        if (confirmCalls === 1) throw new TypeError('connection lost');
        return response({ status: 'CONFIRMED', version: 6 });
      }
      if (path.endsWith('/publish')) return response({ sessionId: 'session-1', status: 'PUBLISHING' }, 202);
      if (path.endsWith('/meeting-sessions/session-1')) {
        statusReads += 1;
        return response(statusReads < 3 ? reviewState : { sessionId: 'session-1', version: 7, status: 'PUBLISHING', allowedActions: [] });
      }
      throw new Error(`Unexpected request: ${path} ${init?.method ?? 'GET'}`);
    });
    renderShare(fetchMock);

    fireEvent.click(await screen.findByRole('button', { name: '저장하고 공유' }));
    await screen.findByText('문서 저장 중입니다. 저장 결과를 확인하고 있습니다.');

    const confirmationRequests = fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/confirm')) as unknown as [RequestInfo | URL, RequestInit][];
    expect(confirmCalls).toBe(2);
    expect(new Headers(confirmationRequests[0][1].headers).get('Idempotency-Key')).toBe(new Headers(confirmationRequests[1][1].headers).get('Idempotency-Key'));
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/publish'))).toHaveLength(1);
  });

  it('ignores stale snapshots, polls pending Email results, and stops after the latest result', async () => {
    let statusReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: 'NOTION', configured: true, connectionVersion: 2 } });
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/confirm')) return response({ status: 'CONFIRMED', version: 6 });
      if (path.endsWith('/publish')) return response({ sessionId: 'session-1', status: 'PUBLISHING' }, 202);
      if (path.endsWith('/meeting-sessions/session-1')) {
        statusReads += 1;
        if (statusReads === 1) return response({ ...reviewState });
        if (statusReads === 2) return response({ sessionId: 'session-1', version: 8, status: 'PUBLISHING', allowedActions: [] });
        if (statusReads === 3) return response({ sessionId: 'session-1', version: 7, status: 'PUBLISHING', allowedActions: [] });
        if (statusReads === 4) return response({ sessionId: 'session-1', version: 9, status: 'DOCUMENT_SAVED', allowedActions: [],
          document: { documentId: 'doc-1', documentUrl: 'https://docs.example/latest' }, meeting: { participantIds: ['p2'] },
          deliveries: [{ deliveryId: 'd1', channel: 'EMAIL', recipientParticipantId: 'p2', status: 'PENDING', retryable: false }] });
        return response({ sessionId: 'session-1', version: 10, status: 'DOCUMENT_SAVED', allowedActions: [],
          document: { documentId: 'doc-1', documentUrl: 'https://docs.example/latest' }, meeting: { participantIds: ['p2'] },
          deliveries: [{ deliveryId: 'd1', channel: 'EMAIL', recipientParticipantId: 'p2', status: 'SENT', retryable: false }] });
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    renderShare(fetchMock);

    fireEvent.click(await screen.findByRole('button', { name: '저장하고 공유' }));

    const documentLink = await screen.findByRole('link', { name: /저장된 회의록 열기/ }, { timeout: 4_000 });
    expect(documentLink.getAttribute('href')).toBe('https://docs.example/latest');
    expect(await screen.findByText(/전송 요청 수락 \(SENT\)/, {}, { timeout: 4_000 })).toBeTruthy();
    await new Promise((resolve) => setTimeout(resolve, 4_500));
    expect(statusReads).toBe(5);
  }, 12_000);

  it('does not expose a document link for an unsafe document URL', async () => {
    let statusReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: 'NOTION', configured: true, connectionVersion: 2 } });
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/confirm')) return response({ status: 'CONFIRMED', version: 6 });
      if (path.endsWith('/publish')) return response({ sessionId: 'session-1', status: 'PUBLISHING' }, 202);
      if (path.endsWith('/meeting-sessions/session-1')) {
        statusReads += 1;
        if (statusReads === 1) return response({ ...reviewState });
        return response({ sessionId: 'session-1', version: 7, status: 'DOCUMENT_SAVED', allowedActions: [], document: { documentId: 'doc-1', documentUrl: 'javascript:alert(1)' } });
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    renderShare(fetchMock);

    fireEvent.click(await screen.findByRole('button', { name: '저장하고 공유' }));

    expect(await screen.findByText(/문서 링크를 확인할 수 없어/)).toBeTruthy();
    expect(screen.queryByRole('link', { name: /저장된 회의록 열기/ })).toBeNull();
  });

  it('shows retry only when API-010 explicitly allows PUBLISH after DOCUMENT_FAILED', async () => {
    let statusReads = 0;
    let publishCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: 'NOTION', configured: true, connectionVersion: 2 } });
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/confirm')) return response({ status: 'CONFIRMED', version: 6 });
      if (path.endsWith('/publish')) { publishCalls += 1; return response({ sessionId: 'session-1', status: 'PUBLISHING' }, 202); }
      if (path.endsWith('/meeting-sessions/session-1')) {
        statusReads += 1;
        if (statusReads === 1) return response({ ...reviewState });
        return response({ sessionId: 'session-1', version: 7, status: 'DOCUMENT_FAILED', allowedActions: [] });
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    renderShare(fetchMock);

    fireEvent.click(await screen.findByRole('button', { name: '저장하고 공유' }));

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '문서 저장 재시도' })).toBeNull();
    expect(publishCalls).toBe(1);
  });

  it('sends only one confirmation when the user double-clicks the Share action', async () => {
    let releaseConfirmation!: (result: Response) => void;
    const confirmation = new Promise<Response>((resolve) => { releaseConfirmation = resolve; });
    let confirmCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: 'NOTION', configured: true, connectionVersion: 2 } });
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/confirm')) { confirmCalls += 1; return confirmation; }
      if (path.endsWith('/publish')) return response({ sessionId: 'session-1', status: 'PUBLISHING' }, 202);
      if (path.endsWith('/meeting-sessions/session-1')) return response({ ...reviewState });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderShare(fetchMock);

    const shareButton = await screen.findByRole('button', { name: '저장하고 공유' });
    fireEvent.click(shareButton);
    fireEvent.click(shareButton);
    expect(confirmCalls).toBe(1);
    releaseConfirmation(response({ status: 'CONFIRMED', version: 6 }));
    await screen.findByText(/문서 저장 중/);

    expect(confirmCalls).toBe(1);
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/publish'))).toHaveLength(1);
  });

  it('confirms before publishing, preserves selections, polls API-010, and exposes only a safe document URL', async () => {
    let statusReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: 'NOTION', configured: true, connectionVersion: 2, rootUrl: 'https://notion.example' } });
      if (path.endsWith('/participants')) return response({ items: [{ id: 'p1', name: 'Ada', email: 'ada@example.test' }, { id: 'p2', name: 'Lin', email: 'lin@example.test' }] });
      if (path.endsWith('/meeting-sessions/session-1/confirm')) return response({ status: 'CONFIRMED', version: 6 });
      if (path.endsWith('/meeting-sessions/session-1/publish')) return response({ sessionId: 'session-1', status: 'PUBLISHING' }, 202);
      if (path.endsWith('/meeting-sessions/session-1')) {
        statusReads += 1;
        if (statusReads === 1) return response({ ...reviewState });
        if (statusReads === 2) return response({ sessionId: 'session-1', version: 7, status: 'PUBLISHING', allowedActions: [] });
        return response({ sessionId: 'session-1', version: 8, status: 'DELIVERING', allowedActions: [], document: { documentId: 'doc-1', documentUrl: 'https://docs.example/doc-1' } });
      }
      throw new Error(`Unexpected request: ${path} ${init?.method ?? 'GET'}`);
    });
    renderShare(fetchMock);

    const submit = await screen.findByRole('button', { name: '저장하고 공유' });
    expect((screen.getByRole('checkbox', { name: /Lin/ }) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByRole('checkbox', { name: 'Slack 알림 사용' }) as HTMLInputElement).checked).toBe(false);
    fireEvent.click(submit);

    const publishLink = await screen.findByRole('link', { name: /저장된 회의록 열기/ }, { timeout: 4_000 });
    expect(publishLink.getAttribute('href')).toBe('https://docs.example/doc-1');
    expect(publishLink.getAttribute('target')).toBe('_blank');
    expect(publishLink.getAttribute('rel')).toContain('noopener');
    expect(screen.getByText(/문서 저장 완료/)).toBeTruthy();
    expect(screen.queryByText(/Email 전송 완료|Slack 알림 완료/)).toBeNull();

    const confirmIndex = fetchMock.mock.calls.findIndex(([url]) => String(url).endsWith('/confirm'));
    const publishIndex = fetchMock.mock.calls.findIndex(([url]) => String(url).endsWith('/publish'));
    expect(confirmIndex).toBeGreaterThanOrEqual(0);
    expect(publishIndex).toBeGreaterThan(confirmIndex);
    const publishCall = fetchMock.mock.calls[publishIndex] as [string, RequestInit];
    expect(new Headers(publishCall[1].headers).get('If-Match')).toBe('"6"');
    expect(JSON.parse(String(publishCall[1].body))).toEqual({ emailRecipientParticipantIds: ['p2'], notificationEnabled: false });
  });

  it('shows API-014 validation issues and does not call API-015', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: 'NOTION', configured: true, connectionVersion: 2, rootUrl: 'https://notion.example' } });
      if (path.endsWith('/participants')) return response({ items: [{ id: 'p1', name: 'Ada', email: 'ada@example.test' }] });
      if (path.endsWith('/meeting-sessions/session-1')) return response({ ...reviewState });
      if (path.endsWith('/confirm')) return new Response(JSON.stringify({ error: { code: 'REVIEW_VALIDATION_FAILED', details: { issues: [
        { path: 'speakers[0].participantId', code: 'SPEAKER_UNMAPPED' },
        { path: 'minutes.actionItems[0].task', code: 'REQUIRED' },
      ] } } }), { status: 422 });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderShare(fetchMock);

    fireEvent.click(await screen.findByRole('button', { name: '저장하고 공유' }));

    const issues = await screen.findByRole('alert');
    expect(within(issues).getByText(/Speaker A 매핑/)).toBeTruthy();
    expect(within(issues).getByText(/회의록/)).toBeTruthy();
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/publish'))).toBe(false);
  });

  it('reconciles an uncertain API-015 result and waits for an explicit retry using the current version', async () => {
    let publishCalls = 0;
    let statusReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: 'NOTION', configured: true, connectionVersion: 2, rootUrl: 'https://notion.example' } });
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/confirm')) return response({ status: 'CONFIRMED', version: 6 });
      if (path.endsWith('/publish')) {
        publishCalls += 1;
        if (publishCalls === 1) throw new TypeError('connection lost');
        return response({ sessionId: 'session-1', status: 'PUBLISHING' }, 202);
      }
      if (path.endsWith('/meeting-sessions/session-1')) {
        statusReads += 1;
        if (statusReads === 1) return response({ ...reviewState });
        return response({ sessionId: 'session-1', version: 7, status: 'CONFIRMED', allowedActions: ['PUBLISH'] });
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    renderShare(fetchMock);

    fireEvent.click(await screen.findByRole('button', { name: '저장하고 공유' }));
    const retry = await screen.findByRole('button', { name: '문서 저장을 시도' }, { timeout: 4_000 });
    expect(publishCalls).toBe(1);
    const firstPublish = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/publish')) as unknown as [RequestInfo | URL, RequestInit];
    fireEvent.click(retry);
    await screen.findByText('문서 저장 중입니다. 저장 결과를 확인하고 있습니다.');

    expect(publishCalls).toBe(2);
    const publishRequests = fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/publish')) as unknown as [RequestInfo | URL, RequestInit][];
    expect(new Headers(publishRequests[1][1].headers).get('If-Match')).toBe('"7"');
    expect(new Headers(publishRequests[1][1].headers).get('Idempotency-Key')).not.toBe(new Headers(firstPublish[1].headers).get('Idempotency-Key'));
  });
});

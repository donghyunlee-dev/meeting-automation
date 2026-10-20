import { withHealthyBackend } from './testUtils/withHealthyBackend';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router';
import { CompletePage } from './CompletePage';

function response(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ data }), { status, headers: { 'Content-Type': 'application/json' } });
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function renderComplete(fetchMock: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
  return render(<MemoryRouter initialEntries={['/meetings/s1/complete']}>
    <Routes><Route path="/meetings/:sessionId/complete" element={<CompletePage />} /></Routes>
  </MemoryRouter>);
}

function SwitchToOtherSession() {
  const navigate = useNavigate();
  return <button type="button" onClick={() => navigate('/meetings/session-B/complete')}>다른 회의 열기</button>;
}

describe('CompletePage', () => {
  it('explains a document save failure from the API and only offers actions the API allows', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      if (init?.method === 'POST') throw new Error(`Unexpected write request: ${path}`);
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/meeting-sessions/s1')) return response({ sessionId: 's1', version: 9, status: 'DOCUMENT_FAILED',
        allowedActions: ['PUBLISH'], documentFailure: { errorCode: 'DOCUMENT_STRUCTURE_NOT_FOUND', retryable: false } });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderComplete(fetchMock);

    expect(await screen.findByText('필수 문서 구조를 찾을 수 없습니다.')).toBeTruthy();
    expect(screen.getByText('오류 코드: DOCUMENT_STRUCTURE_NOT_FOUND')).toBeTruthy();
    expect(screen.getByText(/기본 구조를 복구한 뒤 저장을 다시 요청하세요/)).toBeTruthy();
    expect(screen.getByText('회의록 저장')).toBeTruthy();
    expect(screen.getByText('저장 실패')).toBeTruthy();
    expect(screen.getByRole('button', { name: '문서 저장 재시도' })).toBeTruthy();
    const refreshButton = screen.getByRole('button', { name: '상태 새로고침' });
    expect(refreshButton).toBeTruthy();
    fireEvent.click(refreshButton);
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/meeting-sessions/s1'))).toHaveLength(2));
    expect(screen.queryByText(/현재 상태만 다시 조회했습니다/)).toBeNull();
    expect(fetchMock.mock.calls.some(([url, init]) => String(url).includes('/publish') && init?.method === 'POST')).toBe(false);
  });

  it('keeps polling while document saving is in progress and shows the resulting failure', async () => {
    let statusReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/meeting-sessions/s1')) {
        statusReads += 1;
        return statusReads === 1
          ? response({ sessionId: 's1', version: 3, status: 'PUBLISHING', allowedActions: [] })
          : response({ sessionId: 's1', version: 4, status: 'DOCUMENT_FAILED', allowedActions: ['PUBLISH'],
            documentFailure: { errorCode: 'DOCUMENT_STRUCTURE_NOT_FOUND', retryable: false } });
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    renderComplete(fetchMock);

    await waitFor(() => expect(screen.getByText('필수 문서 구조를 찾을 수 없습니다.')).toBeTruthy(), { timeout: 4_000 });
    expect(statusReads).toBeGreaterThanOrEqual(2);
  });

  it('clears the publish acceptance message when refreshed API state takes over', async () => {
    let statusReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Company', timezone: 'UTC' }, notification: { provider: 'SLACK', enabled: true } });
      if (path.endsWith('/publish') && init?.method === 'POST') return response({ sessionId: 's1', status: 'PUBLISHING' }, 202);
      if (path.endsWith('/meeting-sessions/s1')) {
        statusReads += 1;
        if (statusReads === 1) return response({ sessionId: 's1', version: 4, status: 'DOCUMENT_FAILED', meeting: { participantIds: ['p1'] },
          allowedActions: ['PUBLISH'], documentFailure: { errorCode: 'DOCUMENT_FAILED', retryable: true } });
        if (statusReads === 2) return response({ sessionId: 's1', version: 6, status: 'PUBLISHING', allowedActions: [] });
        return response({ sessionId: 's1', version: 7, status: 'DOCUMENT_SAVED', allowedActions: [],
          document: { documentId: 'doc1' }, deliveries: [{ deliveryId: 'slack-1', channel: 'NOTIFICATION', status: 'SENT', retryable: false }] });
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    renderComplete(fetchMock);

    fireEvent.click(await screen.findByRole('button', { name: '문서 저장 재시도' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: '완료' })).toBeTruthy(), { timeout: 4_000 });

    const publishCall = fetchMock.mock.calls.find(([input, init]) => new URL(String(input), 'http://localhost').pathname.endsWith('/publish') && init?.method === 'POST');
    expect(JSON.parse(String(publishCall?.[1]?.body))).toEqual({ emailRecipientParticipantIds: [], notificationEnabled: true });
    expect(await screen.findByRole('region', { name: 'Slack 알림 결과' })).toBeTruthy();
    expect(screen.queryByText('다시 전달을 시작했습니다. 최신 상태를 확인하고 있습니다.')).toBeNull();
  });

  it('ASYNC-GET-RETRY uses the shared safe error state and retries the API-010 GET explicitly', async () => {
    let snapshotReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/meeting-sessions/s1')) {
        snapshotReads += 1;
        return snapshotReads === 1
          ? new Response(JSON.stringify({ error: { code: 'PROVIDER_FAILURE', message: 'private provider response' } }), { status: 503 })
          : response({ sessionId: 's1', version: 1, status: 'DOCUMENT_SAVED', allowedActions: [], document: { documentId: 'doc1' }, deliveries: [] });
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    renderComplete(fetchMock);

    expect(await screen.findByRole('heading', { name: '회의 결과를 불러오지 못했습니다.' })).toBeTruthy();
    expect(document.body.textContent).not.toContain('private provider response');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('heading', { name: '문서 저장 완료' })).toBeTruthy();
    expect(snapshotReads).toBe(2);
  });
  it.each([
    ['all deliveries accepted', [{ deliveryId: 'e1', channel: 'EMAIL', status: 'SENT', retryable: false }], '완료'],
    ['all deliveries failed', [{ deliveryId: 'e1', channel: 'EMAIL', status: 'FAILED', retryable: false }], '일부 전달 실패'],
    ['no delivery rows', [], '문서 저장 완료'],
  ])('derives %s summary only from the API-010 snapshot', async (_caseName, deliveries, expectedSummary) => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/meeting-sessions/s1')) return response({ sessionId: 's1', version: 1, status: 'DOCUMENT_SAVED', allowedActions: [],
        document: { documentId: 'doc1' }, deliveries });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderComplete(fetchMock);
    expect(await screen.findByRole('heading', { name: expectedSummary })).toBeTruthy();
    const savedMinutesLink = screen.queryByRole('link', { name: '저장된 회의록 보기' });
    if (expectedSummary === '일부 전달 실패') expect(savedMinutesLink).toBeNull();
    else expect(savedMinutesLink?.getAttribute('href')).toBe('/meetings/doc1');
  });

  it('renders API-010 delivery outcomes and shows the Email recipient address', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      if (path.endsWith('/participants')) return response({ items: [{ id: 'p1', name: 'Ada Lovelace', email: 'private@example.test' }] });
      if (path.endsWith('/meeting-sessions/s1')) return response({ sessionId: 's1', version: 4, status: 'DOCUMENT_SAVED', allowedActions: [],
        document: { documentId: 'doc1', documentUrl: 'https://docs.example/doc1' },
        deliveries: [
          { deliveryId: 'mail1', channel: 'EMAIL', recipientParticipantId: 'p1', status: 'FAILED', attemptCount: 1, retryable: false, errorCode: 'EMAIL_FAILED' },
          { deliveryId: 'slack1', channel: 'NOTIFICATION', status: 'FAILED', attemptCount: 1, retryable: false, errorCode: 'SLACK_REJECTED' },
        ] });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderComplete(fetchMock);

    expect(await screen.findByRole('heading', { name: '일부 전달 실패' })).toBeTruthy();
    expect(screen.queryByRole('link', { name: /저장된 회의록 열기/ })).toBeNull();
    expect(screen.getByText(/Ada Lovelace: 전달 실패/)).toBeTruthy();
    expect(screen.queryByText(/이메일 서비스가 참석자에게 전달하지 못했습니다/)).toBeNull();
    expect(screen.queryByText(/오류 코드: EMAIL_FAILED/)).toBeNull();
    expect(screen.getByText(/Slack: 전달 실패/)).toBeTruthy();
    expect(screen.getByText(/Slack이 알림 요청을 거부했습니다/)).toBeTruthy();
    expect(screen.getByText('private@example.test')).toBeTruthy();
    expect(screen.getByRole('region', { name: '회의 결과 처리 단계' }).getAttribute('aria-live')).toBe('polite');
  });

  it.each([
    ['SENT', false],
    ['PENDING', false],
    ['SENDING', false],
    ['FAILED', false],
  ])('does not offer API-016 retry for a %s delivery with retryable=%s', async (status, retryable) => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/meeting-sessions/s1')) return response({ sessionId: 's1', version: 1, status: 'DOCUMENT_SAVED', allowedActions: [],
        document: { documentId: 'doc1' }, deliveries: [{ deliveryId: 'd-gated', channel: 'EMAIL', recipientParticipantId: 'p1', status, retryable }] });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderComplete(fetchMock);

    await screen.findByText(new RegExp(`p1: ${status === 'SENT' ? '전달 완료' : status === 'FAILED' ? '전달 실패' : '전달 진행 중'}`));

    expect(screen.queryByRole('button', { name: /재전송/ })).toBeNull();
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/deliveries/d-gated/retry'))).toBe(false);
  });

  it('does not offer Email resend when Email is not configured', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Company', timezone: 'UTC' }, email: { enabled: false, configured: false } });
      if (path.endsWith('/meeting-sessions/s1')) return response({ sessionId: 's1', version: 1, status: 'DOCUMENT_SAVED', allowedActions: [],
        document: { documentId: 'doc1' }, deliveries: [{ deliveryId: 'd-email', channel: 'EMAIL', recipientParticipantId: 'p1', status: 'FAILED', retryable: true }] });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderComplete(fetchMock);

    expect(await screen.findByText(/p1: 전달 실패/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /재전송/ })).toBeNull();
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/deliveries/d-email/retry'))).toBe(false);
  });

  it('shows retry only on retryable failed rows and reconciles a 202 through API-010 polling', async () => {
    let snapshots = 0;
    let releaseRetry!: (result: Response) => void;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Company', timezone: 'UTC' }, email: { enabled: true, configured: true } });
      if (path.endsWith('/deliveries/d1/retry')) return new Promise<Response>((resolve) => { releaseRetry = resolve; });
      if (path.endsWith('/meeting-sessions/s1')) {
        snapshots += 1;
        return response({ sessionId: 's1', version: snapshots, status: 'DOCUMENT_SAVED', allowedActions: [],
          document: { documentId: 'doc1', documentUrl: 'javascript:alert(1)' },
          deliveries: [
            { deliveryId: 'd1', channel: 'EMAIL', recipientParticipantId: 'missing-person', status: snapshots === 1 ? 'FAILED' : snapshots === 2 ? 'PENDING' : 'SENT', retryable: snapshots === 1, attemptCount: snapshots === 1 ? 1 : 2 },
            { deliveryId: 'd2', channel: 'NOTIFICATION', status: 'FAILED', retryable: false, errorCode: 'SLACK_REJECTED' },
          ] });
      }
      throw new Error(`Unexpected request: ${path} ${init?.method ?? 'GET'}`);
    });
    renderComplete(fetchMock);

    const retry = await screen.findByRole('button', { name: /재전송.*missing-person/ });
    expect(screen.getByText(/Slack: 전달 실패/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Retry.*Slack/ })).toBeNull();
    retry.focus();
    expect(document.activeElement).toBe(retry);
    fireEvent.click(retry);
    expect((screen.getByRole('button', { name: /재전송.*missing-person/ }) as HTMLButtonElement).disabled).toBe(true);
    const retryRequest = fetchMock.mock.calls.find(([url]) => String(url).includes('/deliveries/d1/retry')) as unknown as [RequestInfo | URL, RequestInit];
    expect(new Headers(retryRequest[1].headers).get('Idempotency-Key')).toBeTruthy();
    expect(screen.queryByRole('link', { name: /저장된 회의록 열기/ })).toBeNull();
    releaseRetry(response({ deliveryId: 'd1', status: 'PENDING' }, 202));
    await waitFor(() => expect(screen.getByText(/missing-person: 전달 진행 중/)).toBeTruthy(), { timeout: 5_000 });
    expect(screen.getByText(/Slack: 전달 실패/)).toBeTruthy();
  }, 8_000);

  it('uses a participant ID fallback and presents a compact mobile-safe retry control', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Company', timezone: 'UTC' }, email: { enabled: true, configured: true } });
      if (path.endsWith('/meeting-sessions/s1')) return response({ sessionId: 's1', version: 1, status: 'DOCUMENT_SAVED', allowedActions: [],
        document: { documentId: 'doc1' }, deliveries: [{ deliveryId: 'd3', channel: 'EMAIL', recipientParticipantId: 'p-lost', status: 'FAILED', retryable: true }] });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderComplete(fetchMock);
    expect(await screen.findByText(/p-lost: 전달 실패/)).toBeTruthy();
    const retry = screen.getByRole('button', { name: /재전송.*p-lost/ });
    expect(retry.tagName).toBe('BUTTON');
    expect(screen.queryByRole('link', { name: /저장된 회의록 열기/ })).toBeNull();
    expect(screen.getByRole('main').classList.contains('complete-page')).toBe(true);
  });

  it.each([404, 409])('reconciles an API-016 HTTP %i once and preserves the refreshed document and independent delivery rows', async (status) => {
    let snapshotReads = 0;
    let retryCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      if (path.endsWith('/participants')) return response({ items: [{ id: 'p1', name: 'Ada', email: 'private@example.test' }] });
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Company', timezone: 'UTC' }, email: { enabled: true, configured: true } });
      if (path.endsWith('/deliveries/d-conflict/retry')) {
        retryCalls += 1;
        return response({ error: { code: status === 404 ? 'DELIVERY_NOT_FOUND' : 'DELIVERY_STATE_CONFLICT', message: 'provider token=secret-detail' } }, status);
      }
      if (path.endsWith('/meeting-sessions/s1')) {
        snapshotReads += 1;
        return response({ sessionId: 's1', version: snapshotReads, status: 'DOCUMENT_SAVED', allowedActions: [],
          document: { documentId: 'doc1', documentUrl: 'https://docs.example/doc1' },
          deliveries: snapshotReads === 1
            ? [
              { deliveryId: 'd-conflict', channel: 'EMAIL', recipientParticipantId: 'p1', status: 'FAILED', retryable: true },
              { deliveryId: 'd-independent', channel: 'NOTIFICATION', status: 'SENT', retryable: false },
            ]
            : [
              { deliveryId: 'd-conflict', channel: 'EMAIL', recipientParticipantId: 'p1', status: 'FAILED', retryable: false },
              { deliveryId: 'd-independent', channel: 'NOTIFICATION', status: 'SENT', retryable: false },
            ] });
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    renderComplete(fetchMock);

    fireEvent.click(await screen.findByRole('button', { name: '재전송 Ada' }));

    expect((await screen.findByRole('status')).textContent).toMatch(/중복 전달을 막기 위해 자동 재전송하지 않습니다/);
    expect(screen.getByText(/중복 전달을 막기 위해 자동 재전송하지 않습니다/)).toBeTruthy();
    expect(await screen.findByText(/Slack: 전달 완료/)).toBeTruthy();
    expect(screen.queryByRole('link', { name: /저장된 회의록 열기/ })).toBeNull();
    expect(screen.queryByRole('button', { name: '재전송 Ada' })).toBeNull();
    expect(document.body.textContent).not.toMatch(/provider token|secret-detail/);
    expect(snapshotReads).toBe(2);
    expect(retryCalls).toBe(1);
  });

  it('retains and reuses the same key when both the retry response and reconciliation snapshot are lost', async () => {
    let postCount = 0;
    let snapshotCount = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Company', timezone: 'UTC' }, email: { enabled: true, configured: true } });
      if (path.endsWith('/deliveries/d4/retry')) {
        postCount += 1;
        if (postCount === 1) throw new TypeError('connection lost');
        return response({ deliveryId: 'd4', status: 'PENDING' }, 202);
      }
      if (path.endsWith('/meeting-sessions/s1')) {
        snapshotCount += 1;
        if (snapshotCount === 2 || snapshotCount === 3) throw new TypeError('connection lost');
        return response({ sessionId: 's1', version: snapshotCount, status: 'DOCUMENT_SAVED', allowedActions: [],
          document: { documentId: 'doc1' }, deliveries: [{ deliveryId: 'd4', channel: 'EMAIL', recipientParticipantId: 'p4', status: snapshotCount >= 3 ? 'PENDING' : 'FAILED', retryable: snapshotCount < 3 }] });
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    renderComplete(fetchMock);
    fireEvent.click(await screen.findByRole('button', { name: /재전송.*p4/ }));
    await screen.findByText(/재시도 결과를 확인할 수 없습니다/);
    fireEvent.click(screen.getByRole('button', { name: /재전송.*p4/ }));
    await waitFor(() => expect(screen.queryByRole('button', { name: /재전송.*p4/ })).toBeNull());

    const retryCalls = fetchMock.mock.calls.filter(([url]) => String(url).includes('/deliveries/d4/retry')) as unknown as [RequestInfo | URL, RequestInit][];
    expect(retryCalls).toHaveLength(2);
    expect(new Headers(retryCalls[0][1].headers).get('Idempotency-Key')).toBe(new Headers(retryCalls[1][1].headers).get('Idempotency-Key'));
  }, 8_000);

  it('resets API-010 version state on session navigation and retries only the destination session delivery', async () => {
    let sessionBReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/app-config')) return response({ company: { id: 'c1', name: 'Company', timezone: 'UTC' }, email: { enabled: true, configured: true } });
      if (path.endsWith('/deliveries/dB/retry')) return response({ deliveryId: 'dB', status: 'PENDING' }, 202);
      if (path.endsWith('/meeting-sessions/session-A')) return response({ sessionId: 'session-A', version: 40, status: 'DOCUMENT_SAVED', allowedActions: [],
        document: { documentId: 'docA' }, deliveries: [{ deliveryId: 'dA', channel: 'EMAIL', recipientParticipantId: 'recipient-A', status: 'FAILED', retryable: true }] });
      if (path.endsWith('/meeting-sessions/session-B')) {
        sessionBReads += 1;
        return response({ sessionId: 'session-B', version: sessionBReads === 1 ? 1 : 2, status: 'DOCUMENT_SAVED', allowedActions: [],
          document: { documentId: 'docB' }, deliveries: [{ deliveryId: 'dB', channel: 'EMAIL', recipientParticipantId: 'recipient-B', status: sessionBReads === 1 ? 'FAILED' : 'PENDING', retryable: sessionBReads === 1 }] });
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/meetings/session-A/complete']}><Routes>
      <Route path="/meetings/:sessionId/complete" element={<><CompletePage /><SwitchToOtherSession /></>} />
    </Routes></MemoryRouter>);

    expect(await screen.findByRole('button', { name: /재전송.*recipient-A/ })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '다른 회의 열기' }));
    expect(await screen.findByRole('button', { name: /재전송.*recipient-B/ })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /재전송.*recipient-A/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /재전송.*recipient-B/ }));
    await waitFor(() => expect(screen.queryByRole('button', { name: /재전송.*recipient-B/ })).toBeNull());

    const retryRequest = fetchMock.mock.calls.find(([url]) => String(url).includes('/deliveries/dB/retry')) as unknown as [RequestInfo | URL, RequestInit];
    expect(String(retryRequest[0])).toContain('/meeting-sessions/session-B/deliveries/dB/retry');
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/session-A/deliveries/'))).toBe(false);
  }, 8_000);
});

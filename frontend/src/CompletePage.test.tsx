import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router';
import { CompletePage } from './CompletePage';

function response(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ data }), { status, headers: { 'Content-Type': 'application/json' } });
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function renderComplete(fetchMock: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetchMock);
  return render(<MemoryRouter initialEntries={['/meetings/s1/complete']}>
    <Routes><Route path="/meetings/:sessionId/complete" element={<CompletePage />} /></Routes>
  </MemoryRouter>);
}

function SwitchToOtherSession() {
  const navigate = useNavigate();
  return <button type="button" onClick={() => navigate('/meetings/session-B/complete')}>다른 회의 열기</button>;
}

describe('CompletePage', () => {
  it('ASYNC-GET-RETRY uses the shared safe error state and retries the API-010 GET explicitly', async () => {
    let snapshotReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
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
    ['all deliveries failed', [{ deliveryId: 'e1', channel: 'EMAIL', status: 'FAILED', retryable: false }], '전달 실패'],
    ['no delivery rows', [], '문서 저장 완료'],
  ])('derives %s summary only from the API-010 snapshot', async (_caseName, deliveries, expectedSummary) => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/meeting-sessions/s1')) return response({ sessionId: 's1', version: 1, status: 'DOCUMENT_SAVED', allowedActions: [],
        document: { documentId: 'doc1' }, deliveries });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderComplete(fetchMock);
    expect(await screen.findByRole('heading', { name: expectedSummary })).toBeTruthy();
  });

  it('renders API-010 document and independent Email/Slack outcomes without exposing email addresses', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/participants')) return response({ items: [{ id: 'p1', name: 'Ada Lovelace', email: 'private@example.test' }] });
      if (path.endsWith('/meeting-sessions/s1')) return response({ sessionId: 's1', version: 4, status: 'DOCUMENT_SAVED', allowedActions: [],
        document: { documentId: 'doc1', documentUrl: 'https://docs.example/doc1' },
        deliveries: [
          { deliveryId: 'mail1', channel: 'EMAIL', recipientParticipantId: 'p1', status: 'SENT', attemptCount: 1, retryable: false },
          { deliveryId: 'slack1', channel: 'NOTIFICATION', status: 'FAILED', attemptCount: 1, retryable: false, errorCode: 'SLACK_REJECTED' },
        ] });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderComplete(fetchMock);

    expect(await screen.findByRole('heading', { name: '일부 전달 실패' })).toBeTruthy();
    expect(screen.getByRole('link', { name: /저장된 회의록 열기/ }).getAttribute('href')).toBe('https://docs.example/doc1');
    expect(screen.getByText(/Ada Lovelace: 전송 요청 수락/)).toBeTruthy();
    expect(screen.getByText(/Slack: 전달 실패/)).toBeTruthy();
    expect(document.body.textContent).not.toContain('private@example.test');
    expect(screen.getByRole('status').getAttribute('aria-live')).toBe('polite');
  });

  it.each([
    ['SENT', false],
    ['PENDING', false],
    ['SENDING', false],
    ['FAILED', false],
  ])('does not offer API-016 retry for a %s delivery with retryable=%s', async (status, retryable) => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/meeting-sessions/s1')) return response({ sessionId: 's1', version: 1, status: 'DOCUMENT_SAVED', allowedActions: [],
        document: { documentId: 'doc1' }, deliveries: [{ deliveryId: 'd-gated', channel: 'EMAIL', recipientParticipantId: 'p1', status, retryable }] });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderComplete(fetchMock);

    await screen.findByText(new RegExp(`p1: ${status === 'SENT' ? '전송 요청 수락' : status === 'FAILED' ? '전달 실패' : '전달 진행 중'}`));

    expect(screen.queryByRole('button', { name: /Retry/ })).toBeNull();
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/deliveries/d-gated/retry'))).toBe(false);
  });

  it('shows retry only on retryable failed rows and reconciles a 202 through API-010 polling', async () => {
    let snapshots = 0;
    let releaseRetry!: (result: Response) => void;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/participants')) return response({ items: [] });
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

    const retry = await screen.findByRole('button', { name: /Retry.*missing-person/ });
    expect(screen.getByText(/Slack: 전달 실패/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Retry.*Slack/ })).toBeNull();
    retry.focus();
    expect(document.activeElement).toBe(retry);
    fireEvent.click(retry);
    expect((screen.getByRole('button', { name: /Retry.*missing-person/ }) as HTMLButtonElement).disabled).toBe(true);
    const retryRequest = fetchMock.mock.calls.find(([url]) => String(url).includes('/deliveries/d1/retry')) as unknown as [RequestInfo | URL, RequestInit];
    expect(new Headers(retryRequest[1].headers).get('Idempotency-Key')).toBeTruthy();
    expect(screen.queryByRole('link', { name: /저장된 회의록 열기/ })).toBeNull();
    releaseRetry(response({ deliveryId: 'd1', status: 'PENDING' }, 202));
    await waitFor(() => expect(screen.getByText(/missing-person: 전송 요청 수락/)).toBeTruthy(), { timeout: 5_000 });
    expect(screen.getByText(/Slack: 전달 실패/)).toBeTruthy();
  }, 8_000);

  it('uses a participant ID fallback and presents a compact mobile-safe retry control', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/participants')) return response({ items: [] });
      if (path.endsWith('/meeting-sessions/s1')) return response({ sessionId: 's1', version: 1, status: 'DOCUMENT_SAVED', allowedActions: [],
        document: { documentId: 'doc1' }, deliveries: [{ deliveryId: 'd3', channel: 'EMAIL', recipientParticipantId: 'p-lost', status: 'FAILED', retryable: true }] });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderComplete(fetchMock);
    expect(await screen.findByText(/p-lost: 전달 실패/)).toBeTruthy();
    const retry = screen.getByRole('button', { name: /Retry.*p-lost/ });
    expect(retry.tagName).toBe('BUTTON');
    expect(screen.queryByRole('link', { name: /저장된 회의록 열기/ })).toBeNull();
    expect(screen.getByRole('main').classList.contains('complete-page')).toBe(true);
  });

  it.each([404, 409])('reconciles an API-016 HTTP %i once and preserves the refreshed document and independent delivery rows', async (status) => {
    let snapshotReads = 0;
    let retryCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/participants')) return response({ items: [{ id: 'p1', name: 'Ada', email: 'private@example.test' }] });
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

    fireEvent.click(await screen.findByRole('button', { name: 'Retry Ada' }));

    expect((await screen.findByRole('status')).textContent).toMatch(/최신 상태를 확인했습니다\. 재시도 가능 여부를 확인해 주세요/);
    expect(screen.getByText(/중복 전달을 막기 위해 자동 재전송하지 않습니다/)).toBeTruthy();
    expect(await screen.findByText(/Slack: 전송 요청 수락/)).toBeTruthy();
    expect(screen.getByRole('link', { name: /저장된 회의록 열기/ }).getAttribute('href')).toBe('https://docs.example/doc1');
    expect(screen.queryByRole('button', { name: 'Retry Ada' })).toBeNull();
    expect(document.body.textContent).not.toMatch(/provider token|secret-detail|private@example\.test/);
    expect(snapshotReads).toBe(2);
    expect(retryCalls).toBe(1);
  });

  it('retains and reuses the same key when both the retry response and reconciliation snapshot are lost', async () => {
    let postCount = 0;
    let snapshotCount = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/participants')) return response({ items: [] });
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
    fireEvent.click(await screen.findByRole('button', { name: /Retry.*p4/ }));
    await screen.findByText(/재시도 결과를 확인할 수 없습니다/);
    fireEvent.click(screen.getByRole('button', { name: /Retry.*p4/ }));
    await waitFor(() => expect(screen.queryByRole('button', { name: /Retry.*p4/ })).toBeNull());

    const retryCalls = fetchMock.mock.calls.filter(([url]) => String(url).includes('/deliveries/d4/retry')) as unknown as [RequestInfo | URL, RequestInit][];
    expect(retryCalls).toHaveLength(2);
    expect(new Headers(retryCalls[0][1].headers).get('Idempotency-Key')).toBe(new Headers(retryCalls[1][1].headers).get('Idempotency-Key'));
  }, 8_000);

  it('resets API-010 version state on session navigation and retries only the destination session delivery', async () => {
    let sessionBReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/participants')) return response({ items: [] });
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
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/session-A/complete']}><Routes>
      <Route path="/meetings/:sessionId/complete" element={<><CompletePage /><SwitchToOtherSession /></>} />
    </Routes></MemoryRouter>);

    expect(await screen.findByRole('button', { name: /Retry.*recipient-A/ })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '다른 회의 열기' }));
    expect(await screen.findByRole('button', { name: /Retry.*recipient-B/ })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Retry.*recipient-A/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /Retry.*recipient-B/ }));
    await waitFor(() => expect(screen.queryByRole('button', { name: /Retry.*recipient-B/ })).toBeNull());

    const retryRequest = fetchMock.mock.calls.find(([url]) => String(url).includes('/deliveries/dB/retry')) as unknown as [RequestInfo | URL, RequestInit];
    expect(String(retryRequest[0])).toContain('/meeting-sessions/session-B/deliveries/dB/retry');
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/session-A/deliveries/'))).toBe(false);
  }, 8_000);
});

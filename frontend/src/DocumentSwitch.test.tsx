import { withHealthyBackend } from './testUtils/withHealthyBackend';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import type { SetupOperation, SetupSnapshot } from './documentSetupApi';

const source = { provider: 'NOTION' as const, connectionVersion: 4, rootId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', rootUrl: 'https://notion.example/source' };
const target = { provider: 'CONFLUENCE' as const, connectionVersion: 5, rootId: '12345', rootUrl: 'https://example.atlassian.net/target' };
const ready: SetupSnapshot = { version: 10, status: 'READY', active: source, draft: null, operation: null, canCreateMeeting: true };
const prepared: SetupSnapshot = { ...ready, version: 14, draft: { draftId: 'target-draft', revision: 2, provider: 'CONFLUENCE', state: 'PREPARED' }, operation: { operationId: 'prepare-1', type: 'INITIALIZE', status: 'SUCCEEDED' } };
function op(status: string, allowedActions: string[] = []): SetupOperation {
  return { operationId: 'switch-1', type: 'SWITCH', status, stage: 'COPY_MEETINGS', counts: { participantsTotal: 3, participantsCopied: 3, meetingsTotal: 7, meetingsCopied: 2 }, cancelRequested: false, retryable: status === 'FAILED', allowedActions, version: 15 };
}
function json(data: unknown, status = 200) { return new Response(JSON.stringify({ data }), { status, headers: { 'Content-Type': 'application/json' } }); }
function failure(code: string, status: number) { return new Response(JSON.stringify({ error: { code } }), { status, headers: { 'Content-Type': 'application/json' } }); }
function harness(initial: SetupSnapshot = ready, initialOperation = op('RUNNING', ['CANCEL'])) {
  let snapshot = initial;
  let operation = initialOperation;
  let switchFailure: Response | undefined;
  let actionFailure: Response | undefined;
  let switchLost = false;
  let configUnavailable = false;
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const path = new URL(String(input)).pathname;
    if (path.endsWith('/document-setup')) return json(snapshot);
    if (path.endsWith('/drafts')) { snapshot = { ...ready, version: 11, draft: { draftId: 'target-draft', revision: 2, provider: 'CONFLUENCE', state: 'DRAFT' } }; return json({ draftId: 'target-draft', revision: 2, state: 'DRAFT', version: 11 }, 201); }
    if (path.endsWith('/test')) { snapshot = { ...snapshot, version: 12, draft: { ...snapshot.draft!, state: 'TESTED' } }; return json({ draftId: 'target-draft', revision: 2, expiresAt: '2099-10-12T00:00:00Z', authenticated: true, parentAccessible: true, writeCapability: 'UNVERIFIED', version: 12 }); }
    if (path.endsWith('/initialize')) { snapshot = prepared; return json({ operationId: 'prepare-1', status: 'PENDING', version: 13 }, 202); }
    if (path.endsWith('/operations/prepare-1')) return json({ ...op('SUCCEEDED'), operationId: 'prepare-1', type: 'INITIALIZE', stage: 'ACTIVATE', version: 14 });
    if (path.endsWith('/switch')) {
      if (switchLost) throw new Error('Synthetic lost response');
      if (switchFailure) { snapshot = { ...prepared, version: 19, draft: { ...prepared.draft!, revision: 3 } }; return switchFailure; }
      snapshot = { ...prepared, version: 15, status: 'SWITCHING', canCreateMeeting: false, operation: { operationId: 'switch-1', type: 'SWITCH', status: operation.status } };
      return json({ operationId: 'switch-1', status: 'PENDING', version: 15 }, 202);
    }
    if (/operations\/switch-1\/(retry|cancel)$/.test(path)) {
      if (actionFailure) return actionFailure;
      operation = op(path.endsWith('/retry') ? 'PENDING' : 'CANCELLED');
      snapshot = { ...snapshot, version: 16, status: operation.status === 'CANCELLED' ? 'READY' : 'SWITCHING', operation: { operationId: 'switch-1', type: 'SWITCH', status: operation.status } };
      return json({ operationId: 'switch-1', status: operation.status, version: 16 }, 202);
    }
    if (path.endsWith('/operations/switch-1')) {
      if (operation.status === 'SUCCEEDED') snapshot = { ...ready, active: target, version: 18, operation: { operationId: 'switch-1', type: 'SWITCH', status: 'SUCCEEDED' } };
      return json(operation);
    }
    if (path.endsWith('/app-config')) return configUnavailable ? failure('NETWORK_ERROR', 503) : json({ company: { id: 'c1', name: 'Synthetic', timezone: 'Asia/Seoul' }, document: { ...snapshot.active, configured: true }, setup: { status: snapshot.status, required: false, canCreateMeeting: snapshot.canCreateMeeting, operationId: snapshot.operation?.operationId } });
    if (path.endsWith('/participants') && init?.method === 'POST') return failure('DOCUMENT_CHANGE_BUSY', 409);
    if (path.endsWith('/participants')) return json({ items: [{ id: 'target-person-1', name: 'Synthetic Ada', email: 'ada@example.test' }] });
    throw new Error(`Unexpected request ${path}`);
  });
  vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
  return { fetchMock, setSnapshot: (value: SetupSnapshot) => { snapshot = value; }, failSwitch: () => { switchFailure = failure('DOCUMENT_SETTINGS_VERSION_CONFLICT', 412); }, loseSwitch: () => { switchLost = true; }, configUnavailable: (value: boolean) => { configUnavailable = value; }, failAction: () => { actionFailure = failure('DOCUMENT_CHANGE_BUSY', 409); } };
}
function settings() { return render(<MemoryRouter initialEntries={['/settings/document']}><App /></MemoryRouter>); }
async function connectTarget() {
  fireEvent.click(await screen.findByRole('button', { name: '문서 서비스 변경' }));
  fireEvent.click(screen.getByRole('button', { name: 'Confluence Cloud 선택' }));
  for (const [label, value] of [['Confluence 사이트 URL', 'https://example.atlassian.net'], ['Atlassian 계정 이메일', 'synthetic@example.test'], ['Atlassian API token', 'synthetic-secret'], ['Space ID', '42']]) fireEvent.change(screen.getByLabelText(label), { target: { value } });
  fireEvent.click(screen.getByRole('button', { name: '연결 확인' }));
  expect(screen.getByText('현재 연결: Notion')).toBeTruthy();
  await screen.findByRole('heading', { name: '자료 이전 방식' });
  expect(screen.queryByLabelText('Atlassian API token')).toBeNull();
}
function writes(fetchMock: ReturnType<typeof harness>['fetchMock'], suffix: string) { return fetchMock.mock.calls.filter(([url, init]) => String(url).endsWith(suffix) && init?.method === 'POST'); }
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('document service change', () => {
  it('CHANGE-CHOICE defaults to COPY_ALL and sends one confirmed switch with the prepared revision', async () => {
    const { fetchMock } = harness(); settings(); await connectTarget();
    expect((screen.getByRole('radio', { name: '전체 복사' }) as HTMLInputElement).checked).toBe(true);
    expect(screen.getByText(/참석자와 회의록.*Transcript.*Minutes/)).toBeTruthy();
    expect(screen.getByText(/원본.*보존/)).toBeTruthy();
    const button = screen.getByRole('button', { name: '문서 서비스 전환' }); fireEvent.click(button); fireEvent.click(button);
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/operations/switch-1'))).toBe(true));
    const calls = writes(fetchMock, '/switch'); expect(calls).toHaveLength(1);
    expect(JSON.parse(String(calls[0][1]?.body))).toEqual({ draftId: 'target-draft', draftRevision: 2, mode: 'COPY_ALL', confirmSourcePreserved: true });
    expect(calls[0][1]?.headers).toMatchObject({ 'If-Match': '"14"', 'X-Document-Setup-Request': 'true', 'Idempotency-Key': expect.any(String) });
  });
  it('CHANGE-CHOICE requires explicit START_EMPTY confirmation before sending its mode', async () => {
    const { fetchMock } = harness(prepared); settings(); fireEvent.click(await screen.findByRole('button', { name: '문서 서비스 변경' }));
    fireEvent.click(screen.getByRole('radio', { name: '빈 구조로 새 시작' }));
    expect((screen.getByRole('button', { name: '문서 서비스 전환' }) as HTMLButtonElement).disabled).toBe(true);
    expect(writes(fetchMock, '/switch')).toHaveLength(0);
    fireEvent.click(screen.getByRole('checkbox', { name: /기존 자료가 새 서비스 목록에 나타나지 않음/ }));
    fireEvent.click(screen.getByRole('button', { name: '문서 서비스 전환' })); await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/operations/switch-1'))).toBe(true));
    expect(JSON.parse(String(writes(fetchMock, '/switch')[0][1]?.body))).toMatchObject({ mode: 'START_EMPTY', confirmSourcePreserved: true });
  });
  it.each(['FAILED', 'INTERRUPTED'])('CHANGE-PROGRESS explicitly resumes %s using the same operation and current version', async (status) => {
    const snapshot: SetupSnapshot = { ...prepared, status: 'ATTENTION_REQUIRED', version: 15, canCreateMeeting: false, operation: { operationId: 'switch-1', type: 'SWITCH', status } };
    const { fetchMock } = harness(snapshot, op(status, ['RETRY', 'CANCEL'])); settings();
    expect(await screen.findByText('참석자 3/3명 · 회의 2/7건')).toBeTruthy();
    expect(writes(fetchMock, '/retry')).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: '작업 재개' })); await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/operations/switch-1/retry'))).toBe(true));
    expect(writes(fetchMock, '/operations/switch-1/retry')).toHaveLength(1);
    expect(writes(fetchMock, '/operations/switch-1/retry')[0][1]?.headers).toMatchObject({ 'If-Match': '"15"' });
    expect(writes(fetchMock, '/switch')).toHaveLength(0);
  });
  it('CHANGE-PROGRESS reconciliation allows advertised cancel only, with no automatic creation retry', async () => {
    const snapshot: SetupSnapshot = { ...prepared, version: 15, status: 'ATTENTION_REQUIRED', canCreateMeeting: false, operation: { operationId: 'switch-1', type: 'SWITCH', status: 'RECONCILIATION_REQUIRED' } };
    const { fetchMock } = harness(snapshot, op('RECONCILIATION_REQUIRED', ['REVIEW_PROVIDER', 'CANCEL'])); settings();
    await screen.findByText('참석자 3/3명 · 회의 2/7건'); expect(screen.queryByRole('button', { name: '작업 재개' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '작업 취소' })); await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/operations/switch-1/cancel'))).toBe(true));
    expect(writes(fetchMock, '/operations/switch-1/cancel')).toHaveLength(1); expect(writes(fetchMock, '/switch')).toHaveLength(0);
  });
  it('CHANGE-REFRESH reenters the same running operation without a second start', async () => {
    const snapshot: SetupSnapshot = { ...prepared, version: 15, status: 'SWITCHING', canCreateMeeting: false, operation: { operationId: 'switch-1', type: 'SWITCH', status: 'RUNNING' } };
    const { fetchMock } = harness(snapshot); const view = settings(); await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/operations/switch-1'))).toBe(true)); view.unmount(); settings();
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/operations/switch-1'))).toBe(true)); expect(screen.getByText('현재 연결: Notion')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '문서 서비스 변경' })).toBeNull(); expect(writes(fetchMock, '/switch')).toHaveLength(0);
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/operations/switch-1'))).toHaveLength(2);
  });
  it('CHANGE-REFRESH a switch 412 shows the reloaded revision and invalidates the prepared action', async () => {
    const { fetchMock, failSwitch } = harness(prepared); failSwitch(); settings(); fireEvent.click(await screen.findByRole('button', { name: '문서 서비스 변경' }));
    fireEvent.click(screen.getByRole('button', { name: '문서 서비스 전환' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/설정 버전.*19.*draft revision.*3/);
    expect(screen.queryByRole('button', { name: '문서 서비스 전환' })).toBeNull(); expect(writes(fetchMock, '/switch')).toHaveLength(1);
  });
  it('CHANGE-INTEGRATION successful switching refreshes the new connection and target roster', async () => {
    const snapshot: SetupSnapshot = { ...ready, active: target, version: 18, operation: { operationId: 'switch-1', type: 'SWITCH', status: 'SUCCEEDED' } };
    const { fetchMock } = harness(snapshot, op('SUCCEEDED')); const listener = vi.fn(); window.addEventListener('document-connection-changed', listener);
    try {
      settings(); expect(await screen.findByRole('heading', { name: 'Confluence' })).toBeTruthy(); await waitFor(() => expect(listener).toHaveBeenCalled());
      expect((listener.mock.calls[0][0] as CustomEvent).detail).toEqual({ connectionVersion: 5 });
      expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/app-config'))).toHaveLength(1);
      expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/participants'))).toHaveLength(1);
      expect(screen.queryByRole('link', { name: '참석자 관리' })).toBeNull();
    } finally { window.removeEventListener('document-connection-changed', listener); }
  });
  it('CHANGE-GATE reports a switch lock from an already open participant write form', async () => {
    const { fetchMock } = harness(); render(<MemoryRouter initialEntries={['/settings/participants']}><App /></MemoryRouter>);
    fireEvent.click(await screen.findByRole('button', { name: '참석자 추가' })); fireEvent.change(screen.getByLabelText('이름'), { target: { value: 'Synthetic Grace' } }); fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'grace@example.test' } }); fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/문서 서비스 변경 중.*쓰기.*다시/);
    expect(writes(fetchMock, '/participants')).toHaveLength(1); expect(screen.getByRole('dialog')).toBeTruthy();
  });
  it('CHANGE-ACCESSIBILITY retry failure is announced and leaves a keyboard reachable recovery action', async () => {
    const snapshot: SetupSnapshot = { ...prepared, version: 15, status: 'ATTENTION_REQUIRED', operation: { operationId: 'switch-1', type: 'SWITCH', status: 'FAILED' }, canCreateMeeting: false };
    const { fetchMock, failAction } = harness(snapshot, op('FAILED', ['RETRY', 'CANCEL'])); failAction(); settings(); const retry = await screen.findByRole('button', { name: '작업 재개' }); retry.focus(); fireEvent.click(retry);
    expect((await screen.findByRole('alert')).textContent).toMatch(/작업.*확인/); await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: '진행 상태 새로고침' })));
    expect(writes(fetchMock, '/operations/switch-1/retry')).toHaveLength(1); expect(screen.queryByLabelText('Atlassian API token')).toBeNull();
  });
  it('CHANGE-REFRESH reconciles a terminal operation returned during reentry before refreshing consumers', async () => {
    const snapshot: SetupSnapshot = { ...prepared, version: 15, status: 'SWITCHING', canCreateMeeting: false, operation: { operationId: 'switch-1', type: 'SWITCH', status: 'RUNNING' } };
    const { fetchMock } = harness(snapshot, op('SUCCEEDED')); settings();
    expect(await screen.findByRole('heading', { name: 'Confluence' }, { timeout: 3500 })).toBeTruthy(); await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/participants'))).toBe(true));
  });
  it('CHANGE-REFRESH after a 412 retesting and preparing the latest draft permits a new confirmed switch', async () => {
    const { fetchMock, failSwitch, setSnapshot } = harness(prepared); failSwitch(); settings(); fireEvent.click(await screen.findByRole('button', { name: '문서 서비스 변경' })); fireEvent.click(screen.getByRole('button', { name: '문서 서비스 전환' })); await screen.findByRole('alert');
    setSnapshot(ready); fireEvent.click(screen.getByRole('button', { name: 'Confluence Cloud 선택' }));
    for (const [label, value] of [['Confluence 사이트 URL', 'https://example.atlassian.net'], ['Atlassian 계정 이메일', 'synthetic@example.test'], ['Atlassian API token', 'synthetic-secret'], ['Space ID', '42']]) fireEvent.change(screen.getByLabelText(label), { target: { value } });
    fireEvent.click(screen.getByRole('button', { name: '연결 확인' }));
    expect(await screen.findByRole('heading', { name: '자료 이전 방식' })).toBeTruthy(); expect(writes(fetchMock, '/switch')).toHaveLength(1);
  });
  it('CHANGE-PROGRESS lost switch response keeps source visible and does not mistake an old preparation for the switch', async () => {
    const { fetchMock, loseSwitch } = harness(prepared); loseSwitch(); settings(); fireEvent.click(await screen.findByRole('button', { name: '문서 서비스 변경' })); fireEvent.click(screen.getByRole('button', { name: '문서 서비스 전환' })); await screen.findByRole('heading', { name: '요청 상태 확인' });
    expect(screen.queryByRole('button', { name: '문서 서비스 전환' })).toBeNull(); expect(screen.getByText('현재 연결: Notion')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '다시 확인' }));
    await screen.findByText(/진행 중인 변경 작업이 없습니다.*연결 확인/);
    expect(screen.queryByRole('button', { name: '문서 서비스 전환' })).toBeNull(); expect(writes(fetchMock, '/switch')).toHaveLength(1);
  });
  it('CHANGE-INTEGRATION an unavailable consumer refresh is announced and can be retried without switching again', async () => {
    const snapshot: SetupSnapshot = { ...ready, active: target, version: 18, operation: { operationId: 'switch-1', type: 'SWITCH', status: 'SUCCEEDED' } };
    const { fetchMock, configUnavailable } = harness(snapshot, op('SUCCEEDED')); configUnavailable(true); settings();
    expect((await screen.findByRole('alert')).textContent).toMatch(/새 연결.*불러오지 못했습니다/);
    configUnavailable(false); fireEvent.click(screen.getByRole('button', { name: '연결 데이터 다시 확인' }));
    expect(await screen.findByText('문서 서비스 연결이 준비되었습니다.')).toBeTruthy(); expect(writes(fetchMock, '/switch')).toHaveLength(0);
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/app-config'))).toHaveLength(2);
  });
  it('CHANGE-INTEGRATION a prepared Notion target sends COPY_ALL while Confluence remains active', async () => {
    const reverse: SetupSnapshot = { ...prepared, active: { ...target, connectionVersion: 8 }, draft: { draftId: 'notion-target', revision: 4, provider: 'NOTION', state: 'PREPARED' } };
    let snapshot = reverse;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return json(snapshot);
      if (path.endsWith('/operations/prepare-1')) return json({ ...op('SUCCEEDED'), operationId: 'prepare-1', type: 'INITIALIZE', version: 14 });
      if (path.endsWith('/switch')) { snapshot = { ...reverse, status: 'SWITCHING', canCreateMeeting: false, version: 15, operation: { operationId: 'switch-1', type: 'SWITCH', status: 'RUNNING' } }; return json({ operationId: 'switch-1', status: 'PENDING', version: 15 }, 202); }
      if (path.endsWith('/operations/switch-1')) return json(op('RUNNING', ['CANCEL']));
      throw new Error(`Unexpected request ${path} ${init?.method}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock)); settings(); fireEvent.click(await screen.findByRole('button', { name: '문서 서비스 변경' }));
    expect(screen.getByText('대상 연결: Notion')).toBeTruthy(); fireEvent.click(screen.getByRole('button', { name: '문서 서비스 전환' })); await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/operations/switch-1'))).toBe(true));
    expect(screen.getByText('현재 연결: Confluence Cloud')).toBeTruthy(); expect(screen.getByText('연결 버전: 8')).toBeTruthy();
    expect(JSON.parse(String(writes(fetchMock, '/switch')[0][1]?.body))).toEqual({ draftId: 'notion-target', draftRevision: 4, mode: 'COPY_ALL', confirmSourcePreserved: true });
  });
  it('CHANGE-REFRESH polling stops after leaving a running operation', async () => {
    const snapshot: SetupSnapshot = { ...prepared, status: 'SWITCHING', canCreateMeeting: false, operation: { operationId: 'switch-1', type: 'SWITCH', status: 'RUNNING' } };
    const { fetchMock } = harness(snapshot); const view = settings(); await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/operations/switch-1'))).toBe(true)); view.unmount();
    await new Promise((resolve) => setTimeout(resolve, 1000));
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/operations/switch-1'))).toHaveLength(1); expect(writes(fetchMock, '/switch')).toHaveLength(0);
  });
});

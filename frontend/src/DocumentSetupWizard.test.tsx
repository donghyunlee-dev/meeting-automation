import { withHealthyBackend } from './testUtils/withHealthyBackend';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router';
import { App } from './App';

function response(data: unknown, status = 200) {
  return new Response(JSON.stringify({ data }), { status, headers: { 'Content-Type': 'application/json' } });
}

function errorResponse(code: string, status: number) {
  return new Response(JSON.stringify({ error: { code } }), { status, headers: { 'Content-Type': 'application/json' } });
}

function operation(operationId: string, status: string) {
  return { operationId, type: 'INITIALIZE', status, stage: 'STRUCTURE', counts: { participantsTotal: 0, participantsCopied: 0, meetingsTotal: 0, meetingsCopied: 0 }, cancelRequested: false, retryable: false, allowedActions: [], version: 3 };
}

beforeEach(() => { window.localStorage.clear(); });
afterEach(() => { cleanup(); window.localStorage.clear(); vi.unstubAllGlobals(); });

describe('document setup wizard', () => {
  it('routes an unconfigured meeting entry to setup without requesting meeting data', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response({ version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false })));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/meetings/new']}><App /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: '문서 서비스 연결' }, { timeout: 5000 })).toBeTruthy();
    expect(fetchMock.mock.calls.some(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions'))).toBe(false);
  });

  it('gates the home route during first run and removes the loop-back action', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response({ version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false })));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/']}><App /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: '문서 서비스 연결' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '나중에 하기' })).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Meeting Automation' })).toBeNull();
  });

  it('gates unconfigured participant settings and redirects to the first-run wizard', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response({ version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false })));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/settings/participants']}><App /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: '문서 서비스 연결' })).toBeTruthy();
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/participants'))).toBe(false);
  });

  it('clears provider-specific fields when the provider changes', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response({ version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);
    await screen.findByRole('heading', { name: '문서 서비스 연결' }, { timeout: 5000 });
    fireEvent.click(screen.getByRole('button', { name: 'Notion 선택' }));
    fireEvent.change(screen.getByLabelText('Notion 내부 연결 토큰'), { target: { value: 'secret-token' } });
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'notion-parent' } });
    fireEvent.click(screen.getByRole('button', { name: '서비스 선택으로 돌아가기' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confluence Cloud 선택' }));
    expect(screen.queryByLabelText('Notion 내부 연결 토큰')).toBeNull();
    expect(screen.getByLabelText('Atlassian API token')).toBeTruthy();
    expect((screen.getByLabelText('Atlassian API token') as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('Atlassian 계정 이메일') as HTMLInputElement).value).toBe('');
  });

  it('returns from the Notion connection form to provider selection', async () => {
    vi.stubGlobal('fetch', withHealthyBackend(vi.fn().mockResolvedValue(response({ version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false }))));
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);
    await screen.findByRole('heading', { name: '문서 서비스 연결' }, { timeout: 5000 });
    fireEvent.click(screen.getByRole('button', { name: 'Notion 선택' }));

    const back = screen.getByRole('button', { name: '서비스 선택으로 돌아가기' });
    const providerTitle = screen.getByText('Notion에 연결');
    const connect = screen.getByRole('button', { name: '연결 확인' });
    const guide = screen.getByRole('button', { name: '연결 방법 안내' });
    expect(back.compareDocumentPosition(providerTitle) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(providerTitle.compareDocumentPosition(connect) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(connect.compareDocumentPosition(guide) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Confluence로 변경' })).toBeNull();

    fireEvent.click(back);

    expect(screen.getByRole('button', { name: 'Notion 선택' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Confluence Cloud 선택' })).toBeTruthy();
    expect(screen.queryByLabelText('Notion 내부 연결 토큰')).toBeNull();
  });

  it('shows recovery guidance for unavailable settings storage', async () => {
    vi.stubGlobal('fetch', withHealthyBackend(vi.fn().mockResolvedValue(response({ version: null, status: 'STORAGE_UNAVAILABLE', active: null, draft: null, operation: null, canCreateMeeting: false }))));
    render(<MemoryRouter initialEntries={['/settings/document']}><App /></MemoryRouter>);
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByRole('heading', { name: '저장소 설정을 확인할 수 없습니다' })).toBeTruthy();
  });

  it('shows document connection status with clear settings navigation', async () => {
    vi.stubGlobal('fetch', withHealthyBackend(vi.fn().mockResolvedValue(response({ version: 5, status: 'READY', active: { provider: 'NOTION', connectionVersion: 2, rootUrl: 'https://notion.example' }, draft: null, operation: null, canCreateMeeting: true }))));
    render(<MemoryRouter initialEntries={['/settings/document']}><App /></MemoryRouter>);

    await screen.findByRole('heading', { name: '문서 연결' });

    expect(screen.getByRole('link', { name: '연결된 문서 열기' }).className).toContain('button');
    expect(screen.getByRole('link', { name: '설정으로 돌아가기' }).getAttribute('href')).toBe('/settings');
    expect(screen.queryByRole('link', { name: '참석자 관리' })).toBeNull();
  });

  it('CHANGE-ENTRY reopens provider selection from READY while preserving the active source', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response({ version: 5, status: 'READY', active: { provider: 'NOTION', connectionVersion: 2, rootUrl: 'https://notion.example' }, draft: { draftId: 'pending-draft', revision: 1, provider: 'CONFLUENCE', state: 'DRAFT' }, operation: null, canCreateMeeting: true }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/settings/document']}><App /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: '문서 연결' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Notion' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '문서 서비스 변경' }));
    expect(screen.getByRole('heading', { name: '문서 서비스 선택' })).toBeTruthy();
    expect(screen.getByText('현재 연결: Notion')).toBeTruthy();
    expect(screen.getByText(/연결 버전: 2/)).toBeTruthy();
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);
  });

  it('connects required input errors, clears the password after testing, and invalidates TESTED when fields change', async () => {
    let statusReads = 0;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      void init;
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) {
        statusReads += 1;
        return Promise.resolve(response({ version: statusReads === 1 ? 5 : 6, status: 'READY', active: { provider: 'NOTION', connectionVersion: 2, rootUrl: 'https://notion.example' }, draft: statusReads === 1 ? null : { draftId: 'd1', revision: 1, provider: 'NOTION', state: 'TESTED' }, operation: null, canCreateMeeting: true }));
      }
      if (path.endsWith('/drafts')) return Promise.resolve(response({ draftId: 'd1', revision: 1, state: 'DRAFT', version: 1 }, 201));
      if (path.endsWith('/test')) return Promise.resolve(response({ draftId: 'd1', revision: 1, testedAt: '2026-10-11T10:00:00Z', expiresAt: '2099-10-11T10:10:00Z', authenticated: true, parentAccessible: true, writeCapability: 'UNVERIFIED', version: 2 }));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);
    await screen.findByRole('heading', { name: '문서 서비스 연결' });
    fireEvent.click(screen.getByRole('button', { name: 'Notion 선택' }));
    fireEvent.click(screen.getByRole('button', { name: '연결 확인' }));
    expect(screen.getByLabelText('Notion 내부 연결 토큰').getAttribute('aria-invalid')).toBe('true');
    await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText('Notion 내부 연결 토큰')));
    fireEvent.change(screen.getByLabelText('Notion 내부 연결 토큰'), { target: { value: 'synthetic-secret' } });
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'parent-page-1' } });
    fireEvent.click(screen.getByRole('button', { name: '연결 확인' }));
    expect(await screen.findByRole('heading', { name: '생성될 구조' })).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('button', { name: '연결 확인 중…' })).toBeNull());
    expect((screen.getByLabelText('Notion 내부 연결 토큰') as HTMLInputElement).value).toBe('');
    expect(window.localStorage.length).toBe(1);
    const draftCall = fetchMock.mock.calls.find(([url, init]) => new URL(String(url)).pathname.endsWith('/drafts') && init?.method === 'POST');
    expect(JSON.parse(String(draftCall?.[1]?.body)).credentials.token).toBe('synthetic-secret');
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'another-parent' } });
    expect(screen.queryByRole('heading', { name: '생성될 구조' })).toBeNull();
  });

  it('shows the connected document provider and the current page while structure creation is running', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(response({ version: 3, status: 'CONFIGURING', active: null, draft: { draftId: 'd1', revision: 1, provider: 'NOTION', state: 'INITIALIZING' }, operation: { operationId: 'op-1', type: 'INITIALIZE', status: 'RUNNING' }, canCreateMeeting: false }));
      if (path.endsWith('/operations/op-1')) return Promise.resolve(response({ ...operation('op-1', 'RUNNING'), stage: 'STRUCTURE', currentStep: 'ROOT', completedSteps: [] }));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: 'Notion 문서를 생성하고 있습니다' })).toBeTruthy();
    expect(screen.getByText('Notion에 Meeting Automation 페이지를 생성하고 있습니다.')).toBeTruthy();
    expect(screen.getByRole('list', { name: '문서 페이지 생성 진행 상황' }).textContent).toContain('Meeting Automation');
    expect(screen.queryByText('STRUCTURE · RUNNING')).toBeNull();
  });

  it('keeps the active setup visible and offers a manual status retry when operation details are unavailable', async () => {
    let statusAvailable = false;
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(response({ version: 3, status: 'CONFIGURING', active: null, draft: { draftId: 'd1', revision: 1, provider: 'NOTION', state: 'INITIALIZING' }, operation: { operationId: 'op-1', type: 'INITIALIZE', status: 'RUNNING' }, canCreateMeeting: false }));
      if (path.endsWith('/operations/op-1')) return Promise.resolve(statusAvailable
        ? response({ ...operation('op-1', 'RUNNING'), stage: 'STRUCTURE', currentStep: 'ROOT', completedSteps: [] })
        : errorResponse('INTERNAL_ERROR', 503));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: 'Notion 문서를 생성하고 있습니다' })).toBeTruthy();
    expect((await screen.findByRole('alert')).textContent).toContain('진행 상세 정보를 불러오지 못했습니다');
    expect(screen.getByText('Notion 연결 작업 상태를 확인하고 있습니다.')).toBeTruthy();
    const retryButton = screen.getByRole('button', { name: '진행 상태 다시 불러오기' });
    statusAvailable = true;
    fireEvent.click(retryButton);

    expect(await screen.findByText('Notion에 Meeting Automation 페이지를 생성하고 있습니다.')).toBeTruthy();
    expect(screen.queryByText(/진행 상세 정보를 불러오지 못했습니다/)).toBeNull();
  });

  it('lets a user close an orphaned initialization when operation details cannot be loaded', async () => {
    let cancelled = false;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(response(cancelled
        ? { version: 4, status: 'UNCONFIGURED', active: null, draft: null, operation: { operationId: 'op-1', type: 'INITIALIZE', status: 'CANCELLED' }, canCreateMeeting: false }
        : { version: 3, status: 'CONFIGURING', active: null, draft: { draftId: 'd1', revision: 1, provider: 'NOTION', state: 'INITIALIZING' }, operation: { operationId: 'op-1', type: 'INITIALIZE', status: 'RUNNING' }, canCreateMeeting: false }));
      if (path.endsWith('/operations/op-1/cancel') && init?.method === 'POST') { cancelled = true; return Promise.resolve(response({ operationId: 'op-1', status: 'CANCELLED', version: 4 }, 202)); }
      if (path.endsWith('/operations/op-1')) return Promise.resolve(errorResponse('INTERNAL_ERROR', 503));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);

    const closeButton = await screen.findByRole('button', { name: '연결 작업 종료 후 다시 연결' });
    fireEvent.click(closeButton);

    expect(await screen.findByRole('button', { name: 'Notion 선택' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Confluence Cloud 선택' })).toBeTruthy();
    expect(await screen.findByText(/이미 생성된 페이지가 있을 수 있으니 Notion에서 확인/)).toBeTruthy();
    expect(fetchMock.mock.calls.some(([url, init]) => new URL(String(url)).pathname.endsWith('/operations/op-1/cancel') && init?.method === 'POST')).toBe(true);
  });

  it('lets a user end an interrupted initialization and return to provider selection', async () => {
    let cancelled = false;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(response({ version: cancelled ? 5 : 3, status: cancelled ? 'UNCONFIGURED' : 'ATTENTION_REQUIRED', active: null, draft: cancelled ? null : { draftId: 'd1', revision: 1, provider: 'NOTION', state: 'INITIALIZING' }, operation: { operationId: 'op-1', type: 'INITIALIZE', status: cancelled ? 'CANCELLED' : 'INTERRUPTED' }, canCreateMeeting: false }));
      if (path.endsWith('/operations/op-1/cancel') && init?.method === 'POST') { cancelled = true; return Promise.resolve(response({ operationId: 'op-1', status: 'CANCELLED', version: 4 }, 202)); }
      if (path.endsWith('/operations/op-1')) return Promise.resolve(response({ ...operation('op-1', cancelled ? 'CANCELLED' : 'INTERRUPTED'), stage: 'STRUCTURE', allowedActions: cancelled ? [] : ['RETRY', 'CANCEL'] }));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);

    fireEvent.click(await screen.findByRole('button', { name: '중단된 연결 작업 종료' }));

    expect(await screen.findByRole('button', { name: 'Notion 선택' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Confluence Cloud 선택' })).toBeTruthy();
    expect(screen.getByRole('status').textContent).toContain('일부 페이지가 생성되었는지 확인');
  });

  it('refuses initialization when TESTED belongs to a different draft revision', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, _init?: RequestInit) => {
      void _init;
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(response({ version: 2, status: 'CONFIGURING', active: null, draft: { draftId: 'd1', revision: 1, provider: 'NOTION', state: 'TESTED' }, operation: null, canCreateMeeting: false }));
      if (path.endsWith('/drafts')) return Promise.resolve(response({ draftId: 'd1', revision: 1, state: 'DRAFT', version: 1 }, 201));
      if (path.endsWith('/test')) return Promise.resolve(response({ draftId: 'd1', revision: 0, testedAt: '2026-10-11T10:00:00Z', expiresAt: '2099-10-11T10:10:00Z', authenticated: true, parentAccessible: true, writeCapability: 'UNVERIFIED', version: 2 }));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);
    await screen.findByLabelText('Notion 내부 연결 토큰');
    fireEvent.change(screen.getByLabelText('Notion 내부 연결 토큰'), { target: { value: 'synthetic-secret' } });
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'parent-1' } });
    fireEvent.click(screen.getByRole('button', { name: '연결 확인' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('button', { name: '연결 확인 중…' })).toBeNull());
    expect(fetchMock.mock.calls.some(([url]) => new URL(String(url)).pathname.endsWith('/initialize'))).toBe(false);
  });

  it('initializes a tested revision once and refreshes app config and roster after operation success', async () => {
    let statusReads = 0;
    let operationReads = 0;
    let initialized = false;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) {
        statusReads += 1;
        if (statusReads === 1) return Promise.resolve(response({ version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false }));
        if (initialized && operationReads >= 3) return Promise.resolve(response({ version: 4, status: 'READY', active: { provider: 'NOTION', connectionVersion: 1, rootUrl: 'https://notion.example', rootId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee' }, draft: null, operation: { operationId: 'op-1', type: 'INITIALIZE', status: 'SUCCEEDED' }, canCreateMeeting: true }));
        if (initialized) return Promise.resolve(response({ version: 3, status: 'CONFIGURING', active: null, draft: { draftId: 'd1', revision: 1, provider: 'NOTION', state: 'INITIALIZING' }, operation: { operationId: 'op-1', type: 'INITIALIZE', status: 'RUNNING' }, canCreateMeeting: false }));
        return Promise.resolve(response({ version: 2, status: 'CONFIGURING', active: null, draft: { draftId: 'd1', revision: 1, provider: 'NOTION', state: 'TESTED' }, operation: null, canCreateMeeting: false }));
      }
      if (path.endsWith('/drafts') && init?.method === 'POST') return Promise.resolve(response({ draftId: 'd1', revision: 1, state: 'DRAFT', version: 1 }, 201));
      if (path.endsWith('/test')) return Promise.resolve(response({ draftId: 'd1', revision: 1, testedAt: '2026-10-11T10:00:00Z', expiresAt: '2099-10-11T10:10:00Z', authenticated: true, parentAccessible: true, writeCapability: 'UNVERIFIED', version: 2 }));
      if (path.endsWith('/initialize') && init?.method === 'POST') { initialized = true; return Promise.resolve(response({ operationId: 'op-1', status: 'PENDING', version: 3 }, 202)); }
      if (path.endsWith('/operations/op-1')) {
        operationReads += 1;
        return Promise.resolve(response(operation('op-1', operationReads < 3 ? 'RUNNING' : 'SUCCEEDED')));
      }
      if (path.endsWith('/app-config')) return Promise.resolve(response({ company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' }, setup: { status: 'READY', required: false, canCreateMeeting: true, operationId: null } }));
      if (path.endsWith('/participants')) return Promise.resolve(response({ items: [] }));
      if (path.endsWith('/meetings')) return Promise.resolve(response({ items: [] }));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);
    await screen.findByRole('heading', { name: '문서 서비스 연결' });
    fireEvent.click(screen.getByRole('button', { name: 'Notion 선택' }));
    fireEvent.change(screen.getByLabelText('Notion 내부 연결 토큰'), { target: { value: 'synthetic-secret' } });
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'parent-1' } });
    fireEvent.click(screen.getByRole('button', { name: '연결 확인' }));
    expect(await screen.findByRole('heading', { name: '문서 연결 완료' }, { timeout: 5000 })).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('button', { name: '연결 확인 중…' })).toBeNull());
    expect(screen.getByText('연결 확인')).toBeTruthy();
    expect(screen.getByText('문서 생성')).toBeTruthy();
    expect(screen.getByText('생성 완료')).toBeTruthy();
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/participants'))).toBe(true));
    const initializeCalls = fetchMock.mock.calls.filter(([url, init]) => new URL(String(url)).pathname.endsWith('/initialize') && init?.method === 'POST');
    expect(initializeCalls).toHaveLength(1);
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/app-config'))).toBe(true);
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/participants'))).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '확인' }));
    expect(await screen.findByRole('heading', { name: '최근 회의' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /문서 서비스 연결 상태/ }));
    expect(await screen.findByRole('dialog', { name: '문서 연결 상태' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /연결된 문서 열기/ })).toBeNull();
    expect(screen.getByText('Meeting Automation')).toBeTruthy();
    expect(screen.getByText('Templates')).toBeTruthy();
    expect(screen.getByText('기본 회의록 템플릿')).toBeTruthy();
  });

  it('rereads API-023 and resumes the accepted public operation after the initialize response is lost', async () => {
    let statusReads = 0;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) {
        statusReads += 1;
        if (statusReads === 1) return Promise.resolve(response({ version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false }));
        const operationSummary = statusReads >= 4 ? { operationId: 'lost-op-9', type: 'INITIALIZE', status: 'RUNNING' } : null;
        return Promise.resolve(response({ version: statusReads >= 4 ? 3 : 2, status: 'CONFIGURING', active: null, draft: { draftId: 'd1', revision: 1, provider: 'NOTION', state: statusReads >= 4 ? 'INITIALIZING' : 'TESTED' }, operation: operationSummary, canCreateMeeting: false }));
      }
      if (path.endsWith('/drafts') && init?.method === 'POST') return Promise.resolve(response({ draftId: 'd1', revision: 1, state: 'DRAFT', version: 1 }, 201));
      if (path.endsWith('/test')) return Promise.resolve(response({ draftId: 'd1', revision: 1, testedAt: '2026-10-11T10:00:00Z', expiresAt: '2099-10-11T10:10:00Z', authenticated: true, parentAccessible: true, writeCapability: 'UNVERIFIED', version: 2 }));
      if (path.endsWith('/initialize') && init?.method === 'POST') return Promise.reject(new Error('connection closed after dispatch'));
      if (path.endsWith('/operations/lost-op-9')) return Promise.resolve(response(operation('lost-op-9', 'RUNNING')));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);
    await screen.findByRole('heading', { name: '문서 서비스 연결' });
    fireEvent.click(screen.getByRole('button', { name: 'Notion 선택' }));
    fireEvent.change(screen.getByLabelText('Notion 내부 연결 토큰'), { target: { value: 'synthetic-secret' } });
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'parent-1' } });
    fireEvent.click(screen.getByRole('button', { name: '연결 확인' }));
    expect(await screen.findByRole('heading', { name: 'Notion 문서를 생성하고 있습니다' })).toBeTruthy();
    expect(await screen.findByText(/다시 확인했습니다/)).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('button', { name: '연결 확인 중…' })).toBeNull());
    expect(fetchMock.mock.calls.some(([url]) => new URL(String(url)).pathname === '/api/v1/document-setup')).toBe(true);
    expect(fetchMock.mock.calls.some(([url]) => new URL(String(url)).pathname.endsWith('/operations/lost-op-9'))).toBe(true);
    expect(screen.queryByRole('button', { name: '확인' })).toBeNull();
    expect(screen.queryByLabelText('Notion 내부 연결 토큰')).toBeNull();
    const operationRead = fetchMock.mock.calls.find(([url]) => new URL(String(url)).pathname.endsWith('/operations/lost-op-9'));
    expect(String(operationRead?.[0]).includes('synthetic-secret')).toBe(false);
  });

  it('resumes the public operation after reload without restoring or exposing credentials', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(response({ version: 3, status: 'CONFIGURING', active: null, draft: { draftId: 'd1', revision: 1, provider: 'NOTION', state: 'INITIALIZING' }, operation: { operationId: 'public-op-7', type: 'INITIALIZE', status: 'RUNNING' }, canCreateMeeting: false }));
      if (path.endsWith('/operations/public-op-7')) return Promise.resolve(response(operation('public-op-7', 'RUNNING')));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Notion 문서를 생성하고 있습니다' })).toBeTruthy();
    expect(await screen.findByText('Notion에 문서 구조 페이지를 생성하고 있습니다.')).toBeTruthy();
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/operations/public-op-7')).length).toBeGreaterThanOrEqual(2));
    expect(screen.queryByLabelText('Notion 내부 연결 토큰')).toBeNull();
    expect(JSON.stringify(fetchMock.mock.calls).includes('synthetic-secret')).toBe(false);
    expect(window.localStorage.length).toBe(0);
  });

  it('resumes a retryable initialization failure through the operation retry API', async () => {
    let retried = false;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(response({ version: retried ? 4 : 3, status: retried ? 'CONFIGURING' : 'ATTENTION_REQUIRED', active: null, draft: { draftId: 'd1', revision: 1, provider: 'NOTION', state: 'INITIALIZING' }, operation: { operationId: 'failed-op-3', type: 'INITIALIZE', status: retried ? 'PENDING' : 'FAILED' }, canCreateMeeting: false }));
      if (path.endsWith('/operations/failed-op-3')) {
        return Promise.resolve(response({ ...operation('failed-op-3', retried ? 'RUNNING' : 'FAILED'), errorCode: retried ? undefined : 'DOCUMENT_FAILED', retryable: true, allowedActions: ['RETRY', 'CANCEL'], version: retried ? 4 : 3 }));
      }
      if (path.endsWith('/operations/failed-op-3/retry') && init?.method === 'POST') return Promise.resolve(response({ operationId: 'failed-op-3', status: 'PENDING', version: 4 }, 202));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);

    expect(await screen.findByText('DOCUMENT_FAILED')).toBeTruthy();
    expect(screen.getByRole('heading', { name: '문서 구조 준비 실패' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '작업 재개' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '중단된 연결 작업 종료' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '작업 재개' }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => new URL(String(url)).pathname.endsWith('/operations/failed-op-3/retry'))).toBe(true));
    expect(fetchMock.mock.calls.some(([url]) => new URL(String(url)).pathname.endsWith('/initialize'))).toBe(false);
    retried = true;
  });

  it('shows only API-027 allowed actions for a terminal reconciliation operation', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(response({ version: 5, status: 'ATTENTION_REQUIRED', active: null, draft: { draftId: 'd1', revision: 1, provider: 'NOTION', state: 'INITIALIZING' }, operation: { operationId: 'review-op-4', type: 'INITIALIZE', status: 'RECONCILIATION_REQUIRED' }, canCreateMeeting: false }));
      if (path.endsWith('/operations/review-op-4')) return Promise.resolve(response({ ...operation('review-op-4', 'RECONCILIATION_REQUIRED'), errorCode: 'PROVIDER_STATE_UNCERTAIN', retryable: false, allowedActions: ['REVIEW_PROVIDER', 'CANCEL'], version: 5 }));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);

    expect(await screen.findByText('PROVIDER_STATE_UNCERTAIN')).toBeTruthy();
    expect(screen.getByText(/문서 서비스에서 생성된 페이지 구조를 확인/)).toBeTruthy();
    expect(screen.getByRole('button', { name: '중단된 연결 작업 종료' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
    expect(screen.queryByRole('button', { name: '확인' })).toBeNull();
  });

  it('rereads the latest setup revision after a 412 conflict before retrying', async () => {
    let statusReads = 0;
    let draftPosts = 0;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) {
        statusReads += 1;
        return Promise.resolve(response({ version: statusReads === 1 ? 3 : 5, status: 'READY', active: { provider: 'NOTION', connectionVersion: 2, rootUrl: 'https://notion.example' }, draft: null, operation: null, canCreateMeeting: true }));
      }
      if (path.endsWith('/drafts') && init?.method === 'POST') {
        draftPosts += 1;
        return Promise.resolve(draftPosts === 1 ? errorResponse('DOCUMENT_SETTINGS_VERSION_CONFLICT', 412) : response({ draftId: 'd2', revision: 1, state: 'DRAFT', version: 6 }, 201));
      }
      if (path.endsWith('/test')) return Promise.resolve(response({ draftId: 'd2', revision: 1, testedAt: '2026-10-11T10:00:00Z', expiresAt: '2099-10-11T10:10:00Z', authenticated: true, parentAccessible: true, writeCapability: 'UNVERIFIED', version: 7 }));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);
    await screen.findByRole('heading', { name: '문서 서비스 연결' });
    fireEvent.click(screen.getByRole('button', { name: 'Notion 선택' }));
    fireEvent.change(screen.getByLabelText('Notion 내부 연결 토큰'), { target: { value: 'synthetic-secret' } });
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'parent-1' } });
    fireEvent.click(screen.getByRole('button', { name: '연결 확인' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/최신 상태를 읽었습니다/);
    expect(screen.getByRole('alert').textContent).toMatch(/설정 버전: 5/);
    await waitFor(() => expect(screen.queryByRole('button', { name: '연결 확인 중…' })).toBeNull());
    fireEvent.change(screen.getByLabelText('Notion 내부 연결 토큰'), { target: { value: 'synthetic-secret-2' } });
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'parent-2' } });
    fireEvent.click(screen.getByRole('button', { name: '연결 확인' }));
    expect(await screen.findByRole('heading', { name: '생성될 구조' })).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('button', { name: '연결 확인 중…' })).toBeNull());
    const draftCalls = fetchMock.mock.calls.filter(([url, init]) => new URL(String(url)).pathname.endsWith('/drafts') && init?.method === 'POST');
    expect(draftCalls[0][1]?.headers).toMatchObject({ 'If-Match': '"3"' });
    expect(draftCalls[1][1]?.headers).toMatchObject({ 'If-Match': '"5"' });
  });
});

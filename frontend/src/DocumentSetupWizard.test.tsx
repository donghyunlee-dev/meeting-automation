import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
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

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('document setup wizard', () => {
  it('routes an unconfigured meeting entry to setup without requesting meeting data', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response({ version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false })));
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/new']}><App /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: '문서 서비스 연결' }, { timeout: 5000 })).toBeTruthy();
    expect(fetchMock.mock.calls.map(([url]) => new URL(String(url)).pathname)).toEqual(['/api/v1/document-setup', '/api/v1/document-setup']);
  });

  it('gates unconfigured participant settings and redirects to the first-run wizard', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response({ version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false })));
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/settings/participants']}><App /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: '문서 서비스 연결' })).toBeTruthy();
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/participants'))).toBe(false);
  });

  it('clears provider-specific fields when the provider changes', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response({ version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false }));
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);
    await screen.findByRole('heading', { name: '문서 서비스 연결' }, { timeout: 5000 });
    fireEvent.click(screen.getByRole('button', { name: 'Notion 선택' }));
    fireEvent.change(screen.getByLabelText('Notion 내부 연결 토큰'), { target: { value: 'secret-token' } });
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'notion-parent' } });
    expect(screen.getByText(/페이지 생성·갱신 권한/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Confluence Cloud 선택' }));
    expect(screen.queryByLabelText('Notion 내부 연결 토큰')).toBeNull();
    expect(screen.getByLabelText('Atlassian API token')).toBeTruthy();
    expect((screen.getByLabelText('Atlassian API token') as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('Atlassian 계정 이메일') as HTMLInputElement).value).toBe('');
    expect(screen.getByText(/공간 읽기·페이지 생성·갱신 권한/)).toBeTruthy();
  });

  it('shows recovery guidance for unavailable settings storage', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ version: null, status: 'STORAGE_UNAVAILABLE', active: null, draft: null, operation: null, canCreateMeeting: false })));
    render(<MemoryRouter initialEntries={['/settings/document']}><App /></MemoryRouter>);
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByRole('heading', { name: '저장소 설정을 확인할 수 없습니다' })).toBeTruthy();
  });

  it('connects required input errors, clears the password after testing, and invalidates TESTED when fields change', async () => {
    let statusReads = 0;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      void init;
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) {
        statusReads += 1;
        return Promise.resolve(response({ version: statusReads === 1 ? 0 : 2, status: 'CONFIGURING', active: null, draft: statusReads === 1 ? null : { draftId: 'd1', revision: 1, provider: 'NOTION', state: 'TESTED' }, operation: null, canCreateMeeting: false }));
      }
      if (path.endsWith('/drafts')) return Promise.resolve(response({ draftId: 'd1', revision: 1, state: 'DRAFT', version: 1 }, 201));
      if (path.endsWith('/test')) return Promise.resolve(response({ draftId: 'd1', revision: 1, testedAt: '2026-10-11T10:00:00Z', expiresAt: '2099-10-11T10:10:00Z', authenticated: true, parentAccessible: true, writeCapability: 'UNVERIFIED', version: 2 }));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);
    await screen.findByRole('heading', { name: '문서 서비스 연결' });
    fireEvent.click(screen.getByRole('button', { name: 'Notion 선택' }));
    fireEvent.click(screen.getByRole('button', { name: '읽기 전용 연결 테스트' }));
    expect(screen.getByLabelText('Notion 내부 연결 토큰').getAttribute('aria-invalid')).toBe('true');
    await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText('Notion 내부 연결 토큰')));
    fireEvent.change(screen.getByLabelText('Notion 내부 연결 토큰'), { target: { value: 'synthetic-secret' } });
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'parent-page-1' } });
    fireEvent.click(screen.getByRole('button', { name: '읽기 전용 연결 테스트' }));
    expect(await screen.findByRole('heading', { name: '생성될 구조' })).toBeTruthy();
    expect((screen.getByLabelText('Notion 내부 연결 토큰') as HTMLInputElement).value).toBe('');
    expect(window.localStorage.length).toBe(0);
    const draftCall = fetchMock.mock.calls.find(([url, init]) => new URL(String(url)).pathname.endsWith('/drafts') && init?.method === 'POST');
    expect(JSON.parse(String(draftCall?.[1]?.body)).credentials.token).toBe('synthetic-secret');
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'another-parent' } });
    expect(screen.queryByRole('heading', { name: '생성될 구조' })).toBeNull();
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
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);
    await screen.findByRole('heading', { name: 'Notion 연결 가이드' });
    fireEvent.change(screen.getByLabelText('Notion 내부 연결 토큰'), { target: { value: 'synthetic-secret' } });
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'parent-1' } });
    fireEvent.click(screen.getByRole('button', { name: '읽기 전용 연결 테스트' }));
    expect(await screen.findByRole('heading', { name: '생성될 구조' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '구조 생성 및 연결 완료' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
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
        if (initialized && operationReads >= 3) return Promise.resolve(response({ version: 4, status: 'READY', active: { provider: 'NOTION', connectionVersion: 1, rootUrl: 'https://notion.example' }, draft: null, operation: { operationId: 'op-1', type: 'INITIALIZE', status: 'SUCCEEDED' }, canCreateMeeting: true }));
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
      if (path.endsWith('/app-config')) return Promise.resolve(response({ company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' } }));
      if (path.endsWith('/participants')) return Promise.resolve(response({ items: [] }));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);
    await screen.findByRole('heading', { name: '문서 서비스 연결' });
    fireEvent.click(screen.getByRole('button', { name: 'Notion 선택' }));
    fireEvent.change(screen.getByLabelText('Notion 내부 연결 토큰'), { target: { value: 'synthetic-secret' } });
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'parent-1' } });
    fireEvent.click(screen.getByRole('button', { name: '읽기 전용 연결 테스트' }));
    await screen.findByRole('heading', { name: '생성될 구조' });
    const finishButton = screen.getByRole('button', { name: '구조 생성 및 연결 완료' });
    fireEvent.click(finishButton);
    fireEvent.click(finishButton);
    expect(await screen.findByText('문서 서비스 연결이 준비되었습니다.')).toBeTruthy();
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/participants'))).toBe(true));
    const initializeCalls = fetchMock.mock.calls.filter(([url, init]) => new URL(String(url)).pathname.endsWith('/initialize') && init?.method === 'POST');
    expect(initializeCalls).toHaveLength(1);
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/app-config'))).toBe(true);
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/participants'))).toBe(true);
  });

  it('resumes the public operation after reload without restoring or exposing credentials', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(response({ version: 3, status: 'CONFIGURING', active: null, draft: { draftId: 'd1', revision: 1, provider: 'NOTION', state: 'INITIALIZING' }, operation: { operationId: 'public-op-7', type: 'INITIALIZE', status: 'RUNNING' }, canCreateMeeting: false }));
      if (path.endsWith('/operations/public-op-7')) return Promise.resolve(response(operation('public-op-7', 'RUNNING')));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: '문서 구조 준비 상태' })).toBeTruthy();
    expect(await screen.findByText(/RUNNING/)).toBeTruthy();
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/operations/public-op-7')).length).toBeGreaterThanOrEqual(2));
    expect(screen.queryByLabelText('Notion 내부 연결 토큰')).toBeNull();
    expect(JSON.stringify(fetchMock.mock.calls).includes('synthetic-secret')).toBe(false);
    expect(window.localStorage.length).toBe(0);
  });

  it('rereads the latest setup revision after a 412 conflict before retrying', async () => {
    let statusReads = 0;
    let draftPosts = 0;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) {
        statusReads += 1;
        return Promise.resolve(response({ version: statusReads === 1 ? 3 : 5, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false }));
      }
      if (path.endsWith('/drafts') && init?.method === 'POST') {
        draftPosts += 1;
        return Promise.resolve(draftPosts === 1 ? errorResponse('DOCUMENT_SETTINGS_VERSION_CONFLICT', 412) : response({ draftId: 'd2', revision: 1, state: 'DRAFT', version: 6 }, 201));
      }
      if (path.endsWith('/test')) return Promise.resolve(response({ draftId: 'd2', revision: 1, testedAt: '2026-10-11T10:00:00Z', expiresAt: '2099-10-11T10:10:00Z', authenticated: true, parentAccessible: true, writeCapability: 'UNVERIFIED', version: 7 }));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);
    await screen.findByRole('heading', { name: '문서 서비스 연결' });
    fireEvent.click(screen.getByRole('button', { name: 'Notion 선택' }));
    fireEvent.change(screen.getByLabelText('Notion 내부 연결 토큰'), { target: { value: 'synthetic-secret' } });
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'parent-1' } });
    fireEvent.click(screen.getByRole('button', { name: '읽기 전용 연결 테스트' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/최신 상태를 읽었습니다/);
    fireEvent.change(screen.getByLabelText('Notion 내부 연결 토큰'), { target: { value: 'synthetic-secret-2' } });
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'parent-2' } });
    fireEvent.click(screen.getByRole('button', { name: '읽기 전용 연결 테스트' }));
    await screen.findByRole('heading', { name: '생성될 구조' });
    const draftCalls = fetchMock.mock.calls.filter(([url, init]) => new URL(String(url)).pathname.endsWith('/drafts') && init?.method === 'POST');
    expect(draftCalls[0][1]?.headers).toMatchObject({ 'If-Match': '"3"' });
    expect(draftCalls[1][1]?.headers).toMatchObject({ 'If-Match': '"5"' });
  });
});

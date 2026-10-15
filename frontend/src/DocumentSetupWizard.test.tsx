import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { App } from './App';
import { DocumentSetupWizard } from './DocumentSetupWizard';

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

function RouteState() {
  const location = useLocation();
  return <output data-testid="route-state">{JSON.stringify(location.state)}</output>;
}

describe('document setup wizard', () => {
  it('returns from Settings to the saved Share route state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ version: 5, status: 'READY', active: { provider: 'NOTION', connectionVersion: 2, rootUrl: 'https://notion.example' }, draft: null, operation: null, canCreateMeeting: true })));
    const returnState = { emailRecipientParticipantIds: ['participant-2'], notificationEnabled: false };
    render(<MemoryRouter initialEntries={[{ pathname: '/settings', state: { returnTo: '/meetings/session-1/share', returnState } }]}>
      <Routes>
        <Route path="/settings" element={<><DocumentSetupWizard settings /><RouteState /></>} />
        <Route path="/meetings/:sessionId/share" element={<RouteState />} />
      </Routes>
    </MemoryRouter>);

    fireEvent.click(await screen.findByRole('link', { name: 'Back to sharing' }));

    expect(screen.getByTestId('route-state').textContent).toBe(JSON.stringify(returnState));
  });

  it('routes an unconfigured meeting entry to setup without requesting meeting data', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response({ version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false })));
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/new']}><App /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: '문서 서비스 연결' }, { timeout: 5000 })).toBeTruthy();
    expect(fetchMock.mock.calls.map(([url]) => new URL(String(url)).pathname)).toEqual(['/api/v1/document-setup', '/api/v1/document-setup']);
  });

  it('gates the home route during first run and removes the loop-back action', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response({ version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false })));
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/']}><App /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: '문서 서비스 연결' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '나중에 하기' })).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Meeting Automation' })).toBeNull();
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
    expect(screen.getByText(/페이지 읽기와 콘텐츠 추가·수정 권한/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Notion 연결 만들기' }).getAttribute('href')).toBe('https://www.notion.com/help/create-integrations-with-the-notion-api');
    fireEvent.click(screen.getByRole('button', { name: 'Confluence Cloud로 변경' }));
    expect(screen.queryByLabelText('Notion 내부 연결 토큰')).toBeNull();
    expect(screen.getByLabelText('Atlassian API token')).toBeTruthy();
    expect((screen.getByLabelText('Atlassian API token') as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('Atlassian 계정 이메일') as HTMLInputElement).value).toBe('');
    expect(screen.getByText(/Space ID와 운영 계정의 페이지 읽기·생성·갱신 권한/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Atlassian API token 만들기' }).getAttribute('href')).toBe('https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account');
  });

  it('shows recovery guidance for unavailable settings storage', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ version: null, status: 'STORAGE_UNAVAILABLE', active: null, draft: null, operation: null, canCreateMeeting: false })));
    render(<MemoryRouter initialEntries={['/settings/document']}><App /></MemoryRouter>);
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByRole('heading', { name: '저장소 설정을 확인할 수 없습니다' })).toBeTruthy();
  });

  it('CHANGE-ENTRY reopens provider selection from READY while preserving the active source', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response({ version: 5, status: 'READY', active: { provider: 'NOTION', connectionVersion: 2, rootUrl: 'https://notion.example' }, draft: { draftId: 'pending-draft', revision: 1, provider: 'CONFLUENCE', state: 'DRAFT' }, operation: null, canCreateMeeting: true }));
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/settings/document']}><App /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: '설정' })).toBeTruthy();
    expect(screen.getByText('현재 연결: Notion')).toBeTruthy();
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
    expect(await screen.findByText('문서 서비스 연결이 준비되었습니다.', {}, { timeout: 5000 })).toBeTruthy();
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/participants'))).toBe(true));
    const initializeCalls = fetchMock.mock.calls.filter(([url, init]) => new URL(String(url)).pathname.endsWith('/initialize') && init?.method === 'POST');
    expect(initializeCalls).toHaveLength(1);
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/app-config'))).toBe(true);
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/participants'))).toBe(true);
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
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);
    await screen.findByRole('heading', { name: '문서 서비스 연결' });
    fireEvent.click(screen.getByRole('button', { name: 'Notion 선택' }));
    fireEvent.change(screen.getByLabelText('Notion 내부 연결 토큰'), { target: { value: 'synthetic-secret' } });
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'parent-1' } });
    fireEvent.click(screen.getByRole('button', { name: '읽기 전용 연결 테스트' }));
    await screen.findByRole('heading', { name: '생성될 구조' });
    fireEvent.click(screen.getByRole('button', { name: '구조 생성 및 연결 완료' }));

    expect(await screen.findByRole('heading', { name: '문서 구조 준비 상태' })).toBeTruthy();
    expect(await screen.findByText(/다시 확인했습니다/)).toBeTruthy();
    expect(fetchMock.mock.calls.some(([url]) => new URL(String(url)).pathname === '/api/v1/document-setup')).toBe(true);
    expect(fetchMock.mock.calls.some(([url]) => new URL(String(url)).pathname.endsWith('/operations/lost-op-9'))).toBe(true);
    expect(screen.queryByRole('button', { name: '구조 생성 및 연결 완료' })).toBeNull();
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
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: '문서 구조 준비 상태' })).toBeTruthy();
    expect(await screen.findByText(/RUNNING/)).toBeTruthy();
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/operations/public-op-7')).length).toBeGreaterThanOrEqual(2));
    expect(screen.queryByLabelText('Notion 내부 연결 토큰')).toBeNull();
    expect(JSON.stringify(fetchMock.mock.calls).includes('synthetic-secret')).toBe(false);
    expect(window.localStorage.length).toBe(0);
  });

  it('shows terminal failure details and recovery guidance without calling unsupported retry APIs', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(response({ version: 3, status: 'ATTENTION_REQUIRED', active: null, draft: { draftId: 'd1', revision: 1, provider: 'NOTION', state: 'INITIALIZING' }, operation: { operationId: 'failed-op-3', type: 'INITIALIZE', status: 'FAILED' }, canCreateMeeting: false }));
      if (path.endsWith('/operations/failed-op-3')) {
        return Promise.resolve(response({ ...operation('failed-op-3', 'FAILED'), errorCode: 'DOCUMENT_FAILED', retryable: true, allowedActions: ['RETRY', 'CANCEL'], version: 3 }));
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);

    expect(await screen.findByText('DOCUMENT_FAILED')).toBeTruthy();
    expect(screen.getByText(/실패/)).toBeTruthy();
    expect(screen.getByText('재시도 허용')).toBeTruthy();
    expect(screen.getByText('작업 취소')).toBeTruthy();
    expect(screen.getByText(/작업 ID와 오류 코드를 관리자에게 전달/)).toBeTruthy();
    expect(screen.getByRole('heading', { name: /서버가 허용한 작업.*이 화면에서 실행되지 않음/ })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '구조 생성 및 연결 완료' })).toBeNull();
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
    expect(fetchMock.mock.calls.some(([url]) => new URL(String(url)).pathname.endsWith('/initialize'))).toBe(false);
    expect(fetchMock.mock.calls.some(([url]) => new URL(String(url)).pathname.includes('/retry'))).toBe(false);
  });

  it('shows only API-027 allowed actions for a terminal reconciliation operation', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(response({ version: 5, status: 'ATTENTION_REQUIRED', active: null, draft: { draftId: 'd1', revision: 1, provider: 'NOTION', state: 'INITIALIZING' }, operation: { operationId: 'review-op-4', type: 'INITIALIZE', status: 'RECONCILIATION_REQUIRED' }, canCreateMeeting: false }));
      if (path.endsWith('/operations/review-op-4')) return Promise.resolve(response({ ...operation('review-op-4', 'RECONCILIATION_REQUIRED'), errorCode: 'PROVIDER_STATE_UNCERTAIN', retryable: false, allowedActions: ['REVIEW_PROVIDER', 'CANCEL'], version: 5 }));
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/setup/document']}><App /></MemoryRouter>);

    expect(await screen.findByText('PROVIDER_STATE_UNCERTAIN')).toBeTruthy();
    expect(screen.getByText('Provider 확인')).toBeTruthy();
    expect(screen.getByText('작업 취소')).toBeTruthy();
    expect(screen.getByRole('heading', { name: /서버가 허용한 작업.*이 화면에서 실행되지 않음/ })).toBeTruthy();
    expect(screen.getByText(/Provider에서 생성된/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
    expect(screen.queryByRole('button', { name: '구조 생성 및 연결 완료' })).toBeNull();
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
    expect(screen.getByRole('alert').textContent).toMatch(/설정 버전: 5/);
    fireEvent.change(screen.getByLabelText('Notion 내부 연결 토큰'), { target: { value: 'synthetic-secret-2' } });
    fireEvent.change(screen.getByLabelText('부모 페이지 URL 또는 ID'), { target: { value: 'parent-2' } });
    fireEvent.click(screen.getByRole('button', { name: '읽기 전용 연결 테스트' }));
    await screen.findByRole('heading', { name: '생성될 구조' });
    const draftCalls = fetchMock.mock.calls.filter(([url, init]) => new URL(String(url)).pathname.endsWith('/drafts') && init?.method === 'POST');
    expect(draftCalls[0][1]?.headers).toMatchObject({ 'If-Match': '"3"' });
    expect(draftCalls[1][1]?.headers).toMatchObject({ 'If-Match': '"5"' });
  });
});

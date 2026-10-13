import 'fake-indexeddb/auto';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { StrictMode, useLayoutEffect, useRef } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router';
import { App } from './App';
import type { MediaRecorderAdapter, RecorderLike } from './recordingController';
import { ChunkRepository } from './chunkRepository';
// @ts-expect-error Node's Blob exercises browser-style IndexedDB structured cloning in jsdom.
import { Blob as NodeBlob } from 'node:buffer';

const participants = [
  { id: 'p1', name: 'Ada Lovelace', email: 'ada@example.com' },
  { id: 'p2', name: 'Grace Hopper', email: 'grace@navy.example' },
];

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function readySetupResponse() {
  return response({ data: { version: 1, status: 'READY', active: { provider: 'NOTION', connectionVersion: 1, rootUrl: 'https://notion.example' }, draft: null, operation: null, canCreateMeeting: true } });
}

function installFetchMock(fetchMock: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>) {
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => String(input).endsWith('/document-setup')
    ? Promise.resolve(readySetupResponse())
    : fetchMock(input, init)));
}

function deleteChunkDatabase(): Promise<void> {
  return new Promise((resolve, reject) => {
    const deletion = indexedDB.deleteDatabase('meeting-automation-chunks');
    deletion.onsuccess = () => resolve();
    deletion.onerror = () => reject(deletion.error);
    deletion.onblocked = () => reject(new Error('Chunk test database is still open'));
  });
}

function mockList(result: Response | Promise<Response>) {
  const fetchMock = vi.fn().mockResolvedValue(result);
  installFetchMock(fetchMock);
  return fetchMock;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

function DestinationFixture() {
  const location = useLocation();
  return <p>New Meeting fixture: {location.pathname}</p>;
}

function NavigateToMismatchedRecordingSession() {
  const navigate = useNavigate();
  return <button type="button" onClick={() => navigate('/meetings/other-session/recording')}>Open another session</button>;
}

function StartRecordingDuringLayout() {
  const clicked = useRef(false);
  useLayoutEffect(() => {
    if (clicked.current) return;
    clicked.current = true;
    document.querySelector<HTMLButtonElement>('.recording-primary-action')?.click();
  }, []);
  return null;
}

function renderHomeWithDestinationFixture() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/meetings/new" element={<DestinationFixture />} />
        <Route path="*" element={<App />} />
      </Routes>
    </MemoryRouter>,
  );
}

function renderParticipantsApp() {
  return render(<MemoryRouter initialEntries={['/settings/participants']}><App /></MemoryRouter>);
}

describe('Participants settings', () => {
  it('creates a session with the loaded connection version and navigates with its runtime context', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(readySetupResponse());
      if (path.endsWith('/app-config')) return Promise.resolve(response({ data: {
        company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' },
        document: { provider: 'NOTION', configured: true, connectionVersion: 7, rootUrl: 'https://notion.example' },
        setup: { status: 'READY', required: false, canCreateMeeting: true, operationId: null },
      } }));
      if (path.endsWith('/templates')) return Promise.resolve(response({ data: { items: [{ id: 'default.md', name: 'Default', version: '1.0.0' }] } }));
      if (path.endsWith('/participants')) return Promise.resolve(response({ data: { items: participants } }));
      if (path.endsWith('/meeting-sessions')) return Promise.resolve(response({ data: {
        sessionId: 'session-123', version: 1, status: 'CREATED',
        uploadPolicy: { chunkDurationSeconds: 30, maxChunkBytes: 1000000, acceptedMimeTypes: ['audio/webm'] },
      } }, 201));
      return Promise.reject(new Error(`Unexpected request: ${path} ${init?.method ?? 'GET'}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/new']}><App /></MemoryRouter>);

    await screen.findByText('Asia/Seoul');
    fireEvent.change(screen.getByLabelText('회의 제목'), { target: { value: 'Planning' } });
    fireEvent.click(screen.getByRole('button', { name: '참석자 추가' }));
    fireEvent.click(within(screen.getByRole('group', { name: '참석자 선택' })).getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' }));
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));

    expect(await screen.findByRole('heading', { name: 'Planning' })).toBeTruthy();
    const post = fetchMock.mock.calls.find(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions'));
    expect(post).toBeTruthy();
    const request = post?.[1];
    const body = JSON.parse(String(request?.body));
    expect(body).toMatchObject({ title: 'Planning', templateId: 'default.md', participantIds: ['p1'], timezone: 'Asia/Seoul', documentConnectionVersion: 7 });
    expect(typeof body.recoveryKey).toBe('string');
    expect(request?.headers).toHaveProperty('Idempotency-Key');
    expect(String(post?.[0])).not.toMatch(/recoveryKey|idempotency/i);
  });

  it('reuses one immutable attempt for an uncertain response and blocks duplicate clicks', async () => {
    let rejectFirst!: (error: Error) => void;
    let resolveSecond!: (value: Response) => void;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(readySetupResponse());
      if (path.endsWith('/app-config')) return Promise.resolve(response({ data: {
        company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 7, rootUrl: 'https://notion.example' }, setup: { status: 'READY', required: false, canCreateMeeting: true, operationId: null },
      } }));
      if (path.endsWith('/templates')) return Promise.resolve(response({ data: { items: [{ id: 'default.md', name: 'Default', version: '1.0.0' }] } }));
      if (path.endsWith('/participants')) return Promise.resolve(response({ data: { items: participants } }));
      if (path.endsWith('/meeting-sessions')) return fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions')).length === 1
        ? new Promise<Response>((_resolve, reject) => { rejectFirst = reject; })
        : new Promise<Response>((resolve) => { resolveSecond = resolve; });
      return Promise.reject(new Error(`Unexpected request: ${path} ${init?.method ?? 'GET'}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/new']}><App /></MemoryRouter>);
    await screen.findByText('Asia/Seoul');
    fireEvent.change(screen.getByLabelText('회의 제목'), { target: { value: 'Retry safely' } });
    fireEvent.click(screen.getByRole('button', { name: '참석자 추가' }));
    fireEvent.click(within(screen.getByRole('group', { name: '참석자 선택' })).getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' }));
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions'))).toHaveLength(1));
    expect(screen.getByRole('button', { name: '회의 시작 중…' })).toHaveProperty('disabled', true);
    fireEvent.click(screen.getByRole('button', { name: '회의 시작 중…' }));
    expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions'))).toHaveLength(1);
    rejectFirst(new Error('connection dropped before response'));
    expect((await screen.findByRole('alert')).textContent).toContain('회의를 시작하지 못했습니다.');
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));
    await waitFor(() => expect(resolveSecond).toBeTypeOf('function'));
    const posts = fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions'));
    const firstBody = JSON.parse(String(posts[0][1]?.body));
    const retryBody = JSON.parse(String(posts[1][1]?.body));
    expect(retryBody).toEqual(firstBody);
    expect((posts[1][1]?.headers as Record<string, string>)['Idempotency-Key']).toBe((posts[0][1]?.headers as Record<string, string>)['Idempotency-Key']);
    resolveSecond(response({ data: { sessionId: 'session-retry', version: 1, status: 'CREATED', uploadPolicy: { chunkDurationSeconds: 30, maxChunkBytes: 1000000, acceptedMimeTypes: ['audio/webm'] } } }, 201));
    expect(await screen.findByRole('heading', { name: 'Retry safely' })).toBeTruthy();
  });

  it('treats a server 502 as uncertain and retries the identical attempt on the same READY connection', async () => {
    let configReads = 0;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(readySetupResponse());
      if (path.endsWith('/app-config')) {
        configReads += 1;
        return Promise.resolve(response({ data: {
          company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 7, rootUrl: 'https://notion.example' }, setup: { status: 'READY', required: false, canCreateMeeting: true, operationId: null },
        } }));
      }
      if (path.endsWith('/templates')) return Promise.resolve(response({ data: { items: [{ id: 'default.md', name: 'Default', version: '1.0.0' }] } }));
      if (path.endsWith('/participants')) return Promise.resolve(response({ data: { items: participants } }));
      if (path.endsWith('/meeting-sessions')) {
        const count = fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions')).length;
        return Promise.resolve(count === 1
          ? response({ error: { code: 'PARTICIPANT_LIST_FAILED' } }, 502)
          : response({ data: { sessionId: 'session-after-502', version: 1, status: 'CREATED', uploadPolicy: { chunkDurationSeconds: 30, maxChunkBytes: 1000000, acceptedMimeTypes: ['audio/webm'] } } }, 201));
      }
      return Promise.reject(new Error(`Unexpected request: ${path} ${init?.method ?? 'GET'}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/new']}><App /></MemoryRouter>);
    await screen.findByText('Asia/Seoul');
    fireEvent.change(screen.getByLabelText('회의 제목'), { target: { value: 'Retry after gateway response' } });
    fireEvent.click(screen.getByRole('button', { name: '참석자 추가' }));
    fireEvent.click(within(screen.getByRole('group', { name: '참석자 선택' })).getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' }));
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));
    expect((await screen.findByRole('alert')).textContent).toContain('요청을 완료하지 못했습니다.');

    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));
    expect(await screen.findByRole('heading', { name: 'Retry after gateway response' })).toBeTruthy();
    expect(configReads).toBe(2);
    const posts = fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions'));
    expect(JSON.parse(String(posts[1][1]?.body))).toEqual(JSON.parse(String(posts[0][1]?.body)));
    expect((posts[1][1]?.headers as Record<string, string>)['Idempotency-Key']).toBe((posts[0][1]?.headers as Record<string, string>)['Idempotency-Key']);
  });

  it('discards an uncertain attempt when its preflight sees a newer connection version', async () => {
    let configReads = 0;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(readySetupResponse());
      if (path.endsWith('/app-config')) {
        configReads += 1;
        const connectionVersion = configReads === 1 ? 7 : 8;
        return Promise.resolve(response({ data: {
          company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion, rootUrl: 'https://notion.example' }, setup: { status: 'READY', required: false, canCreateMeeting: true, operationId: null },
        } }));
      }
      if (path.endsWith('/templates')) return Promise.resolve(response({ data: { items: [{ id: 'default.md', name: 'Default', version: '1.0.0' }] } }));
      if (path.endsWith('/participants')) return Promise.resolve(response({ data: { items: participants } }));
      if (path.endsWith('/meeting-sessions')) {
        const count = fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions')).length;
        return Promise.resolve(count === 1
          ? response({ error: { code: 'INTERNAL_ERROR' } }, 500)
          : response({ data: { sessionId: 'session-version-8', version: 1, status: 'CREATED', uploadPolicy: { chunkDurationSeconds: 30, maxChunkBytes: 1000000, acceptedMimeTypes: ['audio/webm'] } } }, 201));
      }
      return Promise.reject(new Error(`Unexpected request: ${path} ${init?.method ?? 'GET'}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/new']}><App /></MemoryRouter>);
    await screen.findByText('Asia/Seoul');
    fireEvent.change(screen.getByLabelText('회의 제목'), { target: { value: 'Connection changes during retry' } });
    fireEvent.click(screen.getByRole('button', { name: '참석자 추가' }));
    fireEvent.click(within(screen.getByRole('group', { name: '참석자 선택' })).getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' }));
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));
    await screen.findByRole('alert');
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));

    expect(await screen.findByText(/문서 연결이 바뀌었습니다/)).toBeTruthy();
    expect(configReads).toBe(2);
    expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions'))).toHaveLength(1);
    expect(screen.queryByRole('list', { name: '선택한 참석자' })).toBeNull();
    fireEvent.click(within(screen.getByRole('group', { name: '참석자 선택' })).getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' }));
    fireEvent.click(screen.getByRole('button', { name: '참석자 선택 닫기' }));
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));
    expect(await screen.findByRole('heading', { name: 'Connection changes during retry' })).toBeTruthy();
    const posts = fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions'));
    const firstBody = JSON.parse(String(posts[0][1]?.body));
    const secondBody = JSON.parse(String(posts[1][1]?.body));
    expect(firstBody.documentConnectionVersion).toBe(7);
    expect(secondBody.documentConnectionVersion).toBe(8);
    expect(secondBody.recoveryKey).not.toBe(firstBody.recoveryKey);
    expect((posts[1][1]?.headers as Record<string, string>)['Idempotency-Key']).not.toBe((posts[0][1]?.headers as Record<string, string>)['Idempotency-Key']);
  });

  it('rotates keys after a definitive API error and never displays provider details', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(readySetupResponse());
      if (path.endsWith('/app-config')) return Promise.resolve(response({ data: {
        company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 7, rootUrl: 'https://notion.example' }, setup: { status: 'READY', required: false, canCreateMeeting: true, operationId: null },
      } }));
      if (path.endsWith('/templates')) return Promise.resolve(response({ data: { items: [{ id: 'default.md', name: 'Default', version: '1.0.0' }] } }));
      if (path.endsWith('/participants')) return Promise.resolve(response({ data: { items: participants } }));
      if (path.endsWith('/meeting-sessions')) {
        const count = fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions')).length;
        return Promise.resolve(count === 1
          ? response({ error: { code: 'VALIDATION_FAILED', message: 'provider token=private-secret' } }, 400)
          : response({ data: { sessionId: 'session-edited', version: 1, status: 'CREATED', uploadPolicy: { chunkDurationSeconds: 30, maxChunkBytes: 1000000, acceptedMimeTypes: ['audio/webm'] } } }, 201));
      }
      return Promise.reject(new Error(`Unexpected request: ${path} ${init?.method ?? 'GET'}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/new']}><App /></MemoryRouter>);
    await screen.findByText('Asia/Seoul');
    fireEvent.change(screen.getByLabelText('회의 제목'), { target: { value: 'Before edit' } });
    fireEvent.click(screen.getByRole('button', { name: '참석자 추가' }));
    fireEvent.click(within(screen.getByRole('group', { name: '참석자 선택' })).getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' }));
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));
    expect((await screen.findByRole('alert')).textContent).toContain('요청을 완료하지 못했습니다.');
    expect(screen.queryByText(/private-secret|provider token/)).toBeNull();
    fireEvent.change(screen.getByLabelText('회의 제목'), { target: { value: 'After edit' } });
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));
    expect(await screen.findByRole('heading', { name: 'After edit' })).toBeTruthy();
    const posts = fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions'));
    expect(JSON.parse(String(posts[0][1]?.body)).title).toBe('Before edit');
    expect(JSON.parse(String(posts[1][1]?.body)).title).toBe('After edit');
    expect((posts[1][1]?.headers as Record<string, string>)['Idempotency-Key']).not.toBe((posts[0][1]?.headers as Record<string, string>)['Idempotency-Key']);
  });

  it('refreshes setup and roster after a stale connection and submits with a new attempt', async () => {
    let configReads = 0;
    let rosterReads = 0;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(readySetupResponse());
      if (path.endsWith('/app-config')) {
        configReads += 1;
        const connectionVersion = configReads === 1 ? 7 : 8;
        return Promise.resolve(response({ data: {
          company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' },
          document: { provider: 'NOTION', configured: true, connectionVersion, rootUrl: 'https://notion.example' },
          setup: { status: 'READY', required: false, canCreateMeeting: true, operationId: null },
        } }));
      }
      if (path.endsWith('/templates')) return Promise.resolve(response({ data: { items: [{ id: 'default.md', name: 'Default', version: '1.0.0' }] } }));
      if (path.endsWith('/participants')) {
        rosterReads += 1;
        return Promise.resolve(response({ data: { items: rosterReads === 1 ? participants : [{ id: 'p1', name: 'Grace Hopper', email: 'grace@new-provider.example' }] } }));
      }
      if (path.endsWith('/meeting-sessions')) {
        const count = fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions')).length;
        return Promise.resolve(count === 1
          ? response({ error: { code: 'DOCUMENT_CONNECTION_CHANGED' } }, 409)
          : response({ data: { sessionId: 'session-new-connection', version: 1, status: 'CREATED', uploadPolicy: { chunkDurationSeconds: 30, maxChunkBytes: 1000000, acceptedMimeTypes: ['audio/webm'] } } }, 201));
      }
      return Promise.reject(new Error(`Unexpected request: ${path} ${init?.method ?? 'GET'}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/new']}><App /></MemoryRouter>);
    await screen.findByText('Asia/Seoul');
    fireEvent.change(screen.getByLabelText('회의 제목'), { target: { value: 'Current connection' } });
    fireEvent.click(screen.getByRole('button', { name: '참석자 추가' }));
    fireEvent.click(within(screen.getByRole('group', { name: '참석자 선택' })).getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' }));
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));

    expect(await screen.findByText(/문서 연결이 바뀌었습니다/)).toBeTruthy();
    expect(configReads).toBe(2);
    expect(rosterReads).toBe(2);
    expect(screen.queryByRole('list', { name: '선택한 참석자' })).toBeNull();
    fireEvent.click(within(screen.getByRole('group', { name: '참석자 선택' })).getByRole('checkbox', { name: 'Grace Hopper grace@new-provider.example' }));
    fireEvent.click(screen.getByRole('button', { name: '참석자 선택 닫기' }));
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));
    expect(await screen.findByRole('heading', { name: 'Current connection' })).toBeTruthy();
    const posts = fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions'));
    const first = JSON.parse(String(posts[0][1]?.body));
    const second = JSON.parse(String(posts[1][1]?.body));
    expect(first.documentConnectionVersion).toBe(7);
    expect(second).toMatchObject({ participantIds: ['p1'], documentConnectionVersion: 8 });
    expect(second.recoveryKey).not.toBe(first.recoveryKey);
    expect((posts[1][1]?.headers as Record<string, string>)['Idempotency-Key']).not.toBe((posts[0][1]?.headers as Record<string, string>)['Idempotency-Key']);
  });

  it('does not restore an old selection when stale-connection roster refresh fails then a new provider reuses its ID', async () => {
    let rosterReads = 0;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(readySetupResponse());
      if (path.endsWith('/app-config')) return Promise.resolve(response({ data: {
        company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' },
        document: { provider: 'NOTION', configured: true, connectionVersion: 7, rootUrl: 'https://notion.example' },
        setup: { status: 'READY', required: false, canCreateMeeting: true, operationId: null },
      } }));
      if (path.endsWith('/templates')) return Promise.resolve(response({ data: { items: [{ id: 'default.md', name: 'Default', version: '1.0.0' }] } }));
      if (path.endsWith('/participants')) {
        rosterReads += 1;
        if (rosterReads === 1) return Promise.resolve(response({ data: { items: participants } }));
        if (rosterReads === 2) return Promise.resolve(response({ error: { code: 'PARTICIPANT_LIST_FAILED' } }, 502));
        return Promise.resolve(response({ data: { items: [{ id: 'p1', name: 'Grace Hopper', email: 'grace@new-provider.example' }] } }));
      }
      if (path.endsWith('/meeting-sessions')) return Promise.resolve(response({ error: { code: 'DOCUMENT_CONNECTION_CHANGED' } }, 409));
      return Promise.reject(new Error(`Unexpected request: ${path} ${init?.method ?? 'GET'}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/new']}><App /></MemoryRouter>);
    await screen.findByText('Asia/Seoul');
    fireEvent.change(screen.getByLabelText('회의 제목'), { target: { value: 'Roster refresh retry' } });
    fireEvent.click(screen.getByRole('button', { name: '참석자 추가' }));
    fireEvent.click(within(screen.getByRole('group', { name: '참석자 선택' })).getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' }));
    fireEvent.click(screen.getByRole('button', { name: '참석자 선택 닫기' }));
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));

    expect(await screen.findByText(/문서 연결이 바뀌었습니다/)).toBeTruthy();
    expect(await screen.findByRole('button', { name: '참석자 목록 다시 시도' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '참석자 목록 다시 시도' }));
    fireEvent.click(await screen.findByRole('button', { name: '참석자 추가' }));
    const newProviderCheckbox = await screen.findByRole('checkbox', { name: 'Grace Hopper grace@new-provider.example' });
    expect(newProviderCheckbox).toHaveProperty('checked', false);
    expect(screen.queryByRole('list', { name: '선택한 참석자' })).toBeNull();
    expect(rosterReads).toBe(3);
  });

  it('blocks API-006 while API-001 says a document switch is in progress', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(readySetupResponse());
      if (path.endsWith('/app-config')) return Promise.resolve(response({ data: {
        company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' },
        document: { provider: 'NOTION', configured: true, connectionVersion: 7, rootUrl: 'https://notion.example' },
        setup: { status: 'SWITCHING', required: false, canCreateMeeting: false, operationId: 'switch-1' },
      } }));
      if (path.endsWith('/templates')) return Promise.resolve(response({ data: { items: [{ id: 'default.md', name: 'Default', version: '1.0.0' }] } }));
      if (path.endsWith('/participants')) return Promise.resolve(response({ data: { items: participants } }));
      return Promise.reject(new Error(`Unexpected request: ${path} ${init?.method ?? 'GET'}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/new']}><App /></MemoryRouter>);
    await screen.findByText('Asia/Seoul');
    expect(screen.getByRole('link', { name: 'Open document settings' }).getAttribute('href')).toBe('/setup/document');
    expect(screen.getByRole('button', { name: '회의 시작' })).toHaveProperty('disabled', true);
    expect(fetchMock.mock.calls.some(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions'))).toBe(false);
  });

  it('renders the SCR-001 Home content and navigation without fetching recent meetings', async () => {
    const fetchMock = vi.fn();
    installFetchMock(fetchMock);
    renderHomeWithDestinationFixture();

    expect(await screen.findByRole('heading', { level: 1, name: 'Meeting Automation' })).toBeTruthy();
    expect(screen.getByText('회의를 시작할까요?')).toBeTruthy();
    expect(screen.getByText('회의 내용을 녹음하고 자동으로 정리합니다.')).toBeTruthy();
    expect(screen.getByRole('link', { name: '새 회의 시작' })).toBeTruthy();
    const navigation = within(screen.getByRole('navigation', { name: '주요 메뉴' }));
    expect(navigation.getAllByRole('link').map((link) => link.getAttribute('aria-label'))).toEqual(['홈', '회의록', '설정']);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('navigates the Home CTA to the New Meeting route fixture', async () => {
    installFetchMock(vi.fn().mockResolvedValue(response({ data: {} })));
    renderHomeWithDestinationFixture();
    fireEvent.click(await screen.findByRole('link', { name: '새 회의 시작' }));
    expect(await screen.findByText('New Meeting fixture: /meetings/new')).toBeTruthy();
  });

  it('serves the New Meeting screen at /meetings/new through the application router', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith('/app-config')) return Promise.resolve(response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' } } }));
      if (path.endsWith('/templates')) return Promise.resolve(response({ data: { items: [{ id: 'default.md', name: '기본 회의록', version: '1.0.0' }] } }));
      return Promise.resolve(response({ data: { items: participants } }));
    });
    installFetchMock(fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/new']}><App onNewMeetingSubmit={vi.fn()} /></MemoryRouter>);

    expect(await screen.findByRole('heading', { level: 1, name: '새 회의' })).toBeTruthy();
    expect(await screen.findByText('Asia/Seoul')).toBeTruthy();
    expect(fetchMock.mock.calls.map(([url]) => new URL(String(url)).pathname)).toEqual([
      '/api/v1/app-config', '/api/v1/templates', '/api/v1/participants',
    ]);
  });

  it('shows loading and then renders roster items', async () => {
    let resolve!: (value: Response) => void;
    const pending = new Promise<Response>((done) => { resolve = done; });
    mockList(pending);
    renderParticipantsApp();

    expect(screen.getAllByRole('main')).toHaveLength(1);
    expect(screen.getByRole('status').textContent).toMatch(/확인하는 중|불러오는 중/);
    resolve(response({ data: { items: participants } }));
    expect(await screen.findByText('Ada Lovelace')).toBeTruthy();
    expect(screen.getByText('ada@example.com')).toBeTruthy();
  });

  it('shows empty and search-empty recovery actions', async () => {
    mockList(response({ data: { items: participants } }));
    renderParticipantsApp();
    await screen.findByText('Ada Lovelace');
    fireEvent.change(screen.getByRole('searchbox', { name: '참석자 검색' }), { target: { value: 'nobody' } });
    expect(screen.getByText(/검색 결과가 없습니다/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '검색 입력 지우기' }));
    expect(screen.getByText('Ada Lovelace')).toBeTruthy();

    mockList(response({ data: { items: [] } }));
    cleanup();
    renderParticipantsApp();
    expect(await screen.findByText(/등록된 참석자가 없습니다/)).toBeTruthy();
  });

  it('filters by case-insensitive name or email substring', async () => {
    mockList(response({ data: { items: participants } }));
    renderParticipantsApp();
    await screen.findByText('Ada Lovelace');
    fireEvent.change(screen.getByRole('searchbox', { name: '참석자 검색' }), { target: { value: 'NAVY' } });
    expect(screen.getByText('Grace Hopper')).toBeTruthy();
    expect(screen.queryByText('Ada Lovelace')).toBeNull();
  });

  it('retries safely after list failure', async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new Error('private provider details'))
      .mockResolvedValueOnce(response({ data: { items: participants } }));
    installFetchMock(fetchMock);
    renderParticipantsApp();
    expect((await screen.findByRole('alert')).textContent).toMatch(/불러오지 못했습니다/);
    expect(screen.queryByText(/private provider details/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('Ada Lovelace')).toBeTruthy();
  });

  it('creates a participant with an idempotency key and keeps fields on failure', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ data: { items: [] } }))
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(response({ data: { id: 'p3', name: 'Lin Chen', email: 'lin@example.com' } }, 201));
    installFetchMock(fetchMock);
    renderParticipantsApp();
    await screen.findByText(/등록된 참석자가 없습니다/);
    fireEvent.click(screen.getByRole('button', { name: '참석자 추가' }));
    fireEvent.change(screen.getByLabelText('이름'), { target: { value: 'Lin Chen' } });
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'lin@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/저장하지 못했습니다/);
    expect((screen.getByLabelText('이름') as HTMLInputElement).value).toBe('Lin Chen');
    const firstKey = fetchMock.mock.calls[1][1]?.headers as Record<string, string>;
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await screen.findByText('Lin Chen');
    const secondKey = fetchMock.mock.calls[2][1]?.headers as Record<string, string>;
    expect(firstKey['Idempotency-Key']).toBeTruthy();
    expect(secondKey['Idempotency-Key']).toBe(firstKey['Idempotency-Key']);
    expect(JSON.parse(String(fetchMock.mock.calls[1][1]?.body))).toEqual({ name: 'Lin Chen', email: 'lin@example.com' });
  });

  it('connects validation errors to fields and does not send invalid input', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response({ data: { items: [] } }));
    installFetchMock(fetchMock);
    renderParticipantsApp();
    await screen.findByText(/등록된 참석자가 없습니다/);
    fireEvent.click(screen.getByRole('button', { name: '새 참석자 추가' }));
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/입력 내용을 확인/);
    expect(screen.getByLabelText('이름').getAttribute('aria-describedby')).toBe('name-error');
    expect(screen.getByLabelText('이메일').getAttribute('aria-describedby')).toBe('email-error');
    fireEvent.change(screen.getByLabelText('이름'), { target: { value: 'Lin Chen' } });
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'invalid' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(screen.getByText(/올바른 이메일 주소/)).toBeTruthy();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('updates a row using only changed fields and reflects the response', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ data: { items: participants } }))
      .mockResolvedValueOnce(response({ data: { id: 'p1', name: 'Ada Byron', email: 'ada@example.com' } }));
    installFetchMock(fetchMock);
    renderParticipantsApp();
    await screen.findByText('Ada Lovelace');
    fireEvent.click(within(screen.getByRole('article', { name: /Ada Lovelace/ })).getByRole('button', { name: '수정' }));
    fireEvent.change(screen.getByLabelText('이름'), { target: { value: 'Ada Byron' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(await screen.findByText('Ada Byron')).toBeTruthy();
    expect(fetchMock.mock.calls[1][1]?.method).toBe('PATCH');
    expect(JSON.parse(String(fetchMock.mock.calls[1][1]?.body))).toEqual({ name: 'Ada Byron' });
    expect(screen.getByText('ada@example.com')).toBeTruthy();
  });

  it('places focus in the dialog, contains keyboard focus, closes on Escape and restores focus', async () => {
    mockList(response({ data: { items: participants } }));
    renderParticipantsApp();
    await screen.findByText('Ada Lovelace');
    const launcher = screen.getByRole('button', { name: '새 참석자 추가' });
    launcher.focus();
    fireEvent.click(launcher);

    const dialog = screen.getByRole('dialog');
    const nameInput = screen.getByLabelText('이름');
    const closeButton = screen.getByRole('button', { name: '닫기' });
    const saveButton = screen.getByRole('button', { name: '저장' });
    expect(document.activeElement).toBe(nameInput);

    saveButton.focus();
    fireEvent.keyDown(dialog, { key: 'Tab' });
    expect(document.activeElement).toBe(closeButton);

    closeButton.focus();
    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(saveButton);

    fireEvent.keyDown(dialog, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(launcher);
  });

  it('keeps an in-flight create open on Escape and reuses its idempotency key after failure', async () => {
    let rejectCreate!: (reason: Error) => void;
    const pendingCreate = new Promise<Response>((_resolve, reject) => { rejectCreate = reject; });
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ data: { items: [] } }))
      .mockReturnValueOnce(pendingCreate)
      .mockResolvedValueOnce(response({ data: { id: 'p3', name: 'Lin Chen', email: 'lin@example.com' } }, 201));
    installFetchMock(fetchMock);
    renderParticipantsApp();
    await screen.findByText(/등록된 참석자가 없습니다/);
    fireEvent.click(screen.getByRole('button', { name: '새 참석자 추가' }));
    fireEvent.change(screen.getByLabelText('이름'), { target: { value: 'Lin Chen' } });
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'lin@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    const dialog = screen.getByRole('dialog');
    const firstKey = (fetchMock.mock.calls[1][1]?.headers as Record<string, string>)['Idempotency-Key'];

    fireEvent.keyDown(dialog, { key: 'Escape' });
    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(screen.getByRole('button', { name: '저장 중…' })).toBeTruthy();

    rejectCreate(new Error('connection lost after request dispatch'));
    expect((await screen.findByRole('alert')).textContent).toMatch(/저장하지 못했습니다/);
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await screen.findByText('Lin Chen');
    const retryKey = (fetchMock.mock.calls[2][1]?.headers as Record<string, string>)['Idempotency-Key'];
    expect(retryKey).toBe(firstKey);
  });

  it('bounds the sheet to a short viewport and keeps its actions in the scrollable panel', async () => {
    const originalHeight = window.innerHeight;
    try {
      Object.defineProperty(window, 'innerHeight', { configurable: true, value: 640 });
      mockList(response({ data: { items: participants } }));
      renderParticipantsApp();
      await screen.findByText('Ada Lovelace');
      fireEvent.click(screen.getByRole('button', { name: '새 참석자 추가' }));

      const dialog = screen.getByRole('dialog');
      expect(dialog.style.maxHeight).toContain('616px');
      expect(dialog.style.overflowY).toBe('auto');
      Object.defineProperty(window, 'innerHeight', { configurable: true, value: 360 });
      fireEvent(window, new Event('resize'));
      await waitFor(() => expect(dialog.style.maxHeight).toContain('336px'));
      expect(dialog.contains(screen.getByRole('button', { name: '저장' }))).toBe(true);
      expect(dialog.contains(screen.getByRole('button', { name: '취소' }))).toBe(true);
    } finally {
      Object.defineProperty(window, 'innerHeight', { configurable: true, value: originalHeight });
    }
  });

  it('validates fields and patches only changed values; retains edit values after 404', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ data: { items: participants } }))
      .mockResolvedValueOnce(response({ error: { code: 'PARTICIPANT_NOT_FOUND' } }, 404))
      .mockResolvedValueOnce(response({ data: { items: participants } }));
    installFetchMock(fetchMock);
    renderParticipantsApp();
    await screen.findByText('Ada Lovelace');
    fireEvent.click(within(screen.getByRole('article', { name: /Ada Lovelace/ })).getByRole('button', { name: '수정' }));
    expect((screen.getByLabelText('이름') as HTMLInputElement).value).toBe('Ada Lovelace');
    fireEvent.change(screen.getByLabelText('이름'), { target: { value: 'Ada Byron' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/목록을 새로고침/);
    expect((screen.getByLabelText('이름') as HTMLInputElement).value).toBe('Ada Byron');
    expect(fetchMock.mock.calls[1][0]).toContain('/participants/p1');
    expect(fetchMock.mock.calls[1][1]?.method).toBe('PATCH');
    expect(JSON.parse(String(fetchMock.mock.calls[1][1]?.body))).toEqual({ name: 'Ada Byron' });
    fireEvent.click(screen.getByRole('button', { name: '목록 새로고침' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
  });
});

describe('Recording route', () => {
  it('accepts an immediate start interaction as soon as the recording route is painted', async () => {
    sessionStorage.setItem('meeting-automation.recording-session', JSON.stringify({
      sessionId: 'session-immediate-start', title: 'Immediate start', version: 1, status: 'CREATED',
      uploadPolicy: { chunkDurationSeconds: 15, maxChunkBytes: 1000000, acceptedMimeTypes: ['audio/webm'] },
    }));
    const track = { stop: vi.fn() };
    const mediaAdapter: MediaRecorderAdapter = {
      isMediaRecorderSupported: () => true,
      isTypeSupported: () => true,
      getUserMedia: vi.fn(async () => ({ getTracks: () => [track] })),
      createRecorder: vi.fn(() => {
        const fake: RecorderLike = {
          mimeType: 'audio/webm', onstart: null, onpause: null, onresume: null, onstop: null, onerror: null, ondataavailable: null,
          start: () => fake.onstart?.(), pause: () => fake.onpause?.(), resume: () => fake.onresume?.(), stop: () => fake.onstop?.(),
        };
        return fake;
      }),
      now: () => 0,
      nowEpoch: () => 1_700_000_000_000,
      setInterval: () => 1,
      clearInterval: () => undefined,
      createChunkId: () => 'immediate-start-chunk',
    };
    const fetchMock = vi.fn(async () => response({ data: { receivedSequences: [], receivedChunks: 0, totalBytes: 0 } }));
    vi.stubGlobal('fetch', fetchMock);

    render(<MemoryRouter initialEntries={['/meetings/session-immediate-start/recording']}><App mediaAdapter={mediaAdapter} /><StartRecordingDuringLayout /></MemoryRouter>);

    expect(mediaAdapter.getUserMedia).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('녹음 중')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Immediate start' })).toBeTruthy();
  });

  it('restores the runtime session on reload and reconciles already received local chunks without resending', async () => {
    await deleteChunkDatabase();
    const session = {
      sessionId: 'session-reload', title: 'Recovered planning', version: 1, status: 'CREATED',
      uploadPolicy: { chunkDurationSeconds: 15, maxChunkBytes: 1000000, acceptedMimeTypes: ['audio/webm'] },
    };
    sessionStorage.setItem('meeting-automation.recording-session', JSON.stringify(session));
    const repository = new ChunkRepository({ dbName: 'meeting-automation-chunks', maxChunkBytes: 1000000 });
    await repository.append('session-reload', {
      chunkId: 'reload-chunk-0', blob: new NodeBlob(['saved audio']) as Blob, mimeType: 'audio/webm', recordedAtMs: 1, recordedElapsedMs: 15_000,
    });
    repository.close();
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/audio')) return response({ data: { receivedSequences: [0], receivedChunks: 1, totalBytes: 11 } });
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<MemoryRouter initialEntries={['/meetings/session-reload/recording']}><App /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: 'Recovered planning' })).toBeTruthy();
    await waitFor(async () => {
      const check = new ChunkRepository({ dbName: 'meeting-automation-chunks', maxChunkBytes: 1000000 });
      const pending = await check.getPending('session-reload');
      check.close();
      expect(pending.chunks).toHaveLength(0);
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(screen.getByText('오디오 업로드가 완료되었습니다.')).toBeTruthy();
  });

  it('preserves IndexedDB audio when API-008 reports SESSION_NOT_FOUND', async () => {
    await deleteChunkDatabase();
    const session = {
      sessionId: 'missing-backend-session', title: 'Saved locally', version: 1, status: 'CREATED',
      uploadPolicy: { chunkDurationSeconds: 15, maxChunkBytes: 1000000, acceptedMimeTypes: ['audio/webm'] },
    };
    sessionStorage.setItem('meeting-automation.recording-session', JSON.stringify(session));
    const repository = new ChunkRepository({ dbName: 'meeting-automation-chunks', maxChunkBytes: 1000000 });
    await repository.append(session.sessionId, {
      chunkId: 'preserve-me', blob: new NodeBlob(['unsent audio']) as Blob, mimeType: 'audio/webm', recordedAtMs: 1, recordedElapsedMs: 15_000,
    });
    repository.close();
    const fetchMock = vi.fn().mockResolvedValue(response({ error: { code: 'SESSION_NOT_FOUND' } }, 404));
    vi.stubGlobal('fetch', fetchMock);

    render(<MemoryRouter initialEntries={[`/meetings/${session.sessionId}/recording`]}><App /></MemoryRouter>);

    expect(await screen.findByText(/로컬 오디오는 보존되었습니다/)).toBeTruthy();
    const check = new ChunkRepository({ dbName: 'meeting-automation-chunks', maxChunkBytes: 1000000 });
    const pending = await check.getPending(session.sessionId);
    check.close();
    expect(pending.chunks.map(({ chunkId, sequence }) => [chunkId, sequence])).toEqual([['preserve-me', 0]]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('shows the created meeting title, session id, ready status, timer, and start action', async () => {
    const track = { stop: vi.fn() };
    const mediaAdapter: MediaRecorderAdapter = {
      isMediaRecorderSupported: () => true,
      isTypeSupported: () => true,
      getUserMedia: vi.fn(async () => ({ getTracks: () => [track] })),
      createRecorder: vi.fn(() => {
        const fake: RecorderLike = {
          mimeType: 'audio/webm', onstart: null, onpause: null, onresume: null, onstop: null, onerror: null, ondataavailable: null,
          start: () => fake.onstart?.(), pause: () => fake.onpause?.(), resume: () => fake.onresume?.(), stop: () => fake.onstop?.(),
        };
        return fake;
      }),
      now: () => 0,
      nowEpoch: () => 1_700_000_000_000,
      setInterval: () => 1,
      clearInterval: () => undefined,
      createChunkId: () => 'chunk-test',
    };
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup')) return Promise.resolve(readySetupResponse());
      if (path.endsWith('/app-config')) return Promise.resolve(response({ data: {
        company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' },
        document: { provider: 'NOTION', configured: true, connectionVersion: 7, rootUrl: 'https://notion.example' },
        setup: { status: 'READY', required: false, canCreateMeeting: true, operationId: null },
      } }));
      if (path.endsWith('/templates')) return Promise.resolve(response({ data: { items: [{ id: 'default.md', name: 'Default', version: '1.0.0' }] } }));
      if (path.endsWith('/participants')) return Promise.resolve(response({ data: { items: participants } }));
      if (path.endsWith('/meeting-sessions')) return Promise.resolve(response({ data: {
        sessionId: 'session-ready', version: 1, status: 'CREATED',
        uploadPolicy: { chunkDurationSeconds: 30, maxChunkBytes: 1000000, acceptedMimeTypes: ['audio/webm'] },
      } }, 201));
      return Promise.reject(new Error(`Unexpected request: ${path} ${init?.method ?? 'GET'}`));
    });
    await new Promise<void>((resolve, reject) => {
      const deletion = indexedDB.deleteDatabase('meeting-automation-chunks');
      deletion.onsuccess = () => resolve();
      deletion.onerror = () => reject(deletion.error);
      deletion.onblocked = () => reject(new Error('Chunk test database is still open'));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<StrictMode><MemoryRouter initialEntries={['/meetings/new']}><App mediaAdapter={mediaAdapter} /><NavigateToMismatchedRecordingSession /></MemoryRouter></StrictMode>);

    await screen.findByText('Asia/Seoul');
    fireEvent.change(screen.getByLabelText('회의 제목'), { target: { value: 'Planning' } });
    fireEvent.click(screen.getByRole('button', { name: '참석자 추가' }));
    fireEvent.click(within(screen.getByRole('group', { name: '참석자 선택' })).getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' }));
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));

    expect(await screen.findByRole('heading', { name: 'Planning' })).toBeTruthy();
    expect(screen.getByText('session-ready')).toBeTruthy();
    expect(screen.getByText('준비됨')).toBeTruthy();
    expect(screen.getByText('00:00:00')).toBeTruthy();
    expect(screen.getByRole('button', { name: '녹음 시작' })).toBeTruthy();
    expect(screen.getByRole('navigation', { name: '주요 메뉴' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '녹음 시작' }));
    expect(mediaAdapter.getUserMedia).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('녹음 중')).toBeTruthy();
    const recorder = vi.mocked(mediaAdapter.createRecorder).mock.results[0].value;
    recorder.ondataavailable?.({ data: new NodeBlob(['audio-data'], { type: 'audio/webm' }) });
    const chunkRepository = new ChunkRepository({ dbName: 'meeting-automation-chunks', maxChunkBytes: 1_000_000 });
    await waitFor(async () => {
      const pending = await chunkRepository.getPending('session-ready');
      expect(pending.chunks.map(({ chunkId, sequence }) => [chunkId, sequence])).toEqual([['chunk-test', 0]]);
    });
    chunkRepository.close();
    expect(screen.queryByRole('navigation', { name: '주요 메뉴' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '회의 종료' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '계속 녹음' }));
    expect(screen.getByText('녹음 중')).toBeTruthy();
    expect(screen.getByText('00:00:00')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '일시정지' }));
    expect(screen.getByText('일시정지됨')).toBeTruthy();
    expect(screen.queryByRole('navigation', { name: '주요 메뉴' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '녹음 재개' }));
    expect(screen.getByText('녹음 중')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '회의 종료' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '회의 종료' }));
    expect(await screen.findByText('녹음이 종료되었습니다.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '녹음 시작' })).toBeNull();
    expect(track.stop).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: 'Open another session' }));
    expect(screen.getByRole('heading', { name: '녹음 세션을 찾을 수 없습니다' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '새 회의 시작' }).getAttribute('href')).toBe('/meetings/new');
    expect(screen.queryByRole('button', { name: '회의 종료' })).toBeNull();
  });

  it('offers a new meeting and no recording controls when runtime session context is missing or mismatched', () => {
    render(<MemoryRouter initialEntries={['/meetings/missing-session/recording']}><App /></MemoryRouter>);

    expect(screen.getByRole('heading', { name: '녹음 세션을 찾을 수 없습니다' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '새 회의 시작' }).getAttribute('href')).toBe('/meetings/new');
    expect(screen.queryByRole('button', { name: '녹음 시작' })).toBeNull();
    expect(screen.queryByRole('button', { name: '회의 종료' })).toBeNull();
  });
});

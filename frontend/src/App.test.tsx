import 'fake-indexeddb/auto';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { StrictMode, useLayoutEffect, useRef } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router';
import { App } from './App';
import type { MediaRecorderAdapter, RecorderLike } from './recordingController';
import { ChunkRepository } from './chunkRepository';
// @ts-expect-error Node's Blob exercises browser-style IndexedDB structured cloning in jsdom.
import { Blob as NodeBlob } from 'node:buffer';
// @ts-expect-error Node WebCrypto exercises the production digest path in jsdom.
import { webcrypto } from 'node:crypto';

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

function CurrentPath() {
  const location = useLocation();
  return <p data-testid="current-path">{location.pathname}</p>;
}

function ReturnToReview({ sessionId }: { sessionId: string }) {
  const navigate = useNavigate();
  return <button type="button" onClick={() => navigate(`/meetings/${sessionId}/review`)}>Review로 돌아가기</button>;
}

function NavigateToReviewSession({ sessionId }: { sessionId: string }) {
  const navigate = useNavigate();
  return <button type="button" onClick={() => navigate(`/meetings/${sessionId}/review`)}>Open another Review session</button>;
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

  it('renders the SCR-001 Home content, loads recent meetings, and preserves navigation', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      void input;
      return response({ data: { items: [] } });
    });
    installFetchMock(fetchMock);
    renderHomeWithDestinationFixture();

    expect(await screen.findByRole('heading', { level: 1, name: 'Meeting Automation' })).toBeTruthy();
    expect(screen.getByText('회의를 시작할까요?')).toBeTruthy();
    expect(screen.getByText('회의 내용을 녹음하고 자동으로 정리합니다.')).toBeTruthy();
    expect(screen.getByRole('link', { name: '새 회의 시작' })).toBeTruthy();
    const navigation = within(screen.getByRole('navigation', { name: '주요 메뉴' }));
    expect(navigation.getAllByRole('link').map((link) => link.getAttribute('aria-label'))).toEqual(['홈', '회의록', '설정']);
    await screen.findByText(/최근 회의가 없습니다/);
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/api/v1/meetings?limit=5');
  });

  it('navigates the Home CTA to the New Meeting route fixture', async () => {
    installFetchMock(vi.fn().mockResolvedValue(response({ data: { items: [] } })));
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
    expect(screen.getByRole('heading', { name: '참석자 목록을 불러오지 못했습니다.' })).toBeTruthy();
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

  it('waits for the final Chunk ACK before starting processing with the recorded metadata', async () => {
    await deleteChunkDatabase();
    const session = {
      sessionId: 'session-processing', title: 'Processing planning', version: 1, status: 'CREATED',
      uploadPolicy: { chunkDurationSeconds: 15, maxChunkBytes: 1000000, acceptedMimeTypes: ['audio/webm'] },
    };
    sessionStorage.setItem('meeting-automation.recording-session', JSON.stringify(session));
    vi.stubGlobal('crypto', webcrypto);
    let elapsedMs = 0;
    const recorder: RecorderLike = {
      mimeType: 'audio/webm', onstart: null, onpause: null, onresume: null, onstop: null, onerror: null, ondataavailable: null,
      start: () => recorder.onstart?.(), pause: () => recorder.onpause?.(), resume: () => recorder.onresume?.(),
      stop: () => {
        recorder.ondataavailable?.({ data: new NodeBlob(['final audio'], { type: 'audio/webm' }) as Blob });
        recorder.onstop?.();
      },
    };
    const mediaAdapter: MediaRecorderAdapter = {
      isMediaRecorderSupported: () => true,
      isTypeSupported: () => true,
      getUserMedia: vi.fn(async () => ({ getTracks: () => [{ stop: vi.fn() }] })),
      createRecorder: vi.fn(() => recorder),
      now: () => elapsedMs,
      nowEpoch: () => 1_700_000_000_000 + elapsedMs,
      setInterval: () => 1,
      clearInterval: () => undefined,
      createChunkId: () => 'final-chunk',
    };
    const events: string[] = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/audio')) {
        events.push('API-008');
        return response({ data: { receivedSequences: [], receivedChunks: 0, totalBytes: 0 } });
      }
      if (path.endsWith('/audio/chunks/0')) {
        events.push('API-007');
        return response({ data: { sequence: 0, received: true } });
      }
      if (path.endsWith('/process')) {
        events.push('API-009');
        return response({ data: { sessionId: session.sessionId, status: 'PROCESSING', stage: 'AUDIO_ASSEMBLY' } }, 202);
      }
      throw new Error(`Unexpected request: ${path} ${init?.method ?? 'GET'}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<MemoryRouter initialEntries={[`/meetings/${session.sessionId}/recording`]}>
      <App mediaAdapter={mediaAdapter} />
      <CurrentPath />
    </MemoryRouter>);

    fireEvent.click(await screen.findByRole('button', { name: '녹음 시작' }));
    await screen.findByText('녹음 중');
    elapsedMs = 4_250;
    fireEvent.click(screen.getByRole('button', { name: '회의 종료' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '회의 종료' }));

    await waitFor(() => {
      const paths = fetchMock.mock.calls.map(([url]) => new URL(String(url)).pathname);
      expect(paths.filter((path) => path.endsWith('/process'))).toHaveLength(1);
    });

    const processCall = fetchMock.mock.calls.find(([url]) => new URL(String(url)).pathname.endsWith('/process'));
    expect(processCall).toBeDefined();
    expect(processCall?.[1]?.method).toBe('POST');
    expect(JSON.parse(String(processCall?.[1]?.body))).toEqual({ expectedChunks: 1, durationMs: 4_250, mimeType: 'audio/webm' });
    expect(new Headers(processCall?.[1]?.headers).get('Idempotency-Key')).toBeTruthy();
    expect(events.indexOf('API-007')).toBeGreaterThanOrEqual(0);
    expect(events.indexOf('API-007')).toBeLessThan(events.indexOf('API-009'));
    await waitFor(() => expect(screen.getByTestId('current-path').textContent).toBe(`/meetings/${session.sessionId}/processing`));
  });

  it('retries an uncertain API-009 response with the same key and payload', async () => {
    await deleteChunkDatabase();
    const session = {
      sessionId: 'session-processing-retry', title: 'Retry planning', version: 1, status: 'CREATED',
      uploadPolicy: { chunkDurationSeconds: 15, maxChunkBytes: 1000000, acceptedMimeTypes: ['audio/webm'] },
    };
    sessionStorage.setItem('meeting-automation.recording-session', JSON.stringify(session));
    vi.stubGlobal('crypto', webcrypto);
    let elapsedMs = 0;
    const recorder: RecorderLike = {
      mimeType: 'audio/webm', onstart: null, onpause: null, onresume: null, onstop: null, onerror: null, ondataavailable: null,
      start: () => recorder.onstart?.(), pause: () => recorder.onpause?.(), resume: () => recorder.onresume?.(),
      stop: () => {
        recorder.ondataavailable?.({ data: new NodeBlob(['retry audio'], { type: 'audio/webm' }) as Blob });
        recorder.onstop?.();
      },
    };
    const mediaAdapter: MediaRecorderAdapter = {
      isMediaRecorderSupported: () => true, isTypeSupported: () => true,
      getUserMedia: vi.fn(async () => ({ getTracks: () => [{ stop: vi.fn() }] })),
      createRecorder: vi.fn(() => recorder), now: () => elapsedMs, nowEpoch: () => 1_700_000_000_000 + elapsedMs,
      setInterval: () => 1, clearInterval: () => undefined, createChunkId: () => 'retry-final-chunk',
    };
    let processAttempts = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/audio')) return response({ data: { receivedSequences: [], receivedChunks: 0, totalBytes: 0 } });
      if (path.endsWith('/audio/chunks/0')) return response({ data: { sequence: 0, received: true } });
      if (path.endsWith('/process')) {
        processAttempts += 1;
        if (processAttempts === 1) throw new TypeError('connection lost after request was sent');
        return response({ data: { sessionId: session.sessionId, status: 'PROCESSING', stage: 'AUDIO_ASSEMBLY' } }, 202);
      }
      throw new Error(`Unexpected request: ${path} ${_init?.method ?? 'GET'}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={[`/meetings/${session.sessionId}/recording`]}>
      <App mediaAdapter={mediaAdapter} />
      <CurrentPath />
    </MemoryRouter>);

    fireEvent.click(await screen.findByRole('button', { name: '녹음 시작' }));
    await screen.findByText('녹음 중');
    elapsedMs = 8_500;
    fireEvent.click(screen.getByRole('button', { name: '회의 종료' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '회의 종료' }));
    const retryButton = await screen.findByRole('button', { name: '처리 시작 재시도' });
    fireEvent.click(retryButton);

    await waitFor(() => expect(processAttempts).toBe(2));

    const processCalls = fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/process'));
    const firstRequest = processCalls[0]?.[1];
    const secondRequest = processCalls[1]?.[1];
    expect(firstRequest?.body).toBe(secondRequest?.body);
    expect(JSON.parse(String(firstRequest?.body))).toEqual({ expectedChunks: 1, durationMs: 8_500, mimeType: 'audio/webm' });
    expect(new Headers(firstRequest?.headers).get('Idempotency-Key')).toBeTruthy();
    expect(new Headers(secondRequest?.headers).get('Idempotency-Key')).toBe(new Headers(firstRequest?.headers).get('Idempotency-Key'));
    await waitFor(() => expect(screen.getByTestId('current-path').textContent).toBe(`/meetings/${session.sessionId}/processing`));
  });

  it('settles a recorder stop error without starting processing and keeps previously persisted audio', async () => {
    await deleteChunkDatabase();
    const session = {
      sessionId: 'session-stop-error', title: 'Stop error recovery', version: 1, status: 'CREATED',
      uploadPolicy: { chunkDurationSeconds: 15, maxChunkBytes: 1000000, acceptedMimeTypes: ['audio/webm'] },
    };
    sessionStorage.setItem('meeting-automation.recording-session', JSON.stringify(session));
    vi.stubGlobal('crypto', webcrypto);
    const recorder: RecorderLike = {
      mimeType: 'audio/webm', onstart: null, onpause: null, onresume: null, onstop: null, onerror: null, ondataavailable: null,
      start: () => recorder.onstart?.(), pause: () => recorder.onpause?.(), resume: () => recorder.onresume?.(),
      stop: vi.fn(),
    };
    const mediaAdapter: MediaRecorderAdapter = {
      isMediaRecorderSupported: () => true,
      isTypeSupported: () => true,
      getUserMedia: vi.fn(async () => ({ getTracks: () => [{ stop: vi.fn() }] })),
      createRecorder: vi.fn(() => recorder),
      now: () => 4_250,
      nowEpoch: () => 1_700_000_004_250,
      setInterval: () => 1,
      clearInterval: () => undefined,
      createChunkId: () => 'pre-error-chunk',
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/audio')) return response({ data: { receivedSequences: [], receivedChunks: 0, totalBytes: 0 } });
      if (path.endsWith('/audio/chunks/0')) return response({ error: { code: 'AUDIO_UPLOAD_REJECTED' } }, 422);
      if (path.endsWith('/process')) return response({ data: { sessionId: session.sessionId, status: 'PROCESSING', stage: 'AUDIO_ASSEMBLY' } }, 202);
      throw new Error(`Unexpected request: ${path}`);
    });
    installFetchMock(fetchMock);

    render(<MemoryRouter initialEntries={[`/meetings/${session.sessionId}/recording`]}><App mediaAdapter={mediaAdapter} /></MemoryRouter>);
    fireEvent.click(await screen.findByRole('button', { name: '녹음 시작' }));
    await screen.findByText('녹음 중');
    recorder.ondataavailable?.({ data: new NodeBlob(['already saved audio'], { type: 'audio/webm' }) as Blob });
    const repository = new ChunkRepository({ dbName: 'meeting-automation-chunks', maxChunkBytes: 1_000_000 });
    try {
      await waitFor(async () => expect((await repository.getPending(session.sessionId)).chunks).toHaveLength(1));

      fireEvent.click(screen.getByRole('button', { name: '회의 종료' }));
      fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '회의 종료' }));
      recorder.onerror?.({ error: { name: 'UnknownError' } });

      await waitFor(() => expect(screen.getByLabelText('처리 시작 상태').textContent).toContain('처리를 시작하지 못했습니다.'), { timeout: 1_000 });
      expect(screen.queryByRole('button', { name: '녹음 시작' })).toBeNull();
      expect(screen.getByRole('link', { name: '새 회의 시작' }).getAttribute('href')).toBe('/meetings/new');
      expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/process'))).toHaveLength(0);
      const recoverable = await repository.getRecoverable(session.sessionId);
      expect(recoverable.chunks.map(({ chunkId, uploadState, blob }) => [chunkId, uploadState, blob.size])).toEqual([
        ['pre-error-chunk', 'PENDING', new Blob(['already saved audio']).size],
      ]);
    } finally {
      repository.close();
    }
  });

  it('offers a new meeting and no recording controls when runtime session context is missing or mismatched', () => {
    render(<MemoryRouter initialEntries={['/meetings/missing-session/recording']}><App /></MemoryRouter>);

    expect(screen.getByRole('heading', { name: '녹음 세션을 찾을 수 없습니다' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '새 회의 시작' }).getAttribute('href')).toBe('/meetings/new');
    expect(screen.queryByRole('button', { name: '녹음 시작' })).toBeNull();
    expect(screen.queryByRole('button', { name: '회의 종료' })).toBeNull();
  });
});

describe('Processing route', () => {
  it('loads API-010 immediately and shows only the processing stage while hiding bottom navigation', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => { void input; return response({ data: {
      sessionId: 'processing-session', version: 2, status: 'PROCESSING',
      processing: { stage: 'AUDIO_ASSEMBLY', progressPercent: 25 },
      transcript: { text: 'partial transcript must stay hidden' },
      minutes: { text: 'partial minutes must stay hidden' },
      allowedActions: [],
    } }); });
    vi.stubGlobal('fetch', fetchMock);

    render(<MemoryRouter initialEntries={['/meetings/processing-session/processing']}><App /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: '회의 처리' })).toBeTruthy();
    expect(screen.getByText('오디오 준비')).toBeTruthy();
    expect(await screen.findByRole('progressbar', { name: '처리 진행률' })).toHaveProperty('value', 25);
    expect(screen.queryByRole('navigation', { name: '주요 메뉴' })).toBeNull();
    expect(screen.queryByText('partial transcript must stay hidden')).toBeNull();
    expect(screen.queryByText('partial minutes must stay hidden')).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/v1/meeting-sessions/processing-session');
  });

  it('moves to review only after a REVIEW snapshot for the route Session', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/meeting-sessions/review-session')) return response({ data: {
        sessionId: 'review-session', version: 4, status: 'REVIEW', meeting: { participantIds: [] },
        processing: { stage: 'DRAFT_READY', progressPercent: 100 }, speakers: [], allowedActions: [],
      } });
      if (path.endsWith('/participants')) return response({ data: { items: [] } });
      if (path.endsWith('/app-config')) return response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } });
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/review-session/processing']}><App /><CurrentPath /></MemoryRouter>);
    await waitFor(() => expect(screen.getByTestId('current-path').textContent).toBe('/meetings/review-session/review'));
    expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions/review-session'))).toHaveLength(2);
  });

  it('blocks review navigation when API-010 returns a different Session ID', async () => {
    const fetchMock = vi.fn(async () => response({ data: {
      sessionId: 'different-session', version: 1, status: 'REVIEW',
      processing: { stage: 'DRAFT_READY', progressPercent: 100 }, allowedActions: [],
    } }));
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/expected-session/processing']}><App /><CurrentPath /></MemoryRouter>);
    expect((await screen.findByRole('alert')).textContent).toContain('회의 처리 정보를 확인할 수 없습니다');
    expect(screen.getByTestId('current-path').textContent).toBe('/meetings/expected-session/processing');
  });

  it('shows stage-safe failure without recovery actions, expiry, or raw provider details', async () => {
    const fetchMock = vi.fn(async () => response({ data: {
      sessionId: 'failed-session', version: 2, status: 'PROCESSING_FAILED',
      processing: { stage: 'TRANSCRIPTION', errorCode: 'PRIVATE_PROVIDER_SECRET' }, allowedActions: [], audioExpiresAt: null,
    } }));
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/failed-session/processing']}><App /></MemoryRouter>);
    expect((await screen.findByRole('alert')).textContent).toContain('음성을 변환하는 중 문제가 발생했습니다.');
    expect(screen.queryByText('PRIVATE_PROVIDER_SECRET')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('shows a safe new-meeting path for SESSION_NOT_FOUND without creating a session', async () => {
    const fetchMock = vi.fn(async () => response({ error: { code: 'SESSION_NOT_FOUND' } }, 404));
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/gone-session/processing']}><App /></MemoryRouter>);
    expect((await screen.findByRole('alert')).textContent).toContain('회의를 찾을 수 없습니다');
    expect(screen.getByRole('link', { name: '새 회의 시작' }).getAttribute('href')).toBe('/meetings/new');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('treats DIARIZATION at 100 percent as the current minutes-generation stage', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ data: {
      sessionId: 'minutes-session', version: 2, status: 'PROCESSING',
      processing: { stage: 'DIARIZATION', progressPercent: 100 }, allowedActions: [],
    } })));
    render(<MemoryRouter initialEntries={['/meetings/minutes-session/processing']}><App /></MemoryRouter>);
    const minutesStep = await screen.findByText('회의록 생성');
    expect(minutesStep.closest('li')?.getAttribute('aria-current')).toBe('step');
    expect(screen.getByText('회의록 생성 단계 처리 중입니다.')).toBeTruthy();
  });

  it('does not invent progress for an out-of-range percentage', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ data: {
      sessionId: 'invalid-progress', version: 2, status: 'PROCESSING',
      processing: { stage: 'TRANSCRIPTION', progressPercent: 140 }, allowedActions: [],
    } })));
    render(<MemoryRouter initialEntries={['/meetings/invalid-progress/processing']}><App /></MemoryRouter>);
    expect(await screen.findByText('음성 인식 단계 처리 중입니다.')).toBeTruthy();
    expect(screen.queryByRole('progressbar', { name: '처리 진행률' })).toBeNull();
  });

  it('does not invent progress when the API-010 percentage is absent', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ data: {
      sessionId: 'missing-progress', version: 2, status: 'PROCESSING',
      processing: { stage: 'TRANSCRIPTION' }, allowedActions: [],
    } })));
    render(<MemoryRouter initialEntries={['/meetings/missing-progress/processing']}><App /></MemoryRouter>);
    expect(await screen.findByText('음성 인식 단계 처리 중입니다.')).toBeTruthy();
    expect(screen.queryByRole('progressbar', { name: '처리 진행률' })).toBeNull();
  });

  it('treats REVIEW with a DRAFT_READY stage as completed and navigates to review', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/meeting-sessions/draft-ready')) return response({ data: {
        sessionId: 'draft-ready', version: 2, status: 'REVIEW', meeting: { participantIds: [] },
        processing: { stage: 'DRAFT_READY', progressPercent: 100 }, speakers: [], allowedActions: [],
      } });
      if (path.endsWith('/participants')) return response({ data: { items: [] } });
      if (path.endsWith('/app-config')) return response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } });
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/draft-ready/processing']}><App /><CurrentPath /></MemoryRouter>);
    await waitFor(() => expect(screen.getByTestId('current-path').textContent).toBe('/meetings/draft-ready/review'));
    expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/meeting-sessions/draft-ready'))).toHaveLength(2);
  });

  it('aborts the active API-010 request when leaving the route', async () => {
    let requestSignal: AbortSignal | undefined;
    const fetchMock = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      requestSignal = init?.signal ?? undefined;
      return new Promise<Response>(() => undefined);
    });
    vi.stubGlobal('fetch', fetchMock);
    const view = render(<MemoryRouter initialEntries={['/meetings/leaving-session/processing']}><App /></MemoryRouter>);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    view.unmount();
    expect(requestSignal?.aborted).toBe(true);
  });
});

describe('Review speaker selection', () => {
  it('ignores a late Session B retry after Session C has loaded', async () => {
    let sessionBCalls = 0;
    let resolveSessionBRetry: ((result: Response) => void) | undefined;
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/meeting-sessions/session-b')) {
        sessionBCalls += 1;
        if (sessionBCalls === 1) return Promise.resolve(response({ error: { code: 'SESSION_UNAVAILABLE' } }, 503));
        return new Promise<Response>((resolve) => { resolveSessionBRetry = resolve; });
      }
      if (path.endsWith('/meeting-sessions/session-c')) return Promise.resolve(response({ data: {
        sessionId: 'session-c', version: 1, status: 'REVIEW', meeting: { participantIds: ['p3'] },
        processing: { stage: 'DRAFT_READY', progressPercent: 100 },
        speakers: [{ speakerId: 'speaker-c', label: 'Speaker C', participantId: 'p3' }],
      } }));
      if (path.endsWith('/participants')) return Promise.resolve(response({ data: { items: [
        { id: 'p1', name: 'Ada Lovelace', email: 'ada.private@example.com' },
        { id: 'p3', name: 'Grace Hopper', email: 'grace.private@example.com' },
      ] } }));
      if (path.endsWith('/app-config')) return Promise.resolve(response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } }));
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/session-b/review']}><App /><NavigateToReviewSession sessionId="session-c" /></MemoryRouter>);

    expect((await screen.findByRole('alert')).textContent).toContain('회의 화자 정보를 불러오지 못했습니다');
    fireEvent.click(screen.getByRole('button', { name: '회의 정보 다시 불러오기' }));
    await waitFor(() => expect(resolveSessionBRetry).toBeDefined());
    fireEvent.click(screen.getByRole('button', { name: 'Open another Review session' }));
    const sessionCSelect = await screen.findByRole('combobox', { name: 'Speaker C 참가자' });
    expect((sessionCSelect as HTMLSelectElement).value).toBe('p3');

    await act(async () => {
      resolveSessionBRetry?.(response({ data: {
        sessionId: 'session-b', version: 2, status: 'REVIEW', meeting: { participantIds: ['p1'] },
        processing: { stage: 'DRAFT_READY', progressPercent: 100 },
        speakers: [{ speakerId: 'speaker-b', label: 'Speaker B', participantId: 'p1' }],
      } }));
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(screen.queryByRole('status', { name: '회의의 화자 정보를 불러오는 중입니다.' })).toBeNull();
    expect(screen.getByRole('combobox', { name: 'Speaker C 참가자' })).toBeTruthy();
    expect((screen.getByRole('combobox', { name: 'Speaker C 참가자' }) as HTMLSelectElement).value).toBe('p3');
    expect(screen.queryByRole('combobox', { name: 'Speaker B 참가자' })).toBeNull();
    expect(screen.queryByText('회의의 화자 정보를 불러오는 중입니다.')).toBeNull();
  });

  it('hides the prior Session speaker data while a new Session loads and after its request fails', async () => {
    let rejectSessionB: ((error: Error) => void) | undefined;
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/meeting-sessions/session-a')) return Promise.resolve(response({ data: {
        sessionId: 'session-a', version: 1, status: 'REVIEW', meeting: { participantIds: ['p1'] },
        processing: { stage: 'DRAFT_READY', progressPercent: 100 },
        speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p1' }],
      } }));
      if (path.endsWith('/meeting-sessions/session-b')) return new Promise<Response>((_resolve, reject) => { rejectSessionB = reject; });
      if (path.endsWith('/participants')) return Promise.resolve(response({ data: { items: [{ id: 'p1', name: 'Ada Lovelace', email: 'ada.private@example.com' }] } }));
      if (path.endsWith('/app-config')) return Promise.resolve(response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } }));
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/session-a/review']}><App /><NavigateToReviewSession sessionId="session-b" /></MemoryRouter>);

    expect(await screen.findByRole('combobox', { name: 'Speaker A 참가자' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Open another Review session' }));
    await waitFor(() => expect(rejectSessionB).toBeDefined());
    expect(await screen.findByText('회의의 화자 정보를 불러오는 중입니다.')).toBeTruthy();
    expect(screen.queryByRole('combobox', { name: 'Speaker A 참가자' })).toBeNull();

    rejectSessionB?.(new TypeError('network details must stay hidden'));
    expect((await screen.findByRole('alert')).textContent).toContain('회의 화자 정보를 불러오지 못했습니다');
    expect(screen.queryByRole('combobox', { name: 'Speaker A 참가자' })).toBeNull();
    expect(screen.queryByText('Ada Lovelace')).toBeNull();
  });

  it('renders one name-only Participant dropdown per API-010 Speaker and keeps mappings speaker-scoped', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/meeting-sessions/review-speakers')) return response({ data: {
        sessionId: 'review-speakers', version: 3, status: 'REVIEW',
        processing: { stage: 'DRAFT_READY', progressPercent: 100 },
        meeting: { participantIds: ['p1', 'p2'] },
        speakers: [
          { speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p2' },
          { speakerId: 'speaker-b', label: 'Speaker B', participantId: null },
        ],
        transcript: [
          { segmentId: 'segment-a1', speakerId: 'speaker-a', startMs: 10, endMs: 20, text: 'First sentence' },
          { segmentId: 'segment-a2', speakerId: 'speaker-a', startMs: 30, endMs: 40, text: 'Second sentence' },
        ],
        allowedActions: ['UPDATE_SPEAKER_MAPPING'],
      } });
      if (path.endsWith('/participants')) return response({ data: { items: [
        { id: 'p1', name: 'Ada Lovelace', email: 'ada.private@example.com' },
        { id: 'p2', name: 'Grace Hopper', email: 'grace.private@example.com' },
      ] } });
      if (path.endsWith('/app-config')) return response({ data: {
        company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' },
        document: { provider: 'NOTION', configured: true, connectionVersion: 2, rootUrl: 'https://notion.example' },
        setup: { status: 'READY', required: false, canCreateMeeting: true, operationId: null },
      } });
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/review-speakers/review']}><App /></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: '회의록 검토' })).toBeTruthy();
    const selects = await screen.findAllByRole('combobox');
    expect(selects).toHaveLength(2);
    expect((screen.getByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement).value).toBe('p2');
    expect((screen.getByRole('combobox', { name: 'Speaker B 참가자' }) as HTMLSelectElement).value).toBe('');
    expect(within(selects[0]).getByRole('option', { name: 'Grace Hopper' })).toBeTruthy();
    expect(screen.queryByText(/private@example\.com/)).toBeNull();
    fireEvent.change(screen.getByRole('combobox', { name: 'Speaker A 참가자' }), { target: { value: 'p1' } });
    expect((screen.getByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement).value).toBe('p1');
    expect((screen.getByRole('combobox', { name: 'Speaker B 참가자' }) as HTMLSelectElement).value).toBe('');
    expect(screen.queryByText(/저장 완료|저장되었습니다/)).toBeNull();
    expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/speaker-mappings'))).toHaveLength(0);
  });

  it('preserves the speaker draft during roster reload failure and successful retry', async () => {
    let rosterCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/meeting-sessions/roster-retry')) return response({ data: {
        sessionId: 'roster-retry', version: 1, status: 'REVIEW', meeting: { participantIds: ['p1', 'p2'] },
        processing: { stage: 'DRAFT_READY', progressPercent: 100 },
        speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p2' }],
      } });
      if (path.endsWith('/participants')) {
        rosterCalls += 1;
        if (rosterCalls === 2) throw new TypeError('network detail must remain hidden');
        return response({ data: { items: [
          { id: 'p1', name: 'Ada Lovelace', email: 'ada.private@example.com' },
          ...(rosterCalls === 3 ? [] : [{ id: 'p2', name: 'Grace Hopper', email: 'grace.private@example.com' }]),
        ] } });
      }
      if (path.endsWith('/app-config')) return response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } });
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/roster-retry/review']}><App /></MemoryRouter>);
    const select = await screen.findByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: 'p1' } });
    fireEvent.click(screen.getByRole('button', { name: '참석자 목록 새로고침' }));
    expect((await screen.findByRole('alert')).textContent).toContain('기존 선택은 유지됩니다');
    expect((screen.getByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement).value).toBe('p1');
    fireEvent.click(screen.getByRole('button', { name: '참석자 목록 다시 불러오기' }));
    await waitFor(() => expect((screen.getByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement).value).toBe('p1'));
    expect(screen.queryByText(/ada\.private@example\.com/)).toBeNull();
  });

  it('shows Settings without selecting a provider when App Config has no document provider', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/meeting-sessions/no-provider')) return response({ data: {
        sessionId: 'no-provider', version: 1, status: 'REVIEW', meeting: { participantIds: ['p1'] },
        processing: { stage: 'DRAFT_READY', progressPercent: 100 }, speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: null }],
      } });
      if (path.endsWith('/participants')) return response({ data: { items: [{ id: 'p1', name: 'Ada Lovelace', email: 'ada.private@example.com' }] } });
      if (path.endsWith('/app-config')) return response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: null, configured: false, connectionVersion: 0, rootUrl: null } } });
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/no-provider/review']}><App /></MemoryRouter>);
    expect((await screen.findByRole('link', { name: '문서 설정 열기' })).getAttribute('href')).toBe('/settings');
    expect(screen.queryByRole('combobox', { name: 'Speaker A 참가자' })).toBeNull();
    expect(screen.getByText(/문서 서비스를 연결하면/)).toBeTruthy();
    expect(screen.queryByText(/CONFLUENCE|NOTION/)).toBeNull();
  });

  it('shows the Settings route when a provider exists but is not configured', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/meeting-sessions/provider-unconfigured')) return response({ data: {
        sessionId: 'provider-unconfigured', version: 1, status: 'REVIEW', meeting: { participantIds: ['p1'] },
        processing: { stage: 'DRAFT_READY', progressPercent: 100 }, speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: null }],
      } });
      if (path.endsWith('/participants')) return response({ data: { items: [{ id: 'p1', name: 'Ada Lovelace', email: 'ada.private@example.com' }] } });
      if (path.endsWith('/app-config')) return response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: false, connectionVersion: 1, rootUrl: 'https://notion.example' } } });
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/provider-unconfigured/review']}><App /></MemoryRouter>);
    expect((await screen.findByRole('link', { name: '문서 설정 열기' })).getAttribute('href')).toBe('/settings');
    expect(screen.queryByRole('combobox')).toBeNull();
    expect(screen.queryByText('NOTION')).toBeNull();
    expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/speaker-mappings'))).toHaveLength(0);
  });

  it('offers Participants management for an empty roster and retains the existing speaker mapping', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/meeting-sessions/empty-roster')) return response({ data: {
        sessionId: 'empty-roster', version: 1, status: 'REVIEW', meeting: { participantIds: ['p2'] },
        processing: { stage: 'DRAFT_READY', progressPercent: 100 }, speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p2' }],
      } });
      if (path.endsWith('/participants')) return response({ data: { items: [] } });
      if (path.endsWith('/app-config')) return response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } });
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/empty-roster/review']}><App /></MemoryRouter>);
    expect(await screen.findByRole('link', { name: '참석자 관리' })).toBeTruthy();
    expect((screen.getByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement).value).toBe('p2');
  });

  it('keeps speaker draft in the App owner while Settings is open and after returning to Review', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/meeting-sessions/return-draft')) return response({ data: {
        sessionId: 'return-draft', version: 1, status: 'REVIEW', meeting: { participantIds: ['p1', 'p2'] },
        processing: { stage: 'DRAFT_READY', progressPercent: 100 }, speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p2' }],
      } });
      if (path.endsWith('/participants')) return response({ data: { items: [
        { id: 'p1', name: 'Ada Lovelace', email: 'ada@example.com' }, { id: 'p2', name: 'Grace Hopper', email: 'grace@example.com' },
      ] } });
      if (path.endsWith('/app-config')) return response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } });
      if (path.endsWith('/document-setup')) return readySetupResponse();
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/return-draft/review']}><App /><ReturnToReview sessionId="return-draft" /><CurrentPath /></MemoryRouter>);
    const speakerSelect = await screen.findByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement;
    fireEvent.change(speakerSelect, { target: { value: 'p1' } });
    fireEvent.click(within(screen.getByRole('navigation', { name: '주요 메뉴' })).getByRole('link', { name: '설정' }));
    await screen.findByRole('heading', { name: '설정' });
    expect(screen.getByTestId('current-path').textContent).toBe('/settings');
    fireEvent.click(screen.getByRole('button', { name: 'Review로 돌아가기' }));
    await waitFor(() => expect((screen.getByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement).value).toBe('p1'));
  });

  it('saves a dirty full speaker mapping with the API-010 version and projects it to every matching transcript segment', async () => {
    const calls: Array<{ path: string; method: string; headers: Headers; body?: string }> = [];
    let putCount = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      const method = init?.method ?? 'GET';
      calls.push({ path: url.pathname, method, headers: new Headers(init?.headers), body: typeof init?.body === 'string' ? init.body : undefined });
      if (url.pathname.endsWith('/meeting-sessions/mapping-save/speaker-mappings')) {
        putCount += 1;
        const mappings = JSON.parse(String(init?.body)).mappings as Array<{ speakerId: string; participantId: string | null }>;
        return response({ data: {
          version: 7 + putCount,
          mappings: mappings.map((mapping) => ({ ...mapping, participantName: participants.find(({ id }) => id === mapping.participantId)?.name ?? null })),
        } });
      }
      if (url.pathname.endsWith('/meeting-sessions/mapping-save')) return response({ data: {
        sessionId: 'mapping-save', version: 7, status: 'REVIEW',
        meeting: { participantIds: ['p1', 'p2'] },
        processing: { stage: 'DRAFT_READY', progressPercent: 100 },
        speakers: [
          { speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p2' },
          { speakerId: 'speaker-b', label: 'Speaker B', participantId: 'p2' },
        ],
        transcript: [
          { segmentId: 'segment-a1', speakerId: 'speaker-a', startMs: 100, endMs: 250, text: 'First sentence' },
          { segmentId: 'segment-a2', speakerId: 'speaker-a', startMs: 300, endMs: 450, text: 'Second sentence' },
          { segmentId: 'segment-b1', speakerId: 'speaker-b', startMs: 500, endMs: 650, text: 'Third sentence' },
        ],
        allowedActions: ['UPDATE_SPEAKER_MAPPING'],
      } });
      if (url.pathname.endsWith('/participants')) return response({ data: { items: participants } });
      if (url.pathname.endsWith('/app-config')) return response({ data: {
        company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' },
        document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' },
      } });
      return Promise.reject(new Error(`Unexpected request: ${url.pathname}`));
    });
    installFetchMock(fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/mapping-save/review']}><App /></MemoryRouter>);

    const firstSpeaker = await screen.findByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement;
    fireEvent.change(firstSpeaker, { target: { value: 'p1' } });
    fireEvent.click(screen.getByRole('button', { name: '매핑 저장' }));

    await waitFor(() => expect(calls.filter(({ method, path }) => method === 'PUT' && path.endsWith('/speaker-mappings'))).toHaveLength(1));
    const put = calls.find(({ method }) => method === 'PUT');
    expect(put?.headers.get('If-Match')).toBe('"7"');
    expect(JSON.parse(put?.body ?? '{}')).toEqual({ mappings: [
      { speakerId: 'speaker-a', participantId: 'p1' },
      { speakerId: 'speaker-b', participantId: 'p2' },
    ] });
    const transcript = within(screen.getByRole('region', { name: '화자 매핑 미리보기' }));
    const firstSegment = transcript.getByText('First sentence').closest('p');
    const secondSegment = transcript.getByText('Second sentence').closest('p');
    expect(firstSegment?.getAttribute('data-segment-id')).toBe('segment-a1');
    expect(secondSegment?.getAttribute('data-segment-id')).toBe('segment-a2');
    expect(firstSegment?.getAttribute('data-start-ms')).toBe('100');
    expect(firstSegment?.getAttribute('data-end-ms')).toBe('250');
    expect(firstSegment?.getAttribute('data-speaker-name')).toBe('Ada Lovelace');
    expect(secondSegment?.getAttribute('data-speaker-name')).toBe('Ada Lovelace');
    fireEvent.change(screen.getByRole('combobox', { name: 'Speaker B 참가자' }), { target: { value: 'p1' } });
    fireEvent.click(screen.getByRole('button', { name: '매핑 저장' }));
    await waitFor(() => expect(calls.filter(({ method }) => method === 'PUT')).toHaveLength(2));
    const secondPut = calls.filter(({ method }) => method === 'PUT')[1];
    expect(secondPut?.headers.get('If-Match')).toBe('"8"');
    expect(JSON.parse(secondPut?.body ?? '{}')).toEqual({ mappings: [
      { speakerId: 'speaker-a', participantId: 'p1' },
      { speakerId: 'speaker-b', participantId: 'p1' },
    ] });
  });

  it('does not PUT a clean draft and suppresses duplicate clicks while one full replacement is in flight', async () => {
    let resolvePut: ((value: Response) => void) | undefined;
    const calls: Array<{ path: string; method: string; headers: Headers; body?: string }> = [];
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      const method = init?.method ?? 'GET';
      calls.push({ path: url.pathname, method, headers: new Headers(init?.headers), body: typeof init?.body === 'string' ? init.body : undefined });
      if (method === 'PUT') return new Promise<Response>((resolve) => { resolvePut = resolve; });
      if (url.pathname.endsWith('/meeting-sessions/deduplicate')) return Promise.resolve(response({ data: {
        sessionId: 'deduplicate', version: 2, status: 'REVIEW', meeting: { participantIds: ['p1', 'p2'] },
        speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p2' }],
        transcript: [], allowedActions: ['UPDATE_SPEAKER_MAPPING'],
      } }));
      if (url.pathname.endsWith('/participants')) return Promise.resolve(response({ data: { items: participants } }));
      if (url.pathname.endsWith('/app-config')) return Promise.resolve(response({ data: {
        company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' },
      } }));
      return Promise.reject(new Error(`Unexpected request: ${url.pathname}`));
    });
    installFetchMock(fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/deduplicate/review']}><App /></MemoryRouter>);

    const save = await screen.findByRole('button', { name: '매핑 저장' }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    fireEvent.click(save);
    expect(calls.filter(({ method }) => method === 'PUT')).toHaveLength(0);

    fireEvent.change(screen.getByRole('combobox', { name: 'Speaker A 참가자' }), { target: { value: 'p1' } });
    fireEvent.click(save);
    fireEvent.click(save);
    await waitFor(() => expect(calls.filter(({ method }) => method === 'PUT')).toHaveLength(1));
    expect(save.disabled).toBe(true);
    resolvePut?.(response({ data: { version: 3, mappings: [{ speakerId: 'speaker-a', participantId: 'p1', participantName: 'Ada Lovelace' }] } }));
    expect(await screen.findByText('매핑이 저장되었습니다.')).toBeTruthy();
    expect(calls.filter(({ method }) => method === 'PUT')).toHaveLength(1);
  });

  it('disables speaker edits while a mapping save is in flight', async () => {
    let resolvePut: ((value: Response) => void) | undefined;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      if (init?.method === 'PUT') return new Promise<Response>((resolve) => { resolvePut = resolve; });
      if (url.pathname.endsWith('/meeting-sessions/locked-draft')) return Promise.resolve(response({ data: {
        sessionId: 'locked-draft', version: 3, status: 'REVIEW', meeting: { participantIds: ['p1', 'p2'] },
        speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p2' }], transcript: [], allowedActions: ['UPDATE_SPEAKER_MAPPING'],
      } }));
      if (url.pathname.endsWith('/participants')) return Promise.resolve(response({ data: { items: participants } }));
      if (url.pathname.endsWith('/app-config')) return Promise.resolve(response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } }));
      return Promise.reject(new Error(`Unexpected request: ${url.pathname}`));
    });
    installFetchMock(fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/locked-draft/review']}><App /></MemoryRouter>);
    const select = await screen.findByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: 'p1' } });
    fireEvent.click(screen.getByRole('button', { name: '매핑 저장' }));
    await waitFor(() => expect(resolvePut).toBeDefined());
    expect(select.disabled).toBe(true);
    resolvePut?.(response({ data: { version: 4, mappings: [{ speakerId: 'speaker-a', participantId: 'p1', participantName: 'Ada Lovelace' }] } }));
    expect(await screen.findByText('매핑이 저장되었습니다.')).toBeTruthy();
    expect(select.value).toBe('p1');
  });

  it('ignores a pending Session A mapping response after navigating to Session B', async () => {
    let resolveAPut: ((value: Response) => void) | undefined;
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = new URL(String(input));
      if (url.pathname.endsWith('/speaker-mappings') && url.pathname.includes('/session-a/')) return new Promise<Response>((resolve) => { resolveAPut = resolve; });
      const sessionId = url.pathname.endsWith('/session-a') ? 'session-a' : url.pathname.endsWith('/session-b') ? 'session-b' : null;
      if (sessionId) return Promise.resolve(response({ data: {
        sessionId, version: 5, status: 'REVIEW', meeting: { participantIds: ['p1', 'p2'] },
        speakers: [{ speakerId: `speaker-${sessionId}`, label: sessionId === 'session-a' ? 'Speaker A' : 'Speaker B', participantId: 'p2' }],
        transcript: [], allowedActions: ['UPDATE_SPEAKER_MAPPING'],
      } }));
      if (url.pathname.endsWith('/participants')) return Promise.resolve(response({ data: { items: participants } }));
      if (url.pathname.endsWith('/app-config')) return Promise.resolve(response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } }));
      return Promise.reject(new Error(`Unexpected request: ${url.pathname}`));
    });
    installFetchMock(fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/session-a/review']}><App /><NavigateToReviewSession sessionId="session-b" /></MemoryRouter>);
    const aSelect = await screen.findByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement;
    fireEvent.change(aSelect, { target: { value: 'p1' } });
    fireEvent.click(screen.getByRole('button', { name: '매핑 저장' }));
    await waitFor(() => expect(resolveAPut).toBeDefined());
    fireEvent.click(screen.getByRole('button', { name: 'Open another Review session' }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith('/meeting-sessions/session-b'))).toBe(true));
    const bSelect = await screen.findByRole('combobox', { name: 'Speaker B 참가자' }) as HTMLSelectElement;
    await act(async () => {
      resolveAPut?.(response({ data: { version: 6, mappings: [{ speakerId: 'speaker-session-a', participantId: 'p1', participantName: 'Ada Lovelace' }] } }));
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(screen.getByRole('combobox', { name: 'Speaker B 참가자' })).toBe(bSelect);
    expect(screen.queryByRole('combobox', { name: 'Speaker A 참가자' })).toBeNull();
  });

  it('reloads API-010 after success and uses its refreshed allowedActions without reverting the saved mapping', async () => {
    let sessionReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      if (init?.method === 'PUT') return response({ data: { version: 4, mappings: [{ speakerId: 'speaker-a', participantId: 'p1', participantName: 'Ada Lovelace' }] } });
      if (url.pathname.endsWith('/meeting-sessions/post-save-refresh')) {
        sessionReads += 1;
        const updated = sessionReads > 1;
        return response({ data: {
          sessionId: 'post-save-refresh', version: updated ? 4 : 3, status: 'REVIEW', meeting: { participantIds: ['p1', 'p2'] },
          speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: updated ? 'p1' : 'p2' }],
          transcript: [], allowedActions: updated ? [] : ['UPDATE_SPEAKER_MAPPING'],
        } });
      }
      if (url.pathname.endsWith('/participants')) return response({ data: { items: participants } });
      if (url.pathname.endsWith('/app-config')) return response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } });
      return Promise.reject(new Error(`Unexpected request: ${url.pathname}`));
    });
    installFetchMock(fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/post-save-refresh/review']}><App /></MemoryRouter>);
    const select = await screen.findByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: 'p1' } });
    fireEvent.click(screen.getByRole('button', { name: '매핑 저장' }));
    expect(await screen.findByText('매핑이 저장되었습니다.')).toBeTruthy();
    await waitFor(() => expect(sessionReads).toBe(2));
    expect(screen.queryByRole('button', { name: '매핑 저장' })).toBeNull();
    expect(select.value).toBe('p1');
  });

  it.each([400, 502])('keeps the committed mapping and unsaved draft after known HTTP %i', async (status) => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      if (init?.method === 'PUT') return response({ error: { code: 'SAFE_ERROR' } }, status);
      if (url.pathname.endsWith('/meeting-sessions/known-error')) return response({ data: {
        sessionId: 'known-error', version: 4, status: 'REVIEW', meeting: { participantIds: ['p1', 'p2'] },
        speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p2' }], transcript: [], allowedActions: ['UPDATE_SPEAKER_MAPPING'],
      } });
      if (url.pathname.endsWith('/participants')) return response({ data: { items: participants } });
      if (url.pathname.endsWith('/app-config')) return response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } });
      return Promise.reject(new Error(`Unexpected request: ${url.pathname}`));
    });
    installFetchMock(fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/known-error/review']}><App /></MemoryRouter>);
    const select = await screen.findByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: 'p1' } });
    fireEvent.click(screen.getByRole('button', { name: '매핑 저장' }));
    expect(await screen.findByText('매핑을 저장하지 못했습니다. 기존 저장 내용과 변경 내용은 유지됩니다. 다시 시도해 주세요.')).toBeTruthy();
    expect(select.value).toBe('p1');
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);
    expect(fetchMock.mock.calls.filter(([input]) => String(input).endsWith('/meeting-sessions/known-error'))).toHaveLength(1);
  });

  it('refreshes API-010 once after 412 and preserves the draft without retrying the PUT', async () => {
    let sessionReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      if (init?.method === 'PUT') return response({ error: { code: 'SESSION_VERSION_CONFLICT' } }, 412);
      if (url.pathname.endsWith('/meeting-sessions/conflict')) {
        sessionReads += 1;
        return response({ data: {
          sessionId: 'conflict', version: sessionReads === 1 ? 4 : 5, status: 'REVIEW', meeting: { participantIds: ['p1', 'p2'] },
          speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p2' }], transcript: [], allowedActions: ['UPDATE_SPEAKER_MAPPING'],
        } });
      }
      if (url.pathname.endsWith('/participants')) return response({ data: { items: participants } });
      if (url.pathname.endsWith('/app-config')) return response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } });
      return Promise.reject(new Error(`Unexpected request: ${url.pathname}`));
    });
    installFetchMock(fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/conflict/review']}><App /></MemoryRouter>);
    const select = await screen.findByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: 'p1' } });
    fireEvent.click(screen.getByRole('button', { name: '매핑 저장' }));
    expect(await screen.findByText('최신 회의 정보를 불러왔습니다. 변경 내용은 유지했으며 자동 저장하지 않았습니다. 확인 후 다시 저장해 주세요.')).toBeTruthy();
    expect(select.value).toBe('p1');
    expect(sessionReads).toBe(2);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);
  });

  it('reconciles an ambiguous PUT as success only when API-010 matches the full attempted mapping', async () => {
    let sessionReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      if (init?.method === 'PUT') throw new TypeError('network details are not rendered');
      if (url.pathname.endsWith('/meeting-sessions/ambiguous')) {
        sessionReads += 1;
        return response({ data: {
          sessionId: 'ambiguous', version: sessionReads === 1 ? 6 : 7, status: 'REVIEW', meeting: { participantIds: ['p1', 'p2'] },
          speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: sessionReads === 1 ? 'p2' : 'p1' }],
          transcript: [], allowedActions: ['UPDATE_SPEAKER_MAPPING'],
        } });
      }
      if (url.pathname.endsWith('/participants')) return response({ data: { items: participants } });
      if (url.pathname.endsWith('/app-config')) return response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } });
      return Promise.reject(new Error(`Unexpected request: ${url.pathname}`));
    });
    installFetchMock(fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/ambiguous/review']}><App /></MemoryRouter>);
    const select = await screen.findByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: 'p1' } });
    fireEvent.click(screen.getByRole('button', { name: '매핑 저장' }));
    expect(await screen.findByText('매핑 저장이 확인되었습니다.')).toBeTruthy();
    expect(select.value).toBe('p1');
    expect(sessionReads).toBe(2);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);
    expect((screen.getByRole('button', { name: '매핑 저장' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('keeps the submitted draft when ambiguous API-010 reconciliation shows the server kept the old mapping', async () => {
    let sessionReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      if (init?.method === 'PUT') throw new TypeError('network details hidden');
      if (url.pathname.endsWith('/meeting-sessions/ambiguous-mismatch')) {
        sessionReads += 1;
        return response({ data: {
          sessionId: 'ambiguous-mismatch', version: sessionReads === 1 ? 6 : 7, status: 'REVIEW', meeting: { participantIds: ['p1', 'p2'] },
          speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p2' }], transcript: [], allowedActions: ['UPDATE_SPEAKER_MAPPING'],
        } });
      }
      if (url.pathname.endsWith('/participants')) return response({ data: { items: participants } });
      if (url.pathname.endsWith('/app-config')) return response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } });
      return Promise.reject(new Error(`Unexpected request: ${url.pathname}`));
    });
    installFetchMock(fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/ambiguous-mismatch/review']}><App /></MemoryRouter>);
    const select = await screen.findByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: 'p1' } });
    fireEvent.click(screen.getByRole('button', { name: '매핑 저장' }));
    expect(await screen.findByText('서버의 최신 매핑을 불러왔습니다. 변경 내용은 유지했습니다. 확인 후 다시 저장해 주세요.')).toBeTruthy();
    expect(select.value).toBe('p1');
    expect(sessionReads).toBe(2);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);
    expect((screen.getByRole('button', { name: '매핑 저장' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('preserves both snapshots when ambiguous PUT reconciliation fails and retrying status never repeats PUT', async () => {
    let sessionReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      if (init?.method === 'PUT') throw new TypeError('network detail');
      if (url.pathname.endsWith('/meeting-sessions/recovery')) {
        sessionReads += 1;
        if (sessionReads === 2) throw new TypeError('status unavailable');
        return response({ data: {
          sessionId: 'recovery', version: sessionReads === 1 ? 2 : 3, status: 'REVIEW', meeting: { participantIds: ['p1', 'p2'] },
          speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p2' }], transcript: [], allowedActions: ['UPDATE_SPEAKER_MAPPING'],
        } });
      }
      if (url.pathname.endsWith('/participants')) return response({ data: { items: participants } });
      if (url.pathname.endsWith('/app-config')) return response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } });
      return Promise.reject(new Error(`Unexpected request: ${url.pathname}`));
    });
    installFetchMock(fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/recovery/review']}><App /></MemoryRouter>);
    const select = await screen.findByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: 'p1' } });
    fireEvent.click(screen.getByRole('button', { name: '매핑 저장' }));
    expect(await screen.findByText('저장 결과를 확인하지 못했습니다. 변경 내용은 유지됩니다. 최신 상태를 다시 확인해 주세요.')).toBeTruthy();
    expect(select.value).toBe('p1');
    fireEvent.click(screen.getByRole('button', { name: '저장 상태 다시 확인' }));
    expect(await screen.findByText('서버의 최신 매핑을 불러왔습니다. 변경 내용은 유지했습니다. 확인 후 다시 저장해 주세요.')).toBeTruthy();
    expect(select.value).toBe('p1');
    expect(sessionReads).toBe(3);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);
  });

  it('ignores a late API-010 snapshot older than a confirmed API-011 mapping', async () => {
    let sessionReads = 0;
    let resolveLateRead: ((value: Response) => void) | undefined;
    let putCalls = 0;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      if (init?.method === 'PUT') {
        putCalls += 1;
        if (putCalls === 1) return Promise.reject(new TypeError('ambiguous first attempt'));
        return Promise.resolve(response({ data: { version: 8, mappings: [{ speakerId: 'speaker-a', participantId: 'p1', participantName: 'Ada Lovelace' }] } }));
      }
      if (url.pathname.endsWith('/meeting-sessions/stale-read')) {
        sessionReads += 1;
        if (sessionReads === 1) return Promise.resolve(response({ data: {
          sessionId: 'stale-read', version: 6, status: 'REVIEW', meeting: { participantIds: ['p1', 'p2'] },
          speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p2' }], transcript: [], allowedActions: ['UPDATE_SPEAKER_MAPPING'],
        } }));
        if (sessionReads === 2) return Promise.reject(new TypeError('first reconciliation unavailable'));
        return new Promise<Response>((resolve) => { resolveLateRead = resolve; });
      }
      if (url.pathname.endsWith('/participants')) return Promise.resolve(response({ data: { items: participants } }));
      if (url.pathname.endsWith('/app-config')) return Promise.resolve(response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } }));
      return Promise.reject(new Error(`Unexpected request: ${url.pathname}`));
    });
    installFetchMock(fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/stale-read/review']}><App /></MemoryRouter>);
    const select = await screen.findByRole('combobox', { name: 'Speaker A 참가자' }) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: 'p1' } });
    fireEvent.click(screen.getByRole('button', { name: '매핑 저장' }));
    await screen.findByRole('button', { name: '저장 상태 다시 확인' });
    fireEvent.click(screen.getByRole('button', { name: '저장 상태 다시 확인' }));
    await waitFor(() => expect(resolveLateRead).toBeDefined());

    fireEvent.click(screen.getByRole('button', { name: '매핑 저장' }));
    expect(await screen.findByText('매핑이 저장되었습니다.')).toBeTruthy();
    await act(async () => {
      resolveLateRead?.(response({ data: {
        sessionId: 'stale-read', version: 7, status: 'REVIEW', meeting: { participantIds: ['p1', 'p2'] },
        speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p2' }], transcript: [], allowedActions: ['UPDATE_SPEAKER_MAPPING'],
      } }));
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(select.value).toBe('p1');
    expect(screen.getByText('매핑이 저장되었습니다.')).toBeTruthy();
    expect(putCalls).toBe(2);
  });

  it('blocks API-011 when the latest API-010 snapshot does not allow mapping updates', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input));
      if (url.pathname.endsWith('/meeting-sessions/action-denied')) return response({ data: {
        sessionId: 'action-denied', version: 2, status: 'REVIEW', meeting: { participantIds: ['p1', 'p2'] },
        speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p2' }], transcript: [], allowedActions: [],
      } });
      if (url.pathname.endsWith('/participants')) return response({ data: { items: participants } });
      if (url.pathname.endsWith('/app-config')) return response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' } } });
      return Promise.reject(new Error(`Unexpected request: ${url.pathname}`));
    });
    installFetchMock(fetchMock);
    render(<MemoryRouter initialEntries={['/meetings/action-denied/review']}><App /></MemoryRouter>);
    expect(await screen.findByRole('combobox', { name: 'Speaker A 참가자' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '매핑 저장' })).toBeNull();
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/speaker-mappings'))).toHaveLength(0);
  });
});

import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NewMeetingPage } from './NewMeetingPage';
import type { MeetingSessionAttempt } from './newMeetingApi';
import { MeetingParticipantSelector } from './MeetingParticipantSelector';

const templates = [
  { id: 'default.md', name: '기본 회의록', version: '1.0.0' },
  { id: 'project.md', name: '프로젝트 회의', version: '1.0.0' },
];
const participants = [
  { id: 'p1', name: 'Ada Lovelace', email: 'ada@example.com' },
  { id: 'p2', name: 'Grace Hopper', email: 'grace@example.com' },
];

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function renderPage(onSubmit = vi.fn<(payload: MeetingSessionAttempt) => void>()) {
  return {
    onSubmit,
    ...render(<MemoryRouter><NewMeetingPage onSubmit={onSubmit} /></MemoryRouter>),
  };
}

function mockSuccess() {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith('/app-config')) return Promise.resolve(response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' }, setup: { status: 'READY', required: false, canCreateMeeting: true, operationId: null } } }));
    if (path.endsWith('/templates')) return Promise.resolve(response({ data: { items: templates } }));
    if (path.endsWith('/participants')) return Promise.resolve(response({ data: { items: participants } }));
    return Promise.reject(new Error(`Unexpected request: ${path}`));
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function participantSelector(isDisabled: boolean) {
  return <MeetingParticipantSelector
    participants={participants}
    selectedIds={['p1']}
    isDisabled={isDisabled}
    isReady
    pickerOpen
    onTogglePicker={() => undefined}
    onToggleParticipant={() => undefined}
  />;
}

describe('New Meeting input and selection', () => {
  it('shows selected attendees inside the combo and keeps them editable after retry', () => {
    const view = render(participantSelector(true));
    const adaCheckbox = screen.getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' });
    expect(adaCheckbox).toHaveProperty('checked', true);
    expect(adaCheckbox).toHaveProperty('disabled', true);
    expect(screen.queryByRole('list', { name: '선택한 참석자' })).toBeNull();
    expect(screen.getByRole('button', { name: '참석자 선택' })).toHaveProperty('disabled', true);

    view.rerender(participantSelector(false));
    expect(screen.getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' })).toHaveProperty('checked', true);
    expect(screen.getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' })).toHaveProperty('disabled', false);
  });

  it('loads the timezone, default template, and existing roster independently', async () => {
    const fetchMock = mockSuccess();
    renderPage();

    await screen.findByLabelText('회의 날짜');
    expect(screen.queryByText('Asia/Seoul')).toBeNull();
    expect(screen.queryByText(/회사 시간대/)).toBeNull();
    const templateSelect = screen.getByLabelText('회의록 선택') as HTMLSelectElement;
    expect(templateSelect.value).toBe('default.md');
    expect(within(templateSelect).queryByRole('option', { name: '회의록 선택' })).toBeNull();
    expect(screen.getByText('참석자')).toBeTruthy();
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls.every(([url]) => String(url).includes('/api/v1/'))).toBe(true);
  });

  it('shows independent query errors and retries without losing title or selected participants', async () => {
    let failParticipants = true;
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith('/app-config')) return Promise.resolve(response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' }, setup: { status: 'READY', required: false, canCreateMeeting: true, operationId: null } } }));
      if (path.endsWith('/templates')) return Promise.resolve(response({ data: { items: templates } }));
      if (path.endsWith('/participants')) return Promise.resolve(failParticipants
        ? response({ error: { code: 'PARTICIPANT_LIST_FAILED' } }, 502)
        : response({ data: { items: participants } }));
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    vi.stubGlobal('fetch', fetchMock);
    renderPage();
    fireEvent.change(await screen.findByLabelText('회의 제목'), { target: { value: '  계획 회의  ' } });
    expect((await screen.findByRole('alert')).textContent).toMatch(/참석자 목록/);
    expect(screen.getByRole('heading', { name: '참석자 목록을 불러오지 못했습니다.' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '참석자 목록 다시 시도' })).toBeTruthy();
    failParticipants = false;
    fireEvent.click(screen.getByRole('button', { name: '참석자 목록 다시 시도' }));
    await screen.findByRole('button', { name: '참석자 선택' });
    fireEvent.click(screen.getByRole('button', { name: '참석자 선택' }));
    expect(await screen.findByText('Ada Lovelace')).toBeTruthy();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' }));
    expect(screen.getByRole('button', { name: '참석자 선택' }).textContent).toContain('1명 선택됨');
    expect(screen.queryByRole('list', { name: '선택한 참석자' })).toBeNull();
    expect((screen.getByLabelText('회의 제목') as HTMLInputElement).value).toBe('  계획 회의  ');
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it.each([
    { path: '/app-config', retryButton: '연결 정보 다시 시도', readyText: '회의 날짜' },
    { path: '/templates', retryButton: 'Template 목록 다시 시도', readyText: '기본 회의록' },
  ])('retries $path without replacing the title draft or other query data', async ({ path, retryButton, readyText }) => {
    let shouldFail = true;
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const requestPath = String(input);
      if (requestPath.endsWith('/app-config')) return path === '/app-config' && shouldFail
        ? Promise.resolve(response({ error: { code: 'CONFIG_UNAVAILABLE' } }, 503))
        : Promise.resolve(response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' }, setup: { status: 'READY', required: false, canCreateMeeting: true, operationId: null } } }));
      if (requestPath.endsWith('/templates')) return path === '/templates' && shouldFail
        ? Promise.resolve(response({ error: { code: 'TEMPLATE_LIST_FAILED' } }, 502))
        : Promise.resolve(response({ data: { items: templates } }));
      return Promise.resolve(response({ data: { items: participants } }));
    });
    vi.stubGlobal('fetch', fetchMock);
    renderPage();
    fireEvent.change(await screen.findByLabelText('회의 제목'), { target: { value: 'Draft stays' } });
    expect((await screen.findByRole('alert')).textContent).toMatch(/다시 시도/);
    expect(screen.getByRole('button', { name: '참석자 선택' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '참석자 선택' }));
    fireEvent.click(within(screen.getByRole('group', { name: '참석자 목록' })).getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' }));
    expect(screen.getByRole('button', { name: '참석자 선택' }).textContent).toContain('1명 선택됨');
    expect(screen.queryByRole('list', { name: '선택한 참석자' })).toBeNull();
    shouldFail = false;
    fireEvent.click(screen.getByRole('button', { name: retryButton }));
    expect(await screen.findByText(readyText)).toBeTruthy();
    expect((screen.getByLabelText('회의 제목') as HTMLInputElement).value).toBe('Draft stays');
    expect(screen.queryByRole('list', { name: '선택한 참석자' })).toBeNull();
    expect(within(screen.getByRole('group', { name: '참석자 목록' })).getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' })).toHaveProperty('checked', true);
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('validates title, template, and at least one participant before callback', async () => {
    mockSuccess();
    const { onSubmit } = renderPage();
    await screen.findByLabelText('회의 날짜');
    fireEvent.change(screen.getByLabelText('회의 제목'), { target: { value: '   ' } });
    fireEvent.change(screen.getByLabelText('회의록 선택'), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));
    expect(screen.getByText('회의 제목을 입력해 주세요.')).toBeTruthy();
    expect(screen.getByText('회의록을 선택해 주세요.')).toBeTruthy();
    expect(screen.getByText('참석자를 한 명 이상 추가해 주세요.')).toBeTruthy();
    expect(screen.getByRole('button', { name: '참석자 선택' }).getAttribute('aria-describedby')).toBe('meeting-participants-error');
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('applies checkbox changes immediately and sends the selected roster', async () => {
    const fetchMock = mockSuccess();
    const { onSubmit } = renderPage();
    await screen.findByLabelText('회의 날짜');
    fireEvent.change(screen.getByLabelText('회의 제목'), { target: { value: '  주간 계획  ' } });
    fireEvent.change(screen.getByLabelText('회의 날짜'), { target: { value: '2026-10-20' } });
    fireEvent.click(screen.getByRole('button', { name: '참석자 선택' }));
    const picker = screen.getByRole('group', { name: '참석자 목록' });
    const adaCheckbox = within(picker).getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' });
    const graceCheckbox = within(picker).getByRole('checkbox', { name: 'Grace Hopper grace@example.com' });
    fireEvent.click(adaCheckbox);
    expect(screen.getByRole('button', { name: '참석자 선택' }).textContent).toContain('1명 선택됨');
    expect(screen.queryByRole('list', { name: '선택한 참석자' })).toBeNull();
    fireEvent.click(graceCheckbox);
    expect(screen.getByRole('button', { name: '참석자 선택' }).textContent).toContain('2명 선택됨');
    fireEvent.click(adaCheckbox);
    expect(screen.getByRole('button', { name: '참석자 선택' }).textContent).toContain('1명 선택됨');
    fireEvent.click(adaCheckbox);
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      title: '주간 계획', templateId: 'default.md', participantIds: ['p2', 'p1'], timezone: 'Asia/Seoul',
      meetingDate: '2026-10-20', documentConnectionVersion: 1,
      recoveryKey: expect.any(String), idempotencyKey: expect.any(String),
    }));
    expect(fetchMock.mock.calls.map(([url]) => new URL(String(url)).pathname)).toEqual([
      '/api/v1/app-config', '/api/v1/templates', '/api/v1/participants',
    ]);
    expect(fetchMock.mock.calls.map((call) => (call as unknown as [RequestInfo | URL, RequestInit?])[1]?.method ?? 'GET')).toEqual(['GET', 'GET', 'GET']);
  });

  it('keeps submit unavailable when template or roster is empty', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith('/app-config')) return Promise.resolve(response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' }, setup: { status: 'READY', required: false, canCreateMeeting: true, operationId: null } } }));
      if (path.endsWith('/templates')) return Promise.resolve(response({ data: { items: [] } }));
      return Promise.resolve(response({ data: { items: [] } }));
    });
    vi.stubGlobal('fetch', fetchMock);
    const { onSubmit } = renderPage();
    expect(await screen.findByText(/사용 가능한 Template이 없습니다/)).toBeTruthy();
    expect(await screen.findByText(/참석자 관리에서 목록을 준비/)).toBeTruthy();
    expect(screen.getByRole('button', { name: '회의 시작' })).toHaveProperty('disabled', true);
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });
});

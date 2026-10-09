import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NewMeetingPage, type NewMeetingPayload } from './NewMeetingPage';
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

function renderPage(onSubmit = vi.fn<(payload: NewMeetingPayload) => void>()) {
  return {
    onSubmit,
    ...render(<MemoryRouter><NewMeetingPage onSubmit={onSubmit} /></MemoryRouter>),
  };
}

function mockSuccess() {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith('/app-config')) return Promise.resolve(response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' } } }));
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
  it('locks selected-item removal and participant changes during retry, preserving selection', () => {
    const view = render(participantSelector(true));
    const removeButton = screen.getByRole('button', { name: 'Ada Lovelace 선택 해제' });
    const adaCheckbox = screen.getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' });
    const pickerToggle = screen.getByRole('button', { name: '참석자 선택 닫기' });
    expect(removeButton).toHaveProperty('disabled', true);
    expect(adaCheckbox).toHaveProperty('disabled', true);
    expect(pickerToggle).toHaveProperty('disabled', true);

    view.rerender(participantSelector(false));
    expect(screen.getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' })).toHaveProperty('checked', true);
  });

  it('loads the timezone, default template, and existing roster independently', async () => {
    const fetchMock = mockSuccess();
    renderPage();

    expect(await screen.findByText('Asia/Seoul')).toBeTruthy();
    expect((screen.getByLabelText('Template') as HTMLSelectElement).value).toBe('default.md');
    expect(screen.getByText('참석자')).toBeTruthy();
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls.every(([url]) => String(url).includes('/api/v1/'))).toBe(true);
  });

  it('shows independent query errors and retries without losing title or selected participants', async () => {
    let failParticipants = true;
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith('/app-config')) return Promise.resolve(response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' } } }));
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
    expect(screen.getByRole('button', { name: '참석자 목록 다시 시도' })).toBeTruthy();
    failParticipants = false;
    fireEvent.click(screen.getByRole('button', { name: '참석자 목록 다시 시도' }));
    await screen.findByRole('button', { name: '참석자 추가' });
    fireEvent.click(screen.getByRole('button', { name: '참석자 추가' }));
    expect(await screen.findByText('Ada Lovelace')).toBeTruthy();
    expect((screen.getByLabelText('회의 제목') as HTMLInputElement).value).toBe('  계획 회의  ');
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it.each([
    { path: '/app-config', retryButton: '회사 시간대 다시 시도', readyText: 'Asia/Seoul' },
    { path: '/templates', retryButton: 'Template 목록 다시 시도', readyText: '기본 회의록' },
  ])('retries $path without replacing the title draft or other query data', async ({ path, retryButton, readyText }) => {
    let shouldFail = true;
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const requestPath = String(input);
      if (requestPath.endsWith('/app-config')) return path === '/app-config' && shouldFail
        ? Promise.resolve(response({ error: { code: 'CONFIG_UNAVAILABLE' } }, 503))
        : Promise.resolve(response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' } } }));
      if (requestPath.endsWith('/templates')) return path === '/templates' && shouldFail
        ? Promise.resolve(response({ error: { code: 'TEMPLATE_LIST_FAILED' } }, 502))
        : Promise.resolve(response({ data: { items: templates } }));
      return Promise.resolve(response({ data: { items: participants } }));
    });
    vi.stubGlobal('fetch', fetchMock);
    renderPage();
    fireEvent.change(await screen.findByLabelText('회의 제목'), { target: { value: 'Draft stays' } });
    expect((await screen.findByRole('alert')).textContent).toMatch(/다시 시도/);
    expect(screen.getByRole('button', { name: '참석자 추가' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '참석자 추가' }));
    fireEvent.click(within(screen.getByRole('group', { name: '참석자 선택' })).getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' }));
    fireEvent.click(screen.getByRole('button', { name: '참석자 선택 닫기' }));
    shouldFail = false;
    fireEvent.click(screen.getByRole('button', { name: retryButton }));
    expect(await screen.findByText(readyText)).toBeTruthy();
    expect((screen.getByLabelText('회의 제목') as HTMLInputElement).value).toBe('Draft stays');
    expect(within(screen.getByRole('list', { name: '선택한 참석자' })).getByText('Ada Lovelace')).toBeTruthy();
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('validates title, template, and at least one participant before callback', async () => {
    mockSuccess();
    const { onSubmit } = renderPage();
    await screen.findByText('Asia/Seoul');
    fireEvent.change(screen.getByLabelText('회의 제목'), { target: { value: '   ' } });
    fireEvent.change(screen.getByLabelText('Template'), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));
    expect(screen.getByText('회의 제목을 입력해 주세요.')).toBeTruthy();
    expect(screen.getByText('Template을 선택해 주세요.')).toBeTruthy();
    expect(screen.getByText('참석자를 한 명 이상 추가해 주세요.')).toBeTruthy();
    expect(screen.getByRole('button', { name: '참석자 추가' }).getAttribute('aria-describedby')).toBe('meeting-participants-error');
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('adds and removes roster members then sends only the valid callback payload', async () => {
    const fetchMock = mockSuccess();
    const { onSubmit } = renderPage();
    await screen.findByText('Asia/Seoul');
    fireEvent.change(screen.getByLabelText('회의 제목'), { target: { value: '  주간 계획  ' } });
    fireEvent.click(screen.getByRole('button', { name: '참석자 추가' }));
    const picker = screen.getByRole('group', { name: '참석자 선택' });
    fireEvent.click(within(picker).getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' }));
    fireEvent.click(screen.getByRole('button', { name: '참석자 선택 닫기' }));
    expect(within(screen.getByRole('list', { name: '선택한 참석자' })).getByText('Ada Lovelace')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Ada Lovelace 선택 해제' }));
    fireEvent.click(screen.getByRole('button', { name: '참석자 추가' }));
    const participantPicker = within(screen.getByRole('group', { name: '참석자 선택' }));
    const adaCheckbox = participantPicker.getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' });
    const graceCheckbox = participantPicker.getByRole('checkbox', { name: 'Grace Hopper grace@example.com' });
    fireEvent.click(graceCheckbox);
    fireEvent.click(adaCheckbox);
    fireEvent.click(graceCheckbox);
    fireEvent.click(graceCheckbox);
    fireEvent.click(screen.getByRole('button', { name: '회의 시작' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit).toHaveBeenCalledWith({
      title: '주간 계획', templateId: 'default.md', participantIds: ['p1', 'p2'], timezone: 'Asia/Seoul',
    });
    expect(fetchMock.mock.calls.map(([url]) => new URL(String(url)).pathname)).toEqual([
      '/api/v1/app-config', '/api/v1/templates', '/api/v1/participants',
    ]);
    expect(fetchMock.mock.calls.map((call) => (call as unknown as [RequestInfo | URL, RequestInit?])[1]?.method ?? 'GET')).toEqual(['GET', 'GET', 'GET']);
  });

  it('keeps submit unavailable when template or roster is empty', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith('/app-config')) return Promise.resolve(response({ data: { company: { id: 'c1', name: 'Example', timezone: 'Asia/Seoul' } } }));
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

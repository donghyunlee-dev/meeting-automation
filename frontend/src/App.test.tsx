import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { App } from './App';

const participants = [
  { id: 'p1', name: 'Ada Lovelace', email: 'ada@example.com' },
  { id: 'p2', name: 'Grace Hopper', email: 'grace@navy.example' },
];

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function mockList(result: Response | Promise<Response>) {
  const fetchMock = vi.fn().mockResolvedValue(result);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function DestinationFixture() {
  const location = useLocation();
  return <p>New Meeting fixture: {location.pathname}</p>;
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
  it('renders the SCR-001 Home content and navigation without fetching recent meetings', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderHomeWithDestinationFixture();

    expect(screen.getByRole('heading', { level: 1, name: 'Meeting Automation' })).toBeTruthy();
    expect(screen.getByText('회의를 시작할까요?')).toBeTruthy();
    expect(screen.getByText('회의 내용을 녹음하고 자동으로 정리합니다.')).toBeTruthy();
    expect(screen.getByRole('link', { name: '새 회의 시작' })).toBeTruthy();
    const navigation = within(screen.getByRole('navigation', { name: '주요 메뉴' }));
    expect(navigation.getAllByRole('link').map((link) => link.getAttribute('aria-label'))).toEqual(['홈', '회의록', '설정']);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('navigates the Home CTA to the New Meeting route fixture', async () => {
    renderHomeWithDestinationFixture();
    fireEvent.click(screen.getByRole('link', { name: '새 회의 시작' }));
    expect(await screen.findByText('New Meeting fixture: /meetings/new')).toBeTruthy();
  });

  it('shows loading and then renders roster items', async () => {
    let resolve!: (value: Response) => void;
    const pending = new Promise<Response>((done) => { resolve = done; });
    mockList(pending);
    renderParticipantsApp();

    expect(screen.getByRole('status').textContent).toMatch(/불러오는 중/);
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
    vi.stubGlobal('fetch', fetchMock);
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
    vi.stubGlobal('fetch', fetchMock);
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
    vi.stubGlobal('fetch', fetchMock);
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
    vi.stubGlobal('fetch', fetchMock);
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
    vi.stubGlobal('fetch', fetchMock);
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
    vi.stubGlobal('fetch', fetchMock);
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

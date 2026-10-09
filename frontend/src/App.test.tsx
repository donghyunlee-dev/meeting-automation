import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
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

describe('Participants settings', () => {
  it('shows loading and then renders roster items', async () => {
    let resolve!: (value: Response) => void;
    const pending = new Promise<Response>((done) => { resolve = done; });
    mockList(pending);
    render(<App />);

    expect(screen.getByRole('status').textContent).toMatch(/불러오는 중/);
    resolve(response({ data: { items: participants } }));
    expect(await screen.findByText('Ada Lovelace')).toBeTruthy();
    expect(screen.getByText('ada@example.com')).toBeTruthy();
  });

  it('shows empty and search-empty recovery actions', async () => {
    mockList(response({ data: { items: participants } }));
    render(<App />);
    await screen.findByText('Ada Lovelace');
    fireEvent.change(screen.getByRole('searchbox', { name: '참석자 검색' }), { target: { value: 'nobody' } });
    expect(screen.getByText(/검색 결과가 없습니다/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '검색 입력 지우기' }));
    expect(screen.getByText('Ada Lovelace')).toBeTruthy();

    mockList(response({ data: { items: [] } }));
    cleanup();
    render(<App />);
    expect(await screen.findByText(/등록된 참석자가 없습니다/)).toBeTruthy();
  });

  it('filters by case-insensitive name or email substring', async () => {
    mockList(response({ data: { items: participants } }));
    render(<App />);
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
    render(<App />);
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
    render(<App />);
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
    render(<App />);
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
    render(<App />);
    await screen.findByText('Ada Lovelace');
    fireEvent.click(within(screen.getByRole('article', { name: /Ada Lovelace/ })).getByRole('button', { name: '수정' }));
    fireEvent.change(screen.getByLabelText('이름'), { target: { value: 'Ada Byron' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(await screen.findByText('Ada Byron')).toBeTruthy();
    expect(fetchMock.mock.calls[1][1]?.method).toBe('PATCH');
    expect(JSON.parse(String(fetchMock.mock.calls[1][1]?.body))).toEqual({ name: 'Ada Byron' });
    expect(screen.getByText('ada@example.com')).toBeTruthy();
  });

  it('validates fields and patches only changed values; retains edit values after 404', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ data: { items: participants } }))
      .mockResolvedValueOnce(response({ error: { code: 'PARTICIPANT_NOT_FOUND' } }, 404))
      .mockResolvedValueOnce(response({ data: { items: participants } }));
    vi.stubGlobal('fetch', fetchMock);
    render(<App />);
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

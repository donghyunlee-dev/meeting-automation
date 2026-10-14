import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AsyncState } from './AsyncState';
import { normalizeApiError } from '../../lib/api/normalizeApiError';

afterEach(cleanup);

describe('AsyncState', () => {
  it('ASYNC-INITIAL announces loading for the resource region', () => {
    render(<AsyncState state={{ status: 'loading', label: '회의 목록을 불러오는 중입니다.' }} renderReady={() => <p>회의 목록</p>} />);

    expect(screen.getByRole('status').textContent).toContain('회의 목록을 불러오는 중입니다.');
    expect(screen.queryByText('회의 목록')).toBeNull();
  });

  it('ASYNC-EMPTY-LIST renders an empty explanation and its explicit next action', () => {
    const onAction = vi.fn();
    render(<AsyncState state={{ status: 'empty', title: '회의가 없습니다.', description: '새 회의를 시작해 보세요.', action: <button type="button" onClick={onAction}>새 회의 시작</button> }} renderReady={() => null} />);

    expect(screen.getByRole('heading', { name: '회의가 없습니다.' })).toBeTruthy();
    expect(screen.getByText('새 회의를 시작해 보세요.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '새 회의 시작' }));
    expect(onAction).toHaveBeenCalledTimes(1);
  });

  it('ASYNC-ERROR-SAFE renders only safe error copy and exposes retry only when the caller supplies a safe GET recovery', () => {
    const onRetry = vi.fn();
    const error = normalizeApiError({ error: {
      code: 'PROVIDER_FAILURE', category: 'UPSTREAM', retryable: true, traceId: 'trace-async-17',
      message: 'token=private-secret', details: { providerResponse: 'customer@example.test' },
    } }, 502);
    render(<AsyncState state={{ status: 'error', title: '회의 목록을 불러오지 못했습니다.', error, onRetry }} renderReady={() => null} />);

    expect(screen.getByRole('alert').textContent).toContain('요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    expect(screen.getByRole('alert').textContent).toContain('trace-async-17');
    expect(screen.queryByText(/private-secret|customer@example\.test|token=/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('keeps heading references unique when multiple async states appear together', () => {
    const error = normalizeApiError(null);
    render(<>
      <AsyncState state={{ status: 'error', title: '설정 조회 실패', error }} renderReady={() => null} />
      <AsyncState state={{ status: 'error', title: '참석자 조회 실패', error }} renderReady={() => null} />
    </>);

    const alerts = screen.getAllByRole('alert');
    const labelledBy = alerts.map((alert) => alert.getAttribute('aria-labelledby'));
    expect(new Set(labelledBy).size).toBe(2);
    expect(labelledBy.every((id) => id && document.getElementById(id)?.tagName === 'H2')).toBe(true);
  });

  it('ERROR-404 distinguishes not-found errors from empty resources and does not invent a retry action', () => {
    const error = normalizeApiError({ error: { code: 'MEETING_NOT_FOUND', message: 'raw provider details' } }, 404);
    render(<AsyncState state={{ status: 'error', title: '회의 기록을 찾을 수 없습니다.', description: '목록으로 돌아가 다시 선택해 주세요.', error }} renderReady={() => null} />);

    expect(screen.getByRole('alert').textContent).toContain('회의 기록을 찾을 수 없습니다.');
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
    expect(screen.queryByText('회의 기록이 없습니다.')).toBeNull();
    expect(screen.queryByText('raw provider details')).toBeNull();
  });

  it('ASYNC-STALE preserves ready content during a background refresh error and adds a non-blocking warning', () => {
    const error = normalizeApiError({ error: { code: 'MEETING_LIST_FAILED' } }, 503);
    render(<AsyncState state={{ status: 'ready', data: ['기존 회의록 초안'], warning: error, onRetry: vi.fn() }} renderReady={(items) => <p>{items[0]}</p>} />);

    expect(screen.getByText('기존 회의록 초안')).toBeTruthy();
    expect(screen.getByRole('status').textContent).toContain('새로고침하지 못했습니다.');
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthenticatedSession } from './App';
import type { AuthSession } from './authApi';

const authMocks = vi.hoisted(() => ({
  getAuthSession: vi.fn(),
  recordAuthActivity: vi.fn(),
  logoutAuthSession: vi.fn(),
}));

vi.mock('./authApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./authApi')>();
  return { ...actual, ...authMocks };
});

function CurrentPath() {
  return <span>{useLocation().pathname}</span>;
}

afterEach(() => { cleanup(); vi.useRealTimers(); vi.clearAllMocks(); });

const session: AuthSession = { authenticated: true, sessionId: 'device-1', remainingIdleSeconds: 7200 };

describe('AuthenticatedSession', () => {
  it('resets the two hour idle deadline after user action', () => {
    vi.useFakeTimers();
    authMocks.recordAuthActivity.mockResolvedValue({ authenticated: true, remainingIdleSeconds: 7200 });
    authMocks.logoutAuthSession.mockResolvedValue({ loggedOut: true });
    const onExpired = vi.fn();
    render(<MemoryRouter><AuthenticatedSession session={session} onExpired={onExpired} activeWorkflow={false}><p>main screen</p></AuthenticatedSession></MemoryRouter>);

    act(() => { vi.advanceTimersByTime(60 * 60 * 1000); });
    fireEvent.click(screen.getByText('main screen'));
    act(() => { vi.advanceTimersByTime(2 * 60 * 60 * 1000 - 1); });
    expect(onExpired).not.toHaveBeenCalled();
    act(() => { vi.advanceTimersByTime(1); });
    expect(onExpired).toHaveBeenCalledOnce();
  });

  it('sends periodic activity only for active recording or processing routes', async () => {
    vi.useFakeTimers();
    authMocks.recordAuthActivity.mockResolvedValue({ authenticated: true, remainingIdleSeconds: 7200 });
    authMocks.logoutAuthSession.mockResolvedValue({ loggedOut: true });
    const onExpired = vi.fn();
    const intervalSpy = vi.spyOn(window, 'setInterval');
    const { unmount } = render(<MemoryRouter initialEntries={['/']}>
      <AuthenticatedSession session={session} onExpired={onExpired} activeWorkflow={false}><><p>main screen</p><CurrentPath /></></AuthenticatedSession>
    </MemoryRouter>);
    expect(intervalSpy.mock.calls.some(([, delay]) => delay === 60_000)).toBe(false);
    expect(authMocks.recordAuthActivity).not.toHaveBeenCalled();

    unmount();
    cleanup();
    render(<MemoryRouter initialEntries={['/meetings/session-1/processing']}>
      <AuthenticatedSession session={session} onExpired={onExpired} activeWorkflow={true}><><p>main screen</p><CurrentPath /></></AuthenticatedSession>
    </MemoryRouter>);
    expect(screen.getByText('/meetings/session-1/processing')).toBeTruthy();
    const heartbeatCallback = intervalSpy.mock.calls.find(([, delay]) => delay === 60_000)?.[0];
    expect(typeof heartbeatCallback).toBe('function');
    act(() => { (heartbeatCallback as () => void)(); });
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(authMocks.recordAuthActivity).toHaveBeenCalled();
  });
});

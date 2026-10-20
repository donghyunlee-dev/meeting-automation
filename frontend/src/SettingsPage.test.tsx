import { withHealthyBackend } from './testUtils/withHealthyBackend';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router';
import { App } from './App';
import { SettingsPage } from './SettingsPage';

function response(data: unknown, status = 200) {
  return new Response(JSON.stringify(status >= 400 ? { error: { detail: data } } : { data }), { status, headers: { 'Content-Type': 'application/json' } });
}

const config = {
  company: { id: 'c1', name: 'Acme', timezone: 'Asia/Seoul' },
  document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://secret-root.example/root-id' },
  email: { enabled: true, configured: true }, notification: { provider: 'SLACK', enabled: true },
};
const health = {
  document: { provider: 'NOTION', configured: true, reachable: true, rootAccessible: true },
  email: { configured: true, reachable: true }, notification: { provider: 'SLACK', configured: true, reachable: false },
};

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('Settings page', () => {
  it('starts the public configuration and integration health GET queries independently', async () => {
    const pending: Array<{ path: string; resolve: (value: Response) => void }> = [];
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/integrations/email') || path.endsWith('/integrations/slack')) {
        return Promise.resolve(response({ configured: false, connectionVerified: false }));
      }
      return new Promise<Response>((resolve) => pending.push({ path, resolve }));
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter><SettingsPage /></MemoryRouter>);

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(fetchMock.mock.calls.map(([input]) => new URL(String(input)).pathname).sort()).toEqual([
      '/api/v1/app-config', '/api/v1/integrations/health',
    ]);
    pending.forEach(({ path, resolve }) => resolve(response(path.endsWith('/app-config') ? config : health)));
    await waitFor(() => expect(document.querySelector('.settings-sections')?.textContent).toContain('Notion'));
    expect(await screen.findByLabelText('발신 이메일 주소')).toBeTruthy();
    expect(await screen.findByLabelText('Slack 알림 주소')).toBeTruthy();
    expect(await screen.findByText('연결 상태 확인됨')).toBeTruthy();
    expect(screen.getByText('최근 알림 전달 상태가 확인되지 않았습니다.')).toBeTruthy();
    expect(screen.getByRole('heading', { level: 1, name: '설정' })).toBeTruthy();
    expect([...document.querySelectorAll('.settings-sections > section h2')].map((heading) => heading.textContent)).toEqual(['문서 연결', '이메일', '알림']);
    expect(screen.getByText((_content, element) => element?.classList.contains('settings-method') === true && element.textContent?.includes('Notion') === true)).toBeTruthy();
    expect(screen.getByText((_content, element) => element?.classList.contains('settings-method') === true && element.textContent?.includes('회의록과 기본 템플릿') === true)).toBeTruthy();
    expect(screen.queryByText(/DOCUMENT SERVICE|DOCUMENT CONNECTION/)).toBeNull();
    expect(screen.queryByText(/OAuth|Refresh Token|Webhook/)).toBeNull();
    expect(screen.getByRole('link', { name: '문서 연결 설정' })).toBeTruthy();
    expect(screen.queryByRole('link', { name: '참석자 관리' })).toBeNull();
    expect(screen.queryByText('Acme')).toBeNull();
    expect(screen.queryByText(/시간대:/)).toBeNull();
    expect(screen.queryByRole('region', { name: '회사' })).toBeNull();
    expect(screen.queryByRole('region', { name: '참석자' })).toBeNull();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4));
  });

  it('keeps public configuration visible when health fails and refreshes current settings with GET', async () => {
    let healthCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      expect(init?.method).toBeUndefined();
      if (path.endsWith('/app-config')) return response(config);
      if (path.endsWith('/integrations/health')) {
        healthCalls += 1;
        if (healthCalls === 1) return response('provider-private-error-marker', 503);
        return response(health);
      }
      if (path.endsWith('/integrations/email')) return response({ configured: false, connectionVerified: false });
      if (path.endsWith('/integrations/slack')) return response({ configured: false, connectionVerified: false });
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter><SettingsPage /></MemoryRouter>);

    await waitFor(() => expect(document.querySelector('.settings-sections')?.textContent).toContain('Notion'));
    expect(await screen.findByLabelText('발신 이메일 주소')).toBeTruthy();
    expect(await screen.findByLabelText('Slack 알림 주소')).toBeTruthy();
    const initialConfigCalls = fetchMock.mock.calls.filter(([input]) => new URL(String(input)).pathname.endsWith('/app-config')).length;
    await waitFor(() => expect(healthCalls).toBeGreaterThan(0));
    fireEvent.click(screen.getByRole('button', { name: '연결 상태 다시 확인' }));
    await waitFor(() => expect(healthCalls).toBeGreaterThan(1));
    expect(fetchMock.mock.calls.filter(([input]) => new URL(String(input)).pathname.endsWith('/app-config')).length).toBe(initialConfigCalls);
    expect(fetchMock.mock.calls.some(([, init]) => init?.method && init.method !== 'GET')).toBe(false);
  });

  it('keeps email credentials editable when the backend has no saved email settings', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), window.location.origin).pathname;
      if (path.endsWith('/app-config')) return response(config);
      if (path.endsWith('/integrations/health')) return response(health);
      if (path.endsWith('/integrations/email')) return response('email settings unavailable', 503);
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter><SettingsPage /></MemoryRouter>);

    const emailSection = await screen.findByRole('region', { name: '이메일' });
    const sender = await within(emailSection).findByLabelText('발신 이메일 주소');
    expect(sender.hasAttribute('readonly')).toBe(false);
    fireEvent.change(sender, { target: { value: 'user@gmail.com' } });
    fireEvent.change(within(emailSection).getByLabelText('Google 클라이언트 ID'), { target: { value: 'client-id' } });
    fireEvent.change(within(emailSection).getByLabelText('Google 클라이언트 보안 키'), { target: { value: 'client-secret' } });
    fireEvent.change(within(emailSection).getByLabelText('메일 전송용 연결 토큰'), { target: { value: 'refresh-token' } });
    expect(within(emailSection).getByRole('button', { name: '저장' }).hasAttribute('disabled')).toBe(false);
    expect(await screen.findByLabelText('Slack 알림 주소')).toBeTruthy();
  });

  it('recovers connection health after the initial health query fails', async () => {
    let healthCalls = 0;
    const recoveredHealth = {
      ...health,
      document: { ...health.document, reachable: false },
      email: { ...health.email, reachable: false },
      notification: { ...health.notification, reachable: true },
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      expect(init?.method).toBeUndefined();
      if (path.endsWith('/app-config')) return response(config);
      if (path.endsWith('/integrations/health')) {
        healthCalls += 1;
        if (healthCalls === 1) return response('private-provider-error', 503);
        return response(healthCalls === 1 ? health : recoveredHealth);
      }
      if (path.endsWith('/integrations/email')) return response({ configured: false, connectionVerified: false });
      if (path.endsWith('/integrations/slack')) return response({ configured: false, connectionVerified: false });
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter><SettingsPage /></MemoryRouter>);

    await waitFor(() => expect(healthCalls).toBe(1));
    fireEvent.click(await screen.findByRole('button', { name: '연결 상태 다시 확인' }));
    await waitFor(() => expect(healthCalls).toBeGreaterThan(1));
    expect(document.body.textContent).not.toContain('private-provider-error');
    const documentSection = screen.getByRole('region', { name: '문서 연결' });
    const emailSection = screen.getByRole('region', { name: '이메일' });
    const notificationSection = screen.getByRole('region', { name: '알림' });
    expect(await within(emailSection).findByLabelText('발신 이메일 주소')).toBeTruthy();
    expect(await screen.findByLabelText('Slack 알림 주소')).toBeTruthy();
    const previousHealthCalls = healthCalls;
    fireEvent.click(screen.getByRole('button', { name: '모든 연결 상태 새로고침' }));
    await waitFor(() => expect(healthCalls).toBeGreaterThan(previousHealthCalls));
    expect(await within(notificationSection).findByText('연결 확인됨')).toBeTruthy();
    expect(documentSection).toBeTruthy();
    expect(emailSection).toBeTruthy();
  });

  it('keeps successful health sections visible when public configuration fails', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => new URL(String(input)).pathname.endsWith('/app-config')
      ? response('configuration-private-error', 503) : response(health));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter><SettingsPage /></MemoryRouter>);

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(document.querySelector('.settings-sections')?.textContent).toContain('Slack');
    expect(await screen.findByLabelText('발신 이메일 주소')).toBeTruthy();
    expect(await screen.findByLabelText('Slack 알림 주소')).toBeTruthy();
    expect(screen.getByText('최근 알림 전달 상태가 확인되지 않았습니다.')).toBeTruthy();
  });

  it('offers one safe page retry after both independent settings queries fail', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      expect(['/api/v1/app-config', '/api/v1/integrations/health']).toContain(new URL(String(input)).pathname);
      return response('raw-provider-secret', 503);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter><SettingsPage /></MemoryRouter>);

    const retry = await screen.findByRole('button', { name: '전체 설정 다시 불러오기' });
    expect(screen.getByRole('heading', { name: '설정과 연결 상태를 불러오지 못했습니다.' })).toBeTruthy();
    expect(screen.getAllByRole('button')).toHaveLength(1);
    expect(document.body.textContent).not.toContain('raw-provider-secret');
    fireEvent.click(retry);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4));
    expect(fetchMock.mock.calls.map(([input]) => new URL(String(input)).pathname).sort()).toEqual([
      '/api/v1/app-config', '/api/v1/app-config', '/api/v1/integrations/health', '/api/v1/integrations/health',
    ]);
  });

  it('does not render secrets, root identifiers, or raw provider errors', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => new URL(String(input)).pathname.endsWith('/app-config')
      ? response(config) : response('raw-provider-token private-root-id', 502));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter><SettingsPage /></MemoryRouter>);

    await waitFor(() => expect(document.querySelector('.settings-sections')?.textContent).toContain('Notion'));
    expect(await screen.findByLabelText('발신 이메일 주소')).toBeTruthy();
    expect(await screen.findByLabelText('Slack 알림 주소')).toBeTruthy();
    const visibleText = document.body.textContent ?? '';
    expect(visibleText).not.toMatch(/secret-root|root-id|raw-provider-token|private-root-id/);
  });

  it('keeps an accessible route to the shared document setup wizard and participant settings', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response(config);
      if (path.endsWith('/integrations/health')) return response(health);
      if (path.endsWith('/document-setup')) return response({ version: 1, status: 'READY', active: { provider: 'NOTION', connectionVersion: 1, rootUrl: 'https://notion.example' }, draft: null, operation: null, canCreateMeeting: true });
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    render(<MemoryRouter initialEntries={['/settings']}><App /></MemoryRouter>);

    fireEvent.click(await screen.findByRole('link', { name: '문서 연결 설정' }));
    expect(await screen.findByRole('button', { name: '문서 서비스 변경' })).toBeTruthy();
    fireEvent.click(screen.getByRole('link', { name: '설정으로 돌아가기' }));
    expect(screen.queryByRole('link', { name: '참석자 관리' })).toBeNull();
    expect(await screen.findByLabelText('발신 이메일 주소')).toBeTruthy();
    expect(await screen.findByLabelText('Slack 알림 주소')).toBeTruthy();
  });
});

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
    const fetchMock = vi.fn((input: RequestInfo | URL) => new Promise<Response>((resolve) => pending.push({ path: new URL(String(input)).pathname, resolve })));
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter><SettingsPage /></MemoryRouter>);

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(fetchMock.mock.calls.map(([input]) => new URL(String(input)).pathname).sort()).toEqual([
      '/api/v1/app-config', '/api/v1/integrations/health',
    ]);
    pending.forEach(({ path, resolve }) => resolve(response(path.endsWith('/app-config') ? config : health)));
    expect(await screen.findByText('Acme')).toBeTruthy();
    expect(await screen.findByText('설정 연결 확인됨')).toBeTruthy();
    expect(screen.getByText('최근 알림 전달 상태가 확인되지 않았습니다.')).toBeTruthy();
  });

  it('keeps public configuration visible when health fails and retry refreshes only health with GET', async () => {
    let healthCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      expect(init?.method).toBeUndefined();
      if (path.endsWith('/app-config')) return response(config);
      healthCalls += 1;
      if (healthCalls === 1) return response('provider-private-error-marker', 503);
      return response(health);
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter><SettingsPage /></MemoryRouter>);

    expect(await screen.findByText('Acme')).toBeTruthy();
    const documentSection = screen.getByRole('region', { name: '문서 서비스' });
    fireEvent.click(within(documentSection).getByRole('button', { name: '연결 상태 다시 확인' }));
    expect(await screen.findByText('최근 알림 전달 상태가 확인되지 않았습니다.')).toBeTruthy();
    expect(fetchMock.mock.calls.filter(([input]) => new URL(String(input)).pathname.endsWith('/app-config'))).toHaveLength(1);
    expect(fetchMock.mock.calls.some(([, init]) => init?.method && init.method !== 'GET')).toBe(false);
  });

  it('keeps successful health sections visible when public configuration fails', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => new URL(String(input)).pathname.endsWith('/app-config')
      ? response('configuration-private-error', 503) : response(health));
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter><SettingsPage /></MemoryRouter>);

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByText('Slack')).toBeTruthy();
    expect(screen.getByText('최근 알림 전달 상태가 확인되지 않았습니다.')).toBeTruthy();
  });

  it('offers one safe page retry after both independent settings queries fail', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      expect(['/api/v1/app-config', '/api/v1/integrations/health']).toContain(new URL(String(input)).pathname);
      return response('raw-provider-secret', 503);
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter><SettingsPage /></MemoryRouter>);

    const retry = await screen.findByRole('button', { name: '전체 설정 다시 불러오기' });
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
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter><SettingsPage /></MemoryRouter>);

    expect(await screen.findByText('Acme')).toBeTruthy();
    const visibleText = document.body.textContent ?? '';
    expect(visibleText).not.toMatch(/secret-root|root-id|raw-provider-token|private-root-id/);
    expect(visibleText).toContain('실제 이메일 또는 Slack 메시지 전달 성공을 보장하지 않습니다');
  });

  it('keeps an accessible route to the shared document setup wizard and participant settings', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/app-config')) return response(config);
      if (path.endsWith('/integrations/health')) return response(health);
      if (path.endsWith('/document-setup')) return response({ version: 1, status: 'READY', active: { provider: 'NOTION', connectionVersion: 1, rootUrl: 'https://notion.example' }, draft: null, operation: null, canCreateMeeting: true });
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<MemoryRouter initialEntries={['/settings']}><App /></MemoryRouter>);

    fireEvent.click(await screen.findByRole('link', { name: '문서 연결 설정 열기' }));
    expect(await screen.findByRole('button', { name: '문서 서비스 변경' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '참석자 관리' }).getAttribute('href')).toBe('/settings/participants');
  });
});

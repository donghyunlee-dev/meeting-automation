import { describe, expect, it } from 'vitest';
import { selectSettingsSummary, type AppConfig, type IntegrationHealth } from './settingsApi';

const company = { id: 'c1', name: 'Acme', timezone: 'Asia/Seoul' };

describe('settings summary selector', () => {
  it('distinguishes an unselected document provider from an unconfigured selected provider', () => {
    const noProvider = selectSettingsSummary({ document: { provider: null, configured: false, connectionVersion: 0, rootUrl: null }, email: { enabled: false, configured: false }, notification: { provider: null, enabled: false } }, null);
    const selected = selectSettingsSummary({ document: { provider: 'NOTION', configured: false, connectionVersion: 1, rootUrl: 'https://private.example' }, email: { enabled: false, configured: false }, notification: { provider: null, enabled: false } }, null);

    expect(noProvider.document.state).toBe('not-selected');
    expect(selected.document).toMatchObject({ state: 'not-configured', provider: 'Notion' });
    expect(JSON.stringify(selected)).not.toContain('private.example');
  });

  it('distinguishes provider reachability from root access and keeps notification reachability conservative', () => {
    const config: AppConfig = {
      company,
      document: { provider: 'CONFLUENCE', configured: true, connectionVersion: 2, rootUrl: 'https://private.example' },
      email: { enabled: true, configured: true },
      notification: { provider: 'SLACK', enabled: true },
    };
    const health: IntegrationHealth = {
      document: { provider: 'CONFLUENCE', configured: true, reachable: true, rootAccessible: false },
      email: { configured: true, reachable: true },
      notification: { provider: 'SLACK', configured: true, reachable: false },
    };

    const summary = selectSettingsSummary(config, health);

    expect(summary.document.state).toBe('root-inaccessible');
    expect(summary.email.state).toBe('available');
    expect(summary.notification.state).toBe('not-confirmed');
    expect(JSON.stringify(summary)).not.toMatch(/private\.example|token|webhook/i);
  });

  it('reports configured providers with failed connection checks as unreachable', () => {
    const summary = selectSettingsSummary({
      document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: null },
      email: { enabled: true, configured: true },
      notification: { provider: null, enabled: false },
    }, {
      document: { provider: 'NOTION', configured: true, reachable: false, rootAccessible: false },
      email: { configured: true, reachable: false },
      notification: null,
    });

    expect(summary.document.state).toBe('unreachable');
    expect(summary.email.state).toBe('unreachable');
  });

  it('keeps config visible when health is unavailable without claiming integrations are reachable', () => {
    const config: AppConfig = { company, email: { enabled: true, configured: true }, notification: { provider: 'SLACK', enabled: true }, document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://private.example' } };

    const summary = selectSettingsSummary(config, null);

    expect(summary.document.state).toBe('health-unknown');
    expect(summary.email.state).toBe('health-unknown');
    expect(summary.notification.state).toBe('health-unknown');
  });
});

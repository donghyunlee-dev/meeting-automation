export type AppConfig = {
  company: { id: string; name: string; timezone: string };
  document?: { provider: string | null; configured: boolean; connectionVersion: number; rootUrl: string | null } | null;
  email?: { enabled: boolean; configured: boolean } | null;
  notification?: { provider: string | null; enabled: boolean } | null;
};

export type IntegrationHealth = {
  document?: { provider: string | null; configured: boolean; reachable: boolean; rootAccessible: boolean } | null;
  email?: { configured: boolean; reachable: boolean } | null;
  notification?: { provider: string | null; configured: boolean; reachable: boolean } | null;
};

export class SettingsApiError extends Error {
  constructor(readonly code: string) {
    super('공개 설정을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.');
    this.name = 'SettingsApiError';
  }
}

async function readData<T>(path: string): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${(import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '')}/api/v1${path}`, {
      headers: { Accept: 'application/json' },
    });
  } catch {
    throw new SettingsApiError('NETWORK_ERROR');
  }
  if (!response.ok) throw new SettingsApiError('REQUEST_FAILED');
  let body: unknown;
  try { body = await response.json(); } catch { throw new SettingsApiError('INVALID_RESPONSE'); }
  if (!body || typeof body !== 'object' || !('data' in body) || !(body as { data?: unknown }).data || typeof (body as { data: unknown }).data !== 'object') {
    throw new SettingsApiError('INVALID_RESPONSE');
  }
  return (body as { data: T }).data;
}

export function getAppConfig(): Promise<AppConfig> {
  return readData<AppConfig>('/app-config');
}

export function getIntegrationHealth(): Promise<IntegrationHealth> {
  return readData<IntegrationHealth>('/integrations/health');
}

export type IntegrationState = 'not-selected' | 'not-configured' | 'unreachable' | 'root-inaccessible' | 'available' | 'not-confirmed' | 'health-unknown';
export type SettingsSummary = {
  company: AppConfig['company'];
  document: { provider: string | null; state: IntegrationState };
  email: { state: IntegrationState };
  notification: { provider: string | null; state: IntegrationState };
};

function providerName(provider: string | null | undefined): string | null {
  if (provider === 'NOTION') return 'Notion';
  if (provider === 'CONFLUENCE') return 'Confluence Cloud';
  if (provider === 'SLACK') return 'Slack';
  return null;
}

function documentState(config: AppConfig['document'], health: IntegrationHealth['document']): IntegrationState {
  if (!config?.provider) return 'not-selected';
  if (!config.configured) return 'not-configured';
  if (!health) return 'health-unknown';
  if (!health.configured) return 'not-configured';
  if (!health.reachable) return 'unreachable';
  if (!health.rootAccessible) return 'root-inaccessible';
  return 'available';
}

function optionalIntegrationState(enabled: boolean, configured: boolean, health: { configured: boolean; reachable: boolean } | null | undefined, notification = false): IntegrationState {
  if (!enabled || !configured) return 'not-configured';
  if (!health) return 'health-unknown';
  if (!health.configured) return 'not-configured';
  if (!health.reachable) return notification ? 'not-confirmed' : 'unreachable';
  return 'available';
}

export function selectSettingsSummary(config: AppConfig, health: IntegrationHealth | null): SettingsSummary {
  return {
    company: { id: config.company.id, name: config.company.name, timezone: config.company.timezone },
    document: { provider: providerName(config.document?.provider), state: documentState(config.document, health?.document) },
    email: { state: optionalIntegrationState(Boolean(config.email?.enabled), Boolean(config.email?.configured), health?.email) },
    notification: {
      provider: providerName(config.notification?.provider),
      state: optionalIntegrationState(Boolean(config.notification?.enabled), Boolean(config.notification?.enabled), health?.notification, true),
    },
  };
}

export const EMAIL_SETTINGS_STORAGE_KEY = 'meeting-automation.email-oauth.v1';

export type StoredEmailSettings = {
  clientId: string;
  clientSecret: string;
  refreshToken: string;
  senderAddress: string;
};

export function loadStoredEmailSettings(): StoredEmailSettings | null {
  try {
    const value = localStorage.getItem(EMAIL_SETTINGS_STORAGE_KEY);
    if (!value) return null;
    const parsed = JSON.parse(value) as Partial<StoredEmailSettings>;
    return [parsed.clientId, parsed.clientSecret, parsed.refreshToken, parsed.senderAddress]
      .every((item) => typeof item === 'string' && item.trim())
      ? parsed as StoredEmailSettings
      : null;
  } catch {
    return null;
  }
}

export function saveStoredEmailSettings(settings: StoredEmailSettings): void {
  localStorage.setItem(EMAIL_SETTINGS_STORAGE_KEY, JSON.stringify(settings));
}

export function clearStoredEmailSettings(): void {
  localStorage.removeItem(EMAIL_SETTINGS_STORAGE_KEY);
}

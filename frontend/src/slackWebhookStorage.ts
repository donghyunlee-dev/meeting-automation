export const SLACK_WEBHOOK_STORAGE_KEY = 'meeting-automation.slack-webhook.v1';

export function loadStoredSlackWebhook(): string | null {
  try {
    const value = localStorage.getItem(SLACK_WEBHOOK_STORAGE_KEY)?.trim();
    return value && /^https:\/\/hooks\.slack\.com\/services\//.test(value) ? value : null;
  } catch {
    return null;
  }
}

export function saveStoredSlackWebhook(webhookUrl: string): void {
  localStorage.setItem(SLACK_WEBHOOK_STORAGE_KEY, webhookUrl.trim());
}

export function clearStoredSlackWebhook(): void {
  localStorage.removeItem(SLACK_WEBHOOK_STORAGE_KEY);
}

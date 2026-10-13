import { expect, test } from '@playwright/test';

test('keeps speaker selection keyboard accessible and within a 360px viewport', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await page.route('**/api/v1/app-config', (route) => route.fulfill({ json: { data: {
    company: { id: 'company-1', name: 'Example', timezone: 'Asia/Seoul' },
    document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' },
  } } }));
  await page.route('**/api/v1/participants', (route) => route.fulfill({ json: { data: { items: [
    { id: 'p1', name: 'Ada Lovelace', email: 'ada.private@example.com' },
    { id: 'p2', name: 'Grace Hopper', email: 'grace.private@example.com' },
  ] } } }));
  await page.route('**/api/v1/meeting-sessions/mobile-review', (route) => route.fulfill({ json: { data: {
    sessionId: 'mobile-review', version: 1, status: 'REVIEW', meeting: { participantIds: ['p1', 'p2'] },
    processing: { stage: 'DRAFT_READY', progressPercent: 100 },
    speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p1' }],
  } } }));

  await page.goto('/meetings/mobile-review/review');
  const selector = page.getByRole('combobox', { name: 'Speaker A 참가자' });
  await expect(selector).toHaveValue('p1');
  await selector.focus();
  await expect(selector).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(selector).toHaveValue('p2');
  await expect(page.getByText(/@/)).toHaveCount(0);

  const layout = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth,
    selector: (() => {
      const rect = document.querySelector('select')!.getBoundingClientRect();
      return { width: rect.width, height: rect.height };
    })(),
    refreshButton: (() => {
      const rect = document.querySelector('.review-speaker-card-heading button')!.getBoundingClientRect();
      return { width: rect.width, height: rect.height };
    })(),
  }));
  expect(layout.document).toBeLessThanOrEqual(layout.viewport);
  expect(layout.selector.width).toBeGreaterThanOrEqual(44);
  expect(layout.selector.height).toBeGreaterThanOrEqual(44);
  expect(layout.refreshButton.width).toBeGreaterThanOrEqual(44);
  expect(layout.refreshButton.height).toBeGreaterThanOrEqual(44);
});

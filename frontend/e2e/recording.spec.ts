import { expect, test } from '@playwright/test';

test('keeps the recording screen within 360px, provides touch targets, and respects reduced motion', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.route('**/api/v1/document-setup', (route) => route.fulfill({ json: { data: {
    version: 1, status: 'READY', active: { provider: 'NOTION', connectionVersion: 1, rootUrl: 'https://notion.example' },
    draft: null, operation: null, canCreateMeeting: true,
  } } }));
  await page.route('**/api/v1/app-config', (route) => route.fulfill({ json: { data: {
    company: { id: 'company-1', name: 'Example', timezone: 'Asia/Seoul' },
    document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://notion.example' },
    setup: { status: 'READY', required: false, canCreateMeeting: true, operationId: null },
  } } }));
  await page.route('**/api/v1/templates', (route) => route.fulfill({ json: { data: { items: [{ id: 'default.md', name: 'Default', version: '1.0.0' }] } } }));
  await page.route('**/api/v1/participants', (route) => route.fulfill({ json: { data: { items: [{ id: 'participant-1', name: 'Ada Lovelace', email: 'ada@example.com' }] } } }));
  await page.route('**/api/v1/meeting-sessions', (route) => route.fulfill({ status: 201, json: { data: {
    sessionId: 'session-mobile', version: 1, status: 'CREATED',
    uploadPolicy: { chunkDurationSeconds: 30, maxChunkBytes: 1_000_000, acceptedMimeTypes: ['audio/webm'] },
  } } }));

  await page.goto('/meetings/new');
  await page.getByLabel('회의 제목').fill('Mobile planning');
  await page.getByRole('button', { name: '참석자 추가' }).click();
  await page.getByRole('checkbox', { name: 'Ada Lovelace ada@example.com' }).check();
  await page.getByRole('button', { name: '회의 시작' }).click();
  await expect(page.getByRole('heading', { name: 'Mobile planning' })).toBeVisible();
  await page.getByRole('button', { name: '녹음 시작' }).click();
  await expect(page.getByRole('status')).toHaveText('녹음 중');
  await expect(page.getByRole('navigation', { name: '주요 메뉴' })).toHaveCount(0);

  const sizes = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    page: document.documentElement.scrollWidth,
    body: document.body.scrollWidth,
    buttons: [...document.querySelectorAll('.recording-actions button')].map((button) => {
      const rect = button.getBoundingClientRect();
      return { width: rect.width, height: rect.height };
    }),
    indicatorAnimation: getComputedStyle(document.querySelector('.recording-indicator')!).animationName,
  }));
  expect(sizes.page).toBeLessThanOrEqual(sizes.viewport);
  expect(sizes.body).toBeLessThanOrEqual(sizes.viewport);
  expect(sizes.buttons).toHaveLength(2);
  for (const button of sizes.buttons) {
    expect(button.width).toBeGreaterThanOrEqual(44);
    expect(button.height).toBeGreaterThanOrEqual(44);
  }
  expect(sizes.indicatorAnimation).toBe('none');

  const endButton = page.getByRole('button', { name: '회의 종료' });
  await endButton.focus();
  await endButton.click();
  await expect(page.getByRole('dialog', { name: '회의를 종료할까요?' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('status')).toHaveText('녹음 중');
  await expect(endButton).toBeFocused();
});

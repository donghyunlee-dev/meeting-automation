import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { installSyntheticApi, longKoreanTitle, participant, screens, sessionId, transcript, type Screen } from './fixtures';

async function expectNoSeriousAxeViolations(page: Page): Promise<void> {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22a', 'wcag22aa'])
    .analyze();
  const seriousViolations = results.violations.filter(({ impact }) => impact === 'critical' || impact === 'serious');

  expect(seriousViolations, seriousViolations.map(({ id, help, nodes }) =>
    `${id}: ${help} (${nodes.length} affected element(s))`,
  ).join('\n')).toEqual([]);
}

async function visitScreen(page: Page, screen: Screen): Promise<void> {
  await page.setViewportSize({ width: 390, height: 844 });
  if (screen.id === 'SCR-003') {
    await page.addInitScript((session) => {
      sessionStorage.setItem('meeting-automation.recording-session', JSON.stringify(session));
    }, {
      sessionId,
      version: 3,
      status: 'CREATED',
      title: longKoreanTitle,
      uploadPolicy: { chunkDurationSeconds: 30, maxChunkBytes: 1_000_000, acceptedMimeTypes: ['audio/webm'] },
    });
  }
  await installSyntheticApi(page, screen);
  await page.goto(screen.route);
  const main = page.locator('main').first();
  await expect(main).toBeVisible();
  const heading = main.getByRole('heading', { level: screen.headingLevel ?? 1 }).first();
  await expect(heading).toBeVisible();
  await expect(heading).toContainText(screen.heading);
}

for (const screen of screens) {
  test(`${screen.id} ${screen.route} has no serious WCAG 2.2 A/AA axe violations`, async ({ page }) => {
    await visitScreen(page, screen);
    await expectNoSeriousAxeViolations(page);
  });
}

test('SCR-012 settings shell exposes named navigation and PRD-sized primary controls', async ({ page }) => {
  const screen = screens.find(({ id }) => id === 'SCR-012');
  if (!screen) throw new Error('SCR-012 fixture is missing');
  await visitScreen(page, screen);
  await expectNoSeriousAxeViolations(page);

  const homeLink = page.getByRole('link', { name: 'Meeting Automation 홈' });
  const homeBounds = await homeLink.boundingBox();
  expect(homeBounds).not.toBeNull();
  expect(homeBounds!.width).toBeGreaterThanOrEqual(44);
  expect(homeBounds!.height).toBeGreaterThanOrEqual(44);

  const addButton = page.getByRole('button', { name: '새 참석자 추가' });
  const addBounds = await addButton.boundingBox();
  expect(addBounds).not.toBeNull();
  expect(addBounds!.width).toBeGreaterThanOrEqual(44);
  expect(addBounds!.height).toBeGreaterThanOrEqual(52);
});

test('SCR-004 end confirmation is named, keyboard reachable, scanned, and restores focus on Escape', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.addInitScript((session) => {
    sessionStorage.setItem('meeting-automation.recording-session', JSON.stringify(session));
    class FakeMediaRecorder {
      static isTypeSupported() { return true; }
      mimeType = 'audio/webm';
      onstart: (() => void) | null = null;
      ondataavailable: ((event: { data: Blob }) => void) | null = null;
      onstop: (() => void) | null = null;
      constructor(stream: MediaStream) { void stream; }
      start() { queueMicrotask(() => this.onstart?.()); }
      stop() { this.ondataavailable?.({ data: new Blob(['fixture'], { type: this.mimeType }) }); this.onstop?.(); }
    }
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia: async () => ({ getTracks: () => [{ stop() {} }] }) } });
    Object.defineProperty(globalThis, 'MediaRecorder', { configurable: true, value: FakeMediaRecorder });
  }, {
    sessionId,
    version: 3,
    status: 'CREATED',
    title: longKoreanTitle,
    uploadPolicy: { chunkDurationSeconds: 30, maxChunkBytes: 1_000_000, acceptedMimeTypes: ['audio/webm'] },
  });
  await page.route('**/api/v1/**', (route) => route.fulfill({ json: { data: { receivedSequences: [] } } }));
  await page.goto(`/meetings/${sessionId}/recording`);

  await page.getByRole('button', { name: '녹음 시작' }).click();
  const opener = page.getByRole('button', { name: '회의 종료' });
  await expect(opener).toBeVisible();
  await opener.click();
  const dialog = page.getByRole('dialog', { name: '회의를 종료할까요?' });
  await expect(dialog).toBeVisible();
  const cancel = dialog.getByRole('button', { name: '계속 녹음' });
  await expect(cancel).toBeFocused();
  await expectNoSeriousAxeViolations(page);

  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(opener).toBeFocused();
});

test('SCR-006 transcript drawer is named, scanned, and restores focus after Escape', async ({ page }) => {
  const screen = screens.find(({ id }) => id === 'SCR-006');
  if (!screen) throw new Error('SCR-006 fixture is missing');
  await visitScreen(page, screen);

  const opener = page.getByRole('button', { name: 'Transcript 원문 보기' });
  await opener.click();
  const drawer = page.getByRole('dialog', { name: 'Transcript 원문' });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole('button', { name: '닫기' })).toBeFocused();
  await expect(drawer.getByText(transcript[0].text)).toBeVisible();
  await expectNoSeriousAxeViolations(page);

  await page.keyboard.press('Escape');
  await expect(drawer).toHaveCount(0);
  await expect(opener).toBeFocused();
});

test('SCR-002 participant selection controls stay named and operable by keyboard', async ({ page }) => {
  const screen = screens.find(({ id }) => id === 'SCR-002');
  if (!screen) throw new Error('SCR-002 fixture is missing');
  await visitScreen(page, screen);

  const picker = page.getByRole('button', { name: '참석자 추가' });
  await picker.focus();
  await page.keyboard.press('Enter');
  const option = page.getByRole('checkbox', { name: `${participant.name} ${participant.email}` });
  await expect(option).toBeVisible();
  await option.focus();
  await page.keyboard.press('Space');
  await expect(option).toBeChecked();
  await expectNoSeriousAxeViolations(page);

  const primary = page.getByRole('button', { name: '회의 시작' });
  const bounds = await primary.boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds!.height).toBeGreaterThanOrEqual(52);
});

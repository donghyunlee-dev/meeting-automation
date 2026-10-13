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
    transcript: [
      { segmentId: 'segment-1', speakerId: 'speaker-a', startMs: 1_000, endMs: 2_500, text: '첫 번째 발언' },
      { segmentId: 'segment-2', speakerId: 'speaker-a', startMs: 65_000, endMs: 67_000, text: '두 번째 발언' },
    ],
    minutes: {
      templateId: 'weekly', templateVersion: '4', summary: '주간 회의 요약',
      discussionPoints: ['예산 검토'], decisions: ['기존 계획 유지'],
      actionItems: [{ ownerParticipantId: 'p1', task: '초안 작성', dueDate: '2026-10-20' }], followUps: ['다음 주 확인'],
    },
    allowedActions: ['UPDATE_SPEAKER_MAPPING', 'UPDATE_MINUTES'],
  } } }));

  await page.goto('/meetings/mobile-review/review');
  await expect(page.getByLabel('회의 요약')).toHaveValue('주간 회의 요약');
  await expect(page.getByLabel('할 일 1')).toHaveValue('초안 작성');
  await expect(page.getByLabel('담당자 1')).toHaveValue('p1');
  const transcriptButton = page.getByRole('button', { name: 'Transcript 원문 보기' });
  await transcriptButton.click();
  const drawer = page.getByRole('dialog', { name: 'Transcript 원문' });
  await expect(drawer.getByText('00:01–00:02')).toBeVisible();
  await expect(drawer.getByText('01:05–01:07')).toBeVisible();
  await expect(drawer.getByText('첫 번째 발언')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(drawer).toHaveCount(0);
  await expect(transcriptButton).toBeFocused();

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
      const rect = document.querySelector('#speaker-speaker-a')!.getBoundingClientRect();
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

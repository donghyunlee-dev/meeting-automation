import { expect, test, type Page } from '@playwright/test';

const sessionId = 'viewport-session';
const longKoreanTitle = '분기별 제품 전략과 고객 지원 운영 현황을 함께 검토하는 장시간 회의 제목 예시';
const longParticipantName = '김가나다라마바사아자차카타파하 고객성공운영담당자 '.repeat(4).trim();
const longActionItem = '이번 분기 고객 의견과 운영 지표를 다시 검토하고 다음 회의 전까지 팀별 실행 계획과 담당 일정을 정리합니다.';

const minutes = {
  templateId: 'weekly', templateVersion: '1', summary: '분기별 운영 계획 요약',
  discussionPoints: ['고객 지원 운영 현황과 다음 분기 개선 방향'],
  decisions: ['다음 회의 전까지 실행 계획을 정리합니다.'],
  actionItems: [{ ownerParticipantId: 'participant-1', task: longActionItem, dueDate: '2026-10-20' }],
  followUps: ['담당별 진행 상황을 확인합니다.'],
};

const transcript = [{
  segmentId: 'segment-1', speakerId: 'speaker-1', startMs: 1_000, endMs: 9_000,
  text: '회의에서 논의한 주요 내용과 결정 사항을 확인합니다. '.repeat(8),
}];

const participant = { id: 'participant-1', name: longParticipantName, email: 'fixture@example.test' };
const graceParticipant = { id: 'participant-2', name: 'Grace Hopper', email: 'grace@example.test' };
const meetingFixtures = [
  { documentId: 'fixture-document', title: '제품 전략 회의', meetingAt: '2026-10-14T12:00:00Z', participants: [{ id: participant.id, name: 'Ada Lovelace' }], documentUrl: 'https://example.test/fixture-document' },
  { documentId: 'older-document', title: '고객 지원 주간 회의', meetingAt: '2026-10-10T12:00:00Z', participants: [{ id: graceParticipant.id, name: graceParticipant.name }], documentUrl: 'https://example.test/older-document' },
];

const review = {
  sessionId, version: 3, status: 'REVIEW', meeting: { participantIds: [participant.id] },
  speakers: [{ speakerId: 'speaker-1', label: 'Speaker A', participantId: participant.id }],
  transcript, minutes, allowedActions: ['UPDATE_SPEAKER_MAPPING', 'UPDATE_MINUTES', 'CONFIRM'],
};

const detail = {
  documentId: 'fixture-document', title: longKoreanTitle, meetingAt: '2026-10-14T09:00:00Z',
  participants: [{ id: participant.id, name: longParticipantName }], minutes, transcript,
  documentUrl: 'https://example.test/fixture-document',
};

const publishSnapshot = (status: string) => ({
  sessionId, version: 3, status, allowedActions: [], meeting: { participantIds: [participant.id] },
  review, deliveries: [
    { deliveryId: 'fixture-email', channel: 'EMAIL', recipientParticipantId: participant.id, status: 'SENT' },
    { deliveryId: 'fixture-notification', channel: 'NOTIFICATION', status: 'FAILED', retryable: true, errorCode: 'DELIVERY_FAILED' },
  ], document: { documentId: 'fixture-document', documentUrl: 'https://example.test/fixture-document' },
});

type Screen = { id: string; route: string; heading: RegExp; headingLevel?: number; contentSelector: string; paddingSelector: string; apiState?: 'processing' | 'processing-failure' | 'review' | 'complete' };

type CapturedRequests = { meetingCreates: number; confirmBodies: unknown[]; publishBodies: unknown[]; retryDeliveryIds: string[] };
type SyntheticFixtureOptions = {
  participants?: typeof participant[];
  meetings?: typeof meetingFixtures;
  completeAfterPublish?: boolean;
  captured?: CapturedRequests;
};

function requestCapture(): CapturedRequests {
  return { meetingCreates: 0, confirmBodies: [], publishBodies: [], retryDeliveryIds: [] };
}

const screens: Screen[] = [
  { id: 'SCR-001', route: '/', heading: /Meeting Automation/, contentSelector: '.home-content', paddingSelector: '.home-page' },
  { id: 'SCR-002', route: '/meetings/new', heading: /새 회의/, contentSelector: '.new-meeting-page', paddingSelector: '.new-meeting-page' },
  { id: 'SCR-003', route: `/meetings/${sessionId}/recording`, heading: new RegExp(longKoreanTitle), contentSelector: '.recording-page', paddingSelector: '.recording-page' },
  { id: 'SCR-005', route: `/meetings/${sessionId}/processing`, heading: /회의 처리/, contentSelector: '.processing-page', paddingSelector: '.processing-page', apiState: 'processing' },
  { id: 'SCR-006', route: `/meetings/${sessionId}/review`, heading: /회의록 검토/, contentSelector: '.review-speaker-page', paddingSelector: '.review-speaker-page', apiState: 'review' },
  { id: 'SCR-007', route: `/meetings/${sessionId}/share`, heading: /회의록 공유/, contentSelector: '.share-page', paddingSelector: '.share-page', apiState: 'review' },
  { id: 'SCR-008', route: `/meetings/${sessionId}/complete`, heading: /회의 결과/, contentSelector: '.share-page.complete-page', paddingSelector: '.complete-page', apiState: 'complete' },
  { id: 'SCR-009', route: '/meetings', heading: /회의록 목록|회의록/, contentSelector: '.meetings-page', paddingSelector: '.meetings-page' },
  { id: 'SCR-010', route: '/meetings/fixture-document', heading: new RegExp(longKoreanTitle), contentSelector: '.meeting-detail-page', paddingSelector: '.meeting-detail-page' },
  { id: 'SCR-011', route: '/settings', heading: /설정/, contentSelector: '.settings-page', paddingSelector: '.settings-page' },
  { id: 'SCR-012', route: '/settings/participants', heading: /참석자/, headingLevel: 2, contentSelector: '.participants-page', paddingSelector: '.participants-page' },
];

const widths = [360, 375, 390, 430, 768, 1280];

async function installSyntheticApi(page: Page, screen: Screen, options: SyntheticFixtureOptions = {}): Promise<void> {
  let publishAccepted = false;
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    const method = route.request().method();
    let data: unknown = { items: [] };
    let status = 200;

    if (path.endsWith('/document-setup')) {
      data = { version: 1, status: 'READY', active: { provider: 'NOTION', connectionVersion: 1, rootUrl: 'https://example.test' }, draft: null, operation: null, canCreateMeeting: true };
    } else if (path.endsWith('/app-config')) {
      data = {
        company: { id: 'fixture-company', name: '예시 회사', timezone: 'Asia/Seoul' },
        document: { provider: 'NOTION', configured: true, connectionVersion: 1, rootUrl: 'https://example.test' },
        email: { enabled: true, configured: true }, notification: { provider: 'SLACK', enabled: true, configured: true },
        setup: { status: 'READY', required: false, canCreateMeeting: true, operationId: null },
      };
    } else if (path.endsWith('/integrations/health')) {
      data = {
        document: { provider: 'NOTION', configured: true, reachable: true, rootAccessible: true },
        email: { configured: true, reachable: true }, notification: { provider: 'SLACK', configured: true, reachable: true },
      };
    } else if (path.endsWith('/templates')) {
      data = { items: [{ id: 'weekly', name: '주간 회의', version: '1' }] };
    } else if (path.endsWith('/participants')) {
      data = { items: options.participants ?? [participant] };
    } else if (path.endsWith('/meetings')) {
      data = { items: options.meetings ?? [meetingFixtures[0]] };
    } else if (path === '/api/v1/meetings/fixture-document') {
      data = detail;
    } else if (path === `/api/v1/meeting-sessions/${sessionId}`) {
      if (screen.apiState === 'processing') {
        data = { sessionId, version: 3, status: 'PROCESSING', processing: { stage: 'TRANSCRIPTION', progressPercent: 55 }, allowedActions: [] };
      } else if (screen.apiState === 'processing-failure') {
        data = {
          sessionId, version: 3, status: 'PROCESSING_FAILED',
          processing: { stage: 'TRANSCRIPTION', retryable: true, audioAvailable: true, audioExpiresAt: '2026-10-20T12:00:00Z' },
          allowedActions: ['RETRY_PROCESSING', 'DOWNLOAD_AUDIO', 'FINALIZE_PROCESSING_FAILURE'],
        };
      } else if (options.completeAfterPublish && publishAccepted) {
        data = publishSnapshot('COMPLETED_WITH_WARNINGS');
      } else if (screen.apiState === 'complete') {
        data = publishSnapshot('COMPLETED_WITH_WARNINGS');
      } else {
        data = review;
      }
    } else if (path === `/api/v1/meeting-sessions/${sessionId}/confirm` && method === 'POST') {
      options.captured?.confirmBodies.push(route.request().postDataJSON());
      data = { sessionId, status: 'CONFIRMED', version: 4 };
    } else if (path === `/api/v1/meeting-sessions/${sessionId}/publish` && method === 'POST') {
      options.captured?.publishBodies.push(route.request().postDataJSON());
      publishAccepted = true;
      status = 202;
      data = { sessionId, status: 'PUBLISHING' };
    } else if (path === `/api/v1/meeting-sessions/${sessionId}/deliveries/fixture-notification/retry` && method === 'POST') {
      options.captured?.retryDeliveryIds.push('fixture-notification');
      status = 202;
      data = { deliveryId: 'fixture-notification', status: 'PENDING' };
    } else if (path === '/api/v1/meeting-sessions' && method === 'POST') {
      if (options.captured) options.captured.meetingCreates += 1;
      status = 201;
      data = { sessionId, version: 1, status: 'CREATED', uploadPolicy: { chunkDurationSeconds: 30, maxChunkBytes: 1_000_000, acceptedMimeTypes: ['audio/webm'] } };
    } else if (path === `/api/v1/meeting-sessions/${sessionId}/publish`) {
      data = publishSnapshot('REVIEW');
    }

    await route.fulfill({ status, json: { data } });
  });
}

for (const screen of screens) {
  test.describe(`${screen.id} ${screen.route}`, () => {
    for (const width of widths) {
      test(`${width}px renders core content without page overflow or clipped bounds`, async ({ page }) => {
        const runtimeErrors: string[] = [];
        page.on('pageerror', (error) => runtimeErrors.push(error.message));
        await page.setViewportSize({ width, height: width <= 430 ? 800 : 900 });
        if (screen.id === 'SCR-003') {
          await page.addInitScript((session) => sessionStorage.setItem('meeting-automation.recording-session', JSON.stringify(session)), {
            sessionId, version: 3, status: 'CREATED', title: longKoreanTitle,
            uploadPolicy: { chunkDurationSeconds: 30, maxChunkBytes: 1_000_000, acceptedMimeTypes: ['audio/webm'] },
          });
        }
        await installSyntheticApi(page, screen);
        await page.goto(screen.route);
        if (screen.id === 'SCR-002') await page.getByLabel('회의 제목').fill(longKoreanTitle);

        const main = page.locator('main').first();
        await expect(main).toBeVisible();
        const heading = main.getByRole('heading', { level: screen.headingLevel ?? 1 }).first();
        await expect(heading).toBeVisible();
        await expect(heading).toContainText(screen.heading);

        const geometry = await page.evaluate((selectors) => {
          const headingElement = document.querySelector(`main h${selectors.headingLevel}`);
          const content = document.querySelector(selectors.contentSelector);
          const padding = document.querySelector(selectors.paddingSelector);
          if (!headingElement || !content || !padding) throw new Error('Expected screen heading, content and padding elements');
          const headingRect = headingElement.getBoundingClientRect();
          const contentRect = content.getBoundingClientRect();
          const paddingStyle = getComputedStyle(padding);
          return {
            viewport: document.documentElement.clientWidth,
            rootScroll: document.documentElement.scrollWidth,
            bodyScroll: document.body.scrollWidth,
            heading: { left: headingRect.left, right: headingRect.right, width: headingRect.width, height: headingRect.height },
            content: { left: contentRect.left, right: contentRect.right, width: contentRect.width },
            padding: { left: Number.parseFloat(paddingStyle.paddingLeft), right: Number.parseFloat(paddingStyle.paddingRight) },
          };
        }, { headingLevel: screen.headingLevel ?? 1, contentSelector: screen.contentSelector, paddingSelector: screen.paddingSelector });

        expect(geometry.rootScroll, `${screen.id} root overflow at ${width}px: ${JSON.stringify(geometry)}`).toBeLessThanOrEqual(geometry.viewport);
        expect(geometry.bodyScroll, `${screen.id} body overflow at ${width}px: ${JSON.stringify(geometry)}`).toBeLessThanOrEqual(geometry.viewport);
        expect(geometry.heading.width, `${screen.id} heading should have a measurable box`).toBeGreaterThan(0);
        expect(geometry.heading.height, `${screen.id} heading should have a measurable box`).toBeGreaterThan(0);
        expect(geometry.heading.left, `${screen.id} heading begins outside the viewport`).toBeGreaterThanOrEqual(0);
        expect(geometry.heading.right, `${screen.id} heading ends outside the viewport`).toBeLessThanOrEqual(width);
        expect(geometry.content.left, `${screen.id} main content begins outside the viewport`).toBeGreaterThanOrEqual(0);
        expect(geometry.content.right, `${screen.id} main content ends outside the viewport`).toBeLessThanOrEqual(width);
        if (width <= 430) {
          expect(geometry.padding.left, `${screen.id} mobile left padding is below 16px`).toBeGreaterThanOrEqual(16);
          expect(geometry.padding.right, `${screen.id} mobile right padding is below 16px`).toBeGreaterThanOrEqual(16);
        }
        const coreControl = main.locator('a[href], button:not([disabled]), input:not([type="hidden"])').first();
        if (await coreControl.count()) {
          await expect(coreControl).toBeVisible();
          const controlBounds = await coreControl.boundingBox();
          expect(controlBounds).not.toBeNull();
          expect(controlBounds!.x, `${screen.id} core control begins outside the viewport`).toBeGreaterThanOrEqual(0);
          expect(controlBounds!.x + controlBounds!.width, `${screen.id} core control ends outside the viewport`).toBeLessThanOrEqual(width);
        }
        if (width >= 768) {
          expect(geometry.content.width, `${screen.id} desktop content exceeds 640px`).toBeLessThanOrEqual(640);
          expect(Math.abs((geometry.content.left + geometry.content.right) / 2 - width / 2), `${screen.id} desktop content is not centered`).toBeLessThanOrEqual(2);
        }
        expect(runtimeErrors, `${screen.id} raised browser runtime errors`).toEqual([]);
      });
    }
  });
}

test('SCR-002 shows title and attendee validation, then selects a fixture attendee', async ({ page }) => {
  const captured = requestCapture();
  await page.setViewportSize({ width: 360, height: 800 });
  await installSyntheticApi(page, screens[1], { captured });
  await page.goto('/meetings/new');

  await page.getByRole('button', { name: '회의 시작' }).click();
  const title = page.getByLabel('회의 제목');
  const attendeePicker = page.getByRole('button', { name: '참석자 추가' });
  await expect(title).toHaveAttribute('aria-invalid', 'true');
  await expect(page.locator('#meeting-title-error')).toHaveText('회의 제목을 입력해 주세요.');
  await expect(attendeePicker).toHaveAttribute('aria-invalid', 'true');
  await expect(page.locator('#meeting-participants-error')).toHaveText('참석자를 한 명 이상 추가해 주세요.');
  expect(captured.meetingCreates).toBe(0);

  await title.fill('기획 주간 회의');
  await attendeePicker.click();
  const attendee = page.getByRole('checkbox', { name: `${participant.name} ${participant.email}` });
  await attendee.check();
  await expect(attendee).toBeChecked();
  await expect(page.getByRole('list', { name: '선택한 참석자' })).toContainText(participant.name);
  await expect(page.locator('#meeting-title-error')).toHaveCount(0);
  await expect(page.locator('#meeting-participants-error')).toHaveCount(0);
  expect(captured.meetingCreates).toBe(0);
});

test('SCR-005 shows a processing failure and opens the discard confirmation before finalizing', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  const failureScreen: Screen = { ...screens[3], apiState: 'processing-failure' };
  await installSyntheticApi(page, failureScreen);
  await page.goto(failureScreen.route);

  const failure = page.getByRole('region', { name: '처리를 완료하지 못했습니다' });
  await expect(failure).toBeVisible();
  await expect(failure).toContainText('음성을 변환하는 중 문제가 발생했습니다.');
  await expect(failure).toContainText('실패 단계: 음성 인식');
  await expect(failure).toContainText('회의 원문과 제공자 오류 내용은 이 화면에 표시하지 않습니다.');
  await expect(failure.getByRole('button', { name: '다시 변환' })).toBeVisible();
  await expect(failure.getByRole('button', { name: '오디오 다운로드 준비' })).toBeVisible();

  await failure.getByRole('button', { name: '오디오 없이 실패 마무리' }).click();
  const confirmation = page.getByRole('dialog', { name: '오디오 없이 마무리' });
  await expect(confirmation).toBeVisible();
  await expect(confirmation).toContainText('오디오를 보관하지 않고 실패 회의 기록을 저장합니다.');
  await expect(confirmation.getByRole('button', { name: '실패 마무리' })).toBeVisible();
  await confirmation.getByRole('button', { name: '취소' }).click();
  await expect(confirmation).toHaveCount(0);
  await expect(failure.getByRole('button', { name: '오디오 없이 실패 마무리' })).toBeVisible();
});

test('SCR-009 filters synthetic meetings by title and date and reports an invalid date range', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 800 });
  await installSyntheticApi(page, screens[7], { meetings: meetingFixtures });
  await page.goto('/meetings');

  const productMeeting = page.locator('.meeting-summary-row').filter({ hasText: '제품 전략 회의' });
  const supportMeeting = page.locator('.meeting-summary-row').filter({ hasText: '고객 지원 주간 회의' });
  await expect(productMeeting).toBeVisible();
  await expect(supportMeeting).toBeVisible();

  await page.getByLabel('회의 검색').fill('제품 전략');
  await expect(productMeeting).toBeVisible();
  await expect(supportMeeting).toHaveCount(0);
  await page.getByLabel('회의 검색').fill('');
  await expect(supportMeeting).toBeVisible();

  await page.getByLabel('시작일').fill('2026-10-13');
  await page.getByLabel('종료일').fill('2026-10-15');
  await expect(productMeeting).toBeVisible();
  await expect(supportMeeting).toHaveCount(0);

  await page.getByLabel('시작일').fill('2026-10-20');
  await page.getByLabel('종료일').fill('2026-10-19');
  await expect(page.getByRole('alert')).toHaveText('시작일은 종료일보다 늦을 수 없습니다. 날짜 범위는 적용하지 않았습니다.');
  await expect(page.getByLabel('시작일')).toHaveAttribute('aria-invalid', 'true');
  await expect(productMeeting).toBeVisible();
  await expect(supportMeeting).toBeVisible();

  await page.getByRole('button', { name: '필터 초기화' }).first().click();
  await expect(page.getByLabel('회의 검색')).toHaveValue('');
  await expect(page.getByLabel('시작일')).toHaveValue('');
  await expect(page.getByLabel('종료일')).toHaveValue('');
  await expect(productMeeting).toBeVisible();
  await expect(supportMeeting).toBeVisible();
});

test('SCR-012 searches the roster and clears the query to restore both fixture participants', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installSyntheticApi(page, screens[10], { participants: [participant, graceParticipant] });
  await page.goto('/settings/participants');

  const ada = page.locator('.participant-card').filter({ hasText: participant.email });
  const grace = page.locator('.participant-card').filter({ hasText: graceParticipant.email });
  await expect(ada).toBeVisible();
  await expect(grace).toBeVisible();
  await page.getByRole('searchbox', { name: '참석자 검색' }).fill('Grace');
  await expect(grace).toBeVisible();
  await expect(ada).toHaveCount(0);
  await page.getByRole('button', { name: '검색 입력 지우기' }).click();
  await expect(page.getByRole('searchbox', { name: '참석자 검색' })).toHaveValue('');
  await expect(ada).toBeVisible();
  await expect(grace).toBeVisible();
});

test('SCR-006 primary action carries the unchanged Review snapshot to Share', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installSyntheticApi(page, screens[4]);
  await page.goto(screens[4].route);

  const shareButton = page.getByRole('button', { name: '저장하고 공유' });
  await expect(shareButton).toBeEnabled();
  await shareButton.click();
  await expect(page).toHaveURL(new RegExp(`/meetings/${sessionId}/share$`));
  await expect(page.getByRole('heading', { name: '회의록 공유' })).toBeVisible();
  await expect(page.getByLabel(`${participant.name} ${participant.email}`)).toBeChecked();
});

test('SCR-007 primary action confirms the Review and publishes the selected channels', async ({ page }) => {
  const captured = requestCapture();
  await page.setViewportSize({ width: 360, height: 800 });
  await installSyntheticApi(page, screens[5], { completeAfterPublish: true, captured });
  await page.goto(screens[5].route);

  const primaryAction = page.getByRole('button', { name: '저장하고 공유' });
  await expect(primaryAction).toBeEnabled();
  await primaryAction.click();
  await expect.poll(() => captured.confirmBodies.length).toBe(1);
  await expect.poll(() => captured.publishBodies.length).toBe(1);
  expect(captured.confirmBodies[0]).toEqual({ confirm: true });
  expect(captured.publishBodies[0]).toEqual({ emailRecipientParticipantIds: [participant.id], notificationEnabled: true });
  await expect(page.getByText('문서 저장이 완료되었습니다. 채널별 결과는 상태 확인에서 갱신됩니다.')).toBeVisible();
});

test('SCR-008 retries only the failed notification delivery from the result screen', async ({ page }) => {
  const captured = requestCapture();
  await page.setViewportSize({ width: 360, height: 800 });
  await installSyntheticApi(page, screens[6], { captured });
  await page.goto(screens[6].route);

  await expect(page.getByRole('heading', { name: '일부 전달 실패' })).toBeVisible();
  await expect(page.getByText(`${participant.name}: 전송 요청 수락`)).toBeVisible();
  const retry = page.getByRole('button', { name: 'Retry Slack 알림' });
  await expect(retry).toBeVisible();
  await retry.click();
  await expect.poll(() => captured.retryDeliveryIds.length).toBe(1);
  expect(captured.retryDeliveryIds).toEqual(['fixture-notification']);
  await expect(page.getByRole('status')).toContainText('Slack 알림 재시도가 접수되었습니다.');
  await expect(page.getByRole('button', { name: `Retry ${participant.name}` })).toHaveCount(0);
});

for (const width of [360, 375]) {
  test(`SCR-004 End Confirm fits ${width}px viewport`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 });
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
    }, { sessionId, version: 3, status: 'CREATED', title: longKoreanTitle, uploadPolicy: { chunkDurationSeconds: 30, maxChunkBytes: 1_000_000, acceptedMimeTypes: ['audio/webm'] } });
    await page.route('**/api/v1/**', (route) => route.fulfill({ json: { data: { receivedSequences: [] } } }));
    await page.goto(`/meetings/${sessionId}/recording`);
    await page.getByRole('button', { name: '녹음 시작' }).click();
    const endButton = page.getByRole('button', { name: '회의 종료' });
    await expect(endButton).toBeVisible();
    await endButton.click();
    const dialog = page.getByRole('dialog', { name: '회의를 종료할까요?' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('button', { name: '계속 녹음' })).toBeVisible();
    await expect(dialog.getByRole('button', { name: '회의 종료' })).toBeVisible();
    const bounds = await dialog.boundingBox();
    expect(bounds).not.toBeNull();
    expect(bounds!.x).toBeGreaterThanOrEqual(0);
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
  });
}

for (const width of [360, 375]) {
  test(`SCR-006 Transcript drawer fits ${width}px viewport`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 });
    await installSyntheticApi(page, { id: 'SCR-006', route: `/meetings/${sessionId}/review`, heading: /회의록 검토/, contentSelector: '.review-speaker-page', paddingSelector: '.review-speaker-page', apiState: 'review' });
    await page.goto(`/meetings/${sessionId}/review`);
    const openDrawer = page.getByRole('button', { name: /Transcript.*원문 보기/ });
    await expect(openDrawer).toBeVisible();
    await openDrawer.click();
    const drawer = page.getByRole('dialog', { name: 'Transcript' });
    await expect(drawer).toBeVisible();
    await expect(drawer.getByText(transcript[0].text)).toBeVisible();
    const bounds = await drawer.boundingBox();
    expect(bounds).not.toBeNull();
    expect(bounds!.x).toBeGreaterThanOrEqual(0);
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await expect(drawer.locator('button[aria-label*="닫기"]')).toBeVisible();
  });
}

import type { Page } from '@playwright/test';


export const sessionId = 'viewport-session';
export const longKoreanTitle = '분기별 제품 전략과 고객 지원 운영 현황을 함께 검토하는 장시간 회의 제목 예시';
export const longParticipantName = '김가나다라마바사아자차카타파하 고객성공운영담당자 '.repeat(4).trim();
export const longActionItem = '이번 분기 고객 의견과 운영 지표를 다시 검토하고 다음 회의 전까지 팀별 실행 계획과 담당 일정을 정리합니다.';

export const minutes = {
  templateId: 'weekly', templateVersion: '1', summary: '분기별 운영 계획 요약',
  discussionPoints: ['고객 지원 운영 현황과 다음 분기 개선 방향'],
  decisions: ['다음 회의 전까지 실행 계획을 정리합니다.'],
  actionItems: [{ ownerParticipantId: 'participant-1', task: longActionItem, dueDate: '2026-10-20' }],
  followUps: ['담당별 진행 상황을 확인합니다.'],
};

export const transcript = [{
  segmentId: 'segment-1', speakerId: 'speaker-1', startMs: 1_000, endMs: 9_000,
  text: '회의에서 논의한 주요 내용과 결정 사항을 확인합니다. '.repeat(8),
}];

export const participant = { id: 'participant-1', name: longParticipantName, email: 'fixture@example.test' };
export const graceParticipant = { id: 'participant-2', name: 'Grace Hopper', email: 'grace@example.test' };
export const meetingFixtures = [
  { documentId: 'fixture-document', title: '제품 전략 회의', meetingAt: '2026-10-14T12:00:00Z', participants: [{ id: participant.id, name: 'Ada Lovelace' }], documentUrl: 'https://example.test/fixture-document' },
  { documentId: 'older-document', title: '고객 지원 주간 회의', meetingAt: '2026-10-10T12:00:00Z', participants: [{ id: graceParticipant.id, name: graceParticipant.name }], documentUrl: 'https://example.test/older-document' },
];

export const review = {
  sessionId, version: 3, status: 'REVIEW', meeting: { participantIds: [participant.id] },
  speakers: [{ speakerId: 'speaker-1', label: 'Speaker A', participantId: participant.id }],
  transcript, minutes, allowedActions: ['UPDATE_SPEAKER_MAPPING', 'UPDATE_MINUTES', 'CONFIRM'],
};

export const detail = {
  documentId: 'fixture-document', title: longKoreanTitle, meetingAt: '2026-10-14T09:00:00Z',
  participants: [{ id: participant.id, name: longParticipantName }], minutes, transcript,
  documentUrl: 'https://example.test/fixture-document',
};

export const publishSnapshot = (status: string) => ({
  sessionId, version: 3, status, allowedActions: [], meeting: { participantIds: [participant.id] },
  review, deliveries: [
    { deliveryId: 'fixture-email', channel: 'EMAIL', recipientParticipantId: participant.id, status: 'SENT' },
    { deliveryId: 'fixture-notification', channel: 'NOTIFICATION', status: 'FAILED', retryable: true, errorCode: 'DELIVERY_FAILED' },
  ], document: { documentId: 'fixture-document', documentUrl: 'https://example.test/fixture-document' },
});

export type Screen = { id: string; route: string; heading: RegExp; headingLevel?: number; contentSelector: string; paddingSelector: string; apiState?: 'processing' | 'processing-failure' | 'review' | 'complete' };

export type CapturedRequests = { meetingCreates: number; confirmBodies: unknown[]; publishBodies: unknown[]; retryDeliveryIds: string[] };
export type SyntheticFixtureOptions = {
  participants?: typeof participant[];
  meetings?: typeof meetingFixtures;
  completeAfterPublish?: boolean;
  captured?: CapturedRequests;
};

export function requestCapture(): CapturedRequests {
  return { meetingCreates: 0, confirmBodies: [], publishBodies: [], retryDeliveryIds: [] };
}

export const screens: Screen[] = [
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

export const widths = [360, 375, 390, 430, 768, 1280];

export async function installSyntheticApi(page: Page, screen: Screen, options: SyntheticFixtureOptions = {}): Promise<void> {
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

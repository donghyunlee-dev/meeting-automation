# Admin Slack 안전 오류 알림 설계

## 작업 식별 정보

- 작업: TASK-016.02
- 상위 작업: TASK-016 Error / Admin Slack
- 단계: Phase 7 — History / Settings / Operations
- PRD 기준: PRD-MA-001 v1.7.0, 2026-10-05
- 관련 기준: DEC-019, FR-029, EXT-005
- 영역: Backend
- 선행 작업: TASK-016.01 (#57), TASK-012.01 (#48)
- GitHub Issue: [#58](https://github.com/donghyunlee-dev/meeting-automation/issues/58)

## 결과

정규화된 업무 FailureContext를 별도 SLACK_ADMIN_WEBHOOK_URL Incoming Webhook으로 전달한다. 이는 원 업무 실패와 분리된 best-effort side effect이며 Slack 전송 오류는 사용자 요청/실패 상태를 덮어쓰지 않는다.

## Payload 및 전송

- 허용 incidentType은 PROCESSING_FAILURE, DOCUMENT_FAILURE, EMAIL_FAILURE, NOTIFICATION_FAILURE다.
- Payload에 incidentType, 존재하는 경우 sessionId/stage, traceId, 고정 safe message, retryable, occurredAt만 포함한다. errorCode는 내부 correlation에만 남긴다.
- HTTPS JSON POST의 text message로 고정 channel에 게시한다. Plain text로 표시하고 제어문자/줄바꿈 및 Slack markup/mention 동작을 정규화한다.
- 업무 failure event당 webhook POST 한 번이다. 설정이 없거나 무효하면 POST하지 않는다.
- Admin URL은 SLACK_ADMIN_WEBHOOK_URL이며 Meeting Notification의 SLACK_MEETING_WEBHOOK_URL과 config/client/payload를 분리한다.
- HTTP 200 및 body ok는 Slack 수락을 뜻하며 보관/읽음을 보장하지 않는다.
- 전송 실패는 safe result/log로만 남기며 원래 업무 error/status/API response를 바꾸지 않는다. webhook failure가 다시 Admin incident를 만들지 않는다.
- 자동 retry/queue replay는 없다. timeout/reset/응답 유실은 게시 여부 불명으로 남기고 재전송하지 않는다.

## 보안

Audio, Transcript, Minutes, 전체 Email 주소, Secret, token, webhook URL, Provider raw error/HTTP body, stack trace, 임의 exception text를 금지한다. Request/response body와 webhook URL을 로그에 쓰지 않는다. Safe correlation에는 traceId/sessionId/stage/errorCode만 남긴다.

## 비범위

- Meeting Notification, API-019 notification health, User Delivery/API-016 retry는 변경하지 않는다.
- OAuth/Bot/chat.postMessage/channel picker, Admin retry dashboard, persistent queue/store는 추가하지 않는다.
- Admin 전송은 별도의 사용자 화면이나 delivery row를 만들지 않는다.

## 완료 기준

- 네 업무 분류의 approved fields가 Admin webhook message로 전송된다.
- Meeting notification URL/config/payload와 분리되어 있다.
- 설정 누락/거절/timeout에도 원 업무 failure가 보존되고 자동 retry가 없다.
- Recursive incident reporting 및 중복 전송이 없고 로그/payload redaction이 통과한다.
- 검증 사례는 [검증 계획](./test.md)에 연결된다.

## 공식 참조

- [Slack Incoming Webhooks](https://api.slack.com/messaging/webhooks)

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

# Slack Incoming Webhook Notification Adapter

## 목표

저장된 Meeting의 제목, 날짜, Document URL을 Slack Incoming Webhook으로 게시하고 Provider에 종속되지 않는 Notification Delivery 결과를 돌려준다. PRD v1.7.0 (2026-10-05), `DEC-015`, `FR-018`, `EXT-005`를 구체화한다. Issue [#48](https://github.com/donghyunlee-dev/meeting-automation/issues/48).

## 범위

- `NOTIFICATION_PROVIDER=SLACK`, `SLACK_MEETING_WEBHOOK_URL` 기반 adapter 설정
- `NotificationProvider.validateConnection()` 및 `sendMeetingPublished(command)` 구현
- Slack Incoming Webhook JSON payload와 HTTP 응답 mapping
- 일반 Meeting Notification의 공통 Delivery 결과/재시도 가능 여부
- webhook secret, 회의 내용, HTTP 오류 원문의 redaction
- Mock HTTP 기반 adapter/Provider contract tests

## 비범위

- API-015 publish pipeline에서 adapter를 실행하는 orchestration/UI는 TASK-012.02, recipient/status presentation은 TASK-013.02가 소유한다.
- Admin Incident Slack webhook (`SLACK_ADMIN_WEBHOOK_URL`) 동작은 바꾸지 않는다. 이 adapter는 Meeting notification URL만 사용한다.
- Slack OAuth install flow, Bot token, 채널 설정 UI, `chat.postMessage` API는 사용하지 않는다. Incoming Webhook URL이 이미 고정 channel로 설치된 V1 설정 방식이다.
- Email 결과 조정 및 API-016 delivery retry는 인접 TASK 범위다.

## 설정과 채널 의미

```text
NOTIFICATION_PROVIDER=SLACK
SLACK_MEETING_WEBHOOK_URL=<secret incoming webhook URL>
```

Slack Incoming Webhook URL은 설치할 때 지정된 channel로 묶인다. Adapter는 payload에서 채널, 사용자명, 아이콘을 바꾸지 않으며 SCR-007 `notificationEnabled=true`인 경우에만 application이 호출한다. URL은 Backend runtime secret/config에서만 사용한다.

Incoming Webhook에 부작용 없이 유효성 확인할 Slack endpoint는 제공되지 않으므로 `validateConnection()`은 시험 알림을 게시하지 않는다. `configured=true`는 provider/HTTPS webhook config가 있고 host/path가 Slack Incoming Webhook 형식인 경우다. `reachable`은 가장 최근 실제 POST가 Slack의 성공 응답을 받았으면 true, 최근 요청이 실패했거나 아직 시도 전이면 false다. 프로세스 재시작 후 최근 네트워크 결과는 초기화된다. Health는 URL이 실제로 활성화되어 있음을 사전 보증하지 않는다.

## Port와 payload

```text
sendMeetingPublished(command) -> DeliveryResult

MeetingPublishedCommand = {
  title,
  meetingAt,
  documentUrl
}

DeliveryResult = {
  status: SENT | FAILED,
  retryable: boolean,
  errorCode?: NOTIFICATION_FAILED
}
```

Document reference가 저장된 뒤에만 command가 구성된다. Payload에는 제목, 회의 날짜와 링크만 포함한다. 요약/Minutes, Transcript, 참석자 이름/이메일, OAuth/Secret은 금지한다. V1 메시지는 예측 가능한 일반 텍스트 `text`와 `mrkdwn:false`, `unfurl_links:false` 설정을 사용한다. 회의 제목은 제어 문자/줄바꿈을 정규화하고 Slack mention/markup 구문을 실행하지 않는 일반 텍스트로 전달한다. Document URL은 http/https만 허용하며 그대로 링크 텍스트에 넣는다.

Adapter는 webhook URL로 HTTPS POST `application/json`을 한 번 보낸다. Slack 응답 HTTP 200 및 일반 텍스트 `ok`이면 Delivery `SENT`, `retryable=false`다. 이는 Slack이 webhook message를 접수했다는 뜻이며 실제 채널 보관/구성원 열람을 확인하지 않는다.

## 실패·health·재시도

- HTTP 200 이외 또는 body가 `ok`가 아니면 `FAILED`, `NOTIFICATION_FAILED`, 안전한 오류 메시지만 반환한다.
- 잘못되거나 누락된 webhook 설정은 `configured=false`, `reachable=false`이며 Slack HTTP 요청 없이 해당 Delivery를 `FAILED`, `NOTIFICATION_FAILED`, `retryable=true`로 기록한다. Slack이 수락하지 않은 것이 분명한 무효/폐기 webhook 및 권한 제한 거절도 설정/권한 수정 후 재시도할 수 있도록 `retryable=true`다. HTTP 400 malformed payload는 코드 수정이 필요한 오류이므로 `retryable=false`다.
- Slack의 명시적 HTTP 429는 `retryable=true`로 변환해 후속 `API-016` delivery workflow가 사용자가 요청한 재시도를 허용한다. HTTP 5xx 응답도 Slack이 message를 게시했는지 문서화된 보장이 없으므로 `retryable=false`로 보수 처리한다. Adapter 내부 자동 loop retry는 하지 않는다.
- HTTP timeout/connection reset 또는 응답 본문 유실은 Slack이 이미 post했는지 알 수 없다. Incoming Webhook에는 애플리케이션이 제공하는 idempotency key가 없으므로 `FAILED`, `retryable=false`로 분류해 중복 메시지 자동 retry를 막는다.
- Email 실패/성공 상태, Document saved 상태를 이 Provider가 읽거나 되돌리지 않는다. Channel Delivery 결과는 독립된 모델 기록다.
- `NOTIFICATION_FAILED` 응답 분류는 `NOTIFICATION_FAILURE`다. Slack URL, HTTP 응답 body, Provider request payload는 log/response/Admin incident에 복사하지 않는다.

## 보안

- `SLACK_MEETING_WEBHOOK_URL`은 posting credential이다. API/config 응답, 일반 log, Admin Slack payload, exception message에 원문이나 token 경로를 쓰지 않는다.
- 회의 제목/body content는 trace/error 로그에 출력하지 않는다. notification payload는 필요 필드만 구성한다.
- Admin 오류 처리기가 이 adapter 실패를 보고할 때 허용된 `NOTIFICATION_FAILURE`, sessionId, stage, traceId, 안전한 오류만 사용하고 meeting payload/webhook token은 포함하지 않는다.

## 완료 기준

- 정상 요청에서 제목·회의 날짜·유효 document URL만 고정 Slack webhook으로 게시한다.
- 문서 저장 성공 전에는 adapter 호출이 없고, Email Delivery와 결과 저장은 이 Adapter에서 수행하지 않는다.
- Slack 200 `ok`, invalid config/URL, 429, 4xx, 5xx, timeout/error body를 안정적으로 공통 Delivery 결과로 변환한다.
- timeout에서 결과 확인 없는 재시도하지 않으며 성공 delivery는 다시 전송하지 않는다.
- URL/회의 내용/Provider 응답/log redaction 및 모의 테스트가 통과한다.

## 공식 참조

- [Slack Incoming Webhooks](https://api.slack.com/messaging/webhooks)
- [Slack message structure and formatting](https://api.slack.com/messaging/overview)
- [Slack text escaping rules](https://api.slack.com/docs/attachments)
- [Slack rate-limit responses](https://api.slack.com/apis/rate-limits)

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

# 구현 작업 목록

기준: `TASK-012.01`, PRD v1.7.0 (2026-10-05), `DEC-015`, `FR-018`, `EXT-005`, Issue [#48](https://github.com/donghyunlee-dev/meeting-automation/issues/48).

## 사전 확인

- [ ] `NotificationProvider`, `ProviderHealth`, Delivery model, API-019 mapper와 Admin Slack incident 경로를 조사한다. 완료 증거: Meeting/Admin Webhook 분리 경계를 기록한다.
- [ ] Spring HTTP client 규약, Secret 설정, application factory와 Java 21/Gradle build 경로를 확인한다. 완료 증거: 구현 파일과 명령 목록을 기록한다.
- [ ] Slack webhook endpoint/error 규칙과 현재 `SLACK_MEETING_WEBHOOK_URL` 설정을 대조한다. 완료 증거: host/path/payload/result 계약을 기록한다.

## 구현 단계

- [ ] 정상 200 `ok`, 200 non-ok, 400/403/404/429/5xx, timeout/connection-reset, 설정 오류의 Mock HTTP 계약 test를 먼저 작성한다.
- [ ] Health/config 검사가 Slack POST를 0회 호출하는 test를 작성한다. 완료 결과: Settings 조회가 시험용 message 부작용을 만들지 않는다.
- [ ] Domain 중립 meeting command/result와 Slack Provider factory 연결을 고정한다. 완료 결과: Admin incident DTO/URL이 Meeting Adapter에 주입되지 않는다.
- [ ] Slack payload builder를 구현한다. 완료 결과: 제목/날짜/document URL만 `text`에 포함하고 title newline/mention formatting을 안전하게 처리하며 `mrkdwn=false`, `unfurl_links=false`를 사용한다.
- [ ] Webhook HTTP Adapter를 구현한다. 완료 결과: HTTPS JSON POST 1회, 200+`ok`는 SENT, 명시 거절과 설정 수정 가능한 오류는 retryable, payload 오류 및 불명확한 5xx/timeout은 nonretryable로 분류한다.
- [ ] 불명확한 timeout을 `NOTIFICATION_FAILED/재시도 가능 여부=false`로 기록한다. 완료 결과: 자동 재전송이 없다.
- [ ] 가장 최근 실제 Delivery 결과를 ProviderHealth reachable 관측값에 반영한다. 완료 결과: 아직 전송하지 않았거나 마지막 전송에 실패한 경우 reachable=true가 되지 않는다.
- [ ] API-010 generic notification Delivery 및 Email 독립성 test를 TASK-012.02 계약 모의으로 검증한다.
- [ ] Webhook URL/error body/title/content 가림과 Admin Slack 영향 없음 회귀 test를 실행한다.
- [ ] Backend Java 21/Gradle test/build, 모의 검증, 별도 Slack 시험용 webhook 수동 결과를 Issue #48에 기록한다.

## 완료 확인

- Slack channel/username/icon은 Webhook 설치 설정이 결정하고 Adapter는 바꾸지 않는다.
- Document 저장 성공 전에는 Provider 호출이 0회다.
- Email/Document 결과는 서로 독립이고 `SENT`는 다시 보내지 않는다.
- Slack webhook secret, Minutes/Transcript, Email address 및 원 Slack 응답은 노출되지 않는다.

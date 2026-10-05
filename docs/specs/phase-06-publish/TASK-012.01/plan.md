# 구현 계획

기준: PRD v1.7.0 (2026-10-05), `DEC-015`, `FR-018`, `EXT-005`.

## 의존성

- 선행 Document Publish: TASK-010.02, Issue [#44](https://github.com/donghyunlee-dev/meeting-automation/issues/44)
- 운영 오류 알림 경계: 기존 `EXT-005.sendAdminIncident()` 및 TASK-016.02이며 동작을 바꾸지 않는다.
- 본 작업: [Issue #48](https://github.com/donghyunlee-dev/meeting-automation/issues/48)
- Provider 원본 계약: [integrations.md](../../../product/integrations.md), Backend 설정의 Slack Webhook 항목
- 설계와 검증 기준: [spec.md](./spec.md), [tasks.md](./tasks.md), [test.md](./test.md)

## 변경 경계와 담당

- Domain Port는 `NotificationProvider`, `DeliveryResult`, Provider에 종속되지 않는 `MeetingPublishedCommand`를 제공한다.
- 외부 Adapter는 Slack HTTP 호출과 Webhook payload를 담당하며 `SLACK_MEETING_WEBHOOK_URL`만 사용한다.
- 설정/factory는 `NOTIFICATION_PROVIDER=SLACK`을 검증하고 구현체를 선택한다. Admin Webhook을 혼용하지 않는다.
- `validateConnection()`은 message를 게시하지 않고 설정을 확인한다. 연결 가능 여부는 마지막 실제 전송 결과를 반영하며 시험용 message를 보내지 않는다.
- API-015 coordinator 호출, `notificationEnabled` 검사, Delivery 결과 저장/API-010 반영은 TASK-012.02의 책임이다.
- API-016 retry는 TASK-013.01이 담당하며 실패한 Delivery만 재시도하고 성공 건은 다시 보내지 않는다.

현재 설계 workspace에는 Backend 소스/build 구조가 없다. 구현 시 Java 21/Gradle 기준과 저장소의 Spring HTTP client 규약을 따른다. Slack SDK/Domain 타입을 공통 Provider Port 밖으로 노출하지 않으며, Slack Incoming Webhook은 JSON HTTP POST이므로 Backend 공통 HTTP client를 사용한다.

## 구현 순서

실제 Slack 게시 없이 Webhook 설정과 표준 결과를 검증할 수 있어야 한다. 먼저 Provider 계약과 부작용 경계를 고정한 뒤 HTTP mapper를 연결한다.

1. Backend 구조, 공통 ProviderHealth/Delivery 모델, Secret 설정과 Admin Slack 경로를 조사한다. 결과: Meeting/Admin Webhook의 변경 경계를 기록한다.
2. Provider 계약 테스트를 먼저 작성한다. 설정 누락, `ok`, 비정상 HTTP/error body, timeout, 429, Health 조회 시 시험 발송 없음이 구현 전에 검증된다.
3. Domain 중립 `NotificationProvider`, `MeetingPublishedCommand`, `DeliveryResult` 및 factory 연결을 확인/작성한다. 결과: Adapter command에는 제목/날짜/documentUrl만 전달된다.
4. Slack payload mapper를 구현한다. 결과: 설치된 고정 채널, 안전한 일반 텍스트, URL/제목 처리와 unfurl 설정이 검증된다.
5. HTTP Adapter가 meeting webhook URL로 POST를 한 번 보내고 status/body/Retry-After를 분류한다. `ok`만 `SENT`가 되며 자동 재시도는 없다.
6. `validateConnection()`의 설정 및 최근 전송 결과 의미를 구현한다. Health 조회는 POST나 시험용 message를 만들지 않는다.
7. Secret/Meeting 본문 가림, Admin webhook 분리, API-010 generic `NOTIFICATION` Delivery 연결을 검증한다.
8. Java 21/Gradle Backend 단위/빌드 명령, 모의 HTTP 검증 및 증거를 기록한다. 실제 Slack channel 확인은 별도 시험용 Webhook으로 수행한다.

## 인터페이스 및 불변식

- `EXT-005.sendMeetingPublished(command) -> DeliveryResult`를 유지한다. Slack endpoint, payload type, HTTP 오류는 Adapter 내부에 둔다.
- `MeetingPublishedCommand`에는 title/meetingAt/documentUrl만 넣는다.
- Slack channel은 Incoming Webhook 설치 설정이 정하며 payload에서 channel/user/icon을 덮어쓰지 않는다.
- Adapter timeout/failure는 Email/Document 결과를 바꾸지 않는다.
- Provider 성공 결과는 generic Delivery `SENT`다. 재시도 가능 여부는 API-016의 Delivery별 조건을 따른다.

## 검증 접근

JUnit 5와 Mock HTTP server/client로 endpoint, JSON payload, response mapping, 중복 방지 없는 불명확 결과, Health 조회 부작용 방지, Secret 가림을 각각 검증한다. Slack 실제 게시 시험은 선택적 수동 확인으로만 수행한다. 설계 과정에서는 Backend test를 실행하지 않는다.

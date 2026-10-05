# 구현 계획

기준: PRD v1.7.0 (2026-10-05), `DEC-015`, `FR-018`, `SCR-007`, `API-010`, `API-015`, `EXT-005`. Issue [#49](https://github.com/donghyunlee-dev/meeting-automation/issues/49).

## 의존성과 경계

- Slack Provider Port/HTTP Adapter: TASK-012.01, Issue [#48](https://github.com/donghyunlee-dev/meeting-automation/issues/48)
- API-010 roster 및 generic Delivery: TASK-011.02, Issue [#47](https://github.com/donghyunlee-dev/meeting-automation/issues/47)
- Confirm 후 API-015 접수와 Document 결과/polling: TASK-010.03, Issue [#45](https://github.com/donghyunlee-dev/meeting-automation/issues/45)
- Backend는 Publish orchestration의 알림 분기, Notification Delivery 저장 및 API-010 mapping을 소유한다.
- Frontend는 SCR-007 선택, 설정 안내, API-010 Notification 결과 표시를 소유한다.
- API-016 재시도와 SCR-008 최종 결과 요약은 TASK-013.01/.02를 따른다.

## 구현 순서

Backend 계약 테스트와 Frontend API/component 테스트를 먼저 작성한다. Publish는 Document 완료 이후에만 Slack을 호출해야 하고 API-010이 유일한 결과 원천이므로, Backend 통합 계약을 확정한 다음 SCR-007 표시를 연결한다.

1. API-015/010 DTO, Publish coordinator, Delivery repository/model 및 TASK-012.01 Port를 조사한다. 결과: 현재 orchestration의 각 채널 호출 순서와 API projection 위치를 기록한다.
2. `notificationEnabled=false/true`, Document 실패, Adapter 성공/실패의 Backend 통합 테스트를 작성한다. 결과: 조건마다 기대 호출 수와 Delivery 상태가 고정된다.
3. 알림 미선택 시 Slack을 생략하고 선택 시 Document 성공 뒤 한 번 호출하는 분기를 구현한다. 결과: Email 분기와 상태를 변경하지 않는다.
4. Adapter `DeliveryResult`를 `channel=NOTIFICATION` 공통 Delivery로 변환·저장하고 API-010 projection을 연결한다. 결과: 주소, webhook, 외부 본문 없이 API 계약을 만족한다.
5. SCR-007의 명시 선택값/초기값과 API-019 미설정 안내를 연결한다. 결과: 요청 body에 boolean 선택을 포함하고 `provider=null`이면 Settings 이동을 안내한다.
6. API-010 polling 결과를 SCR-007 Notification 결과 영역에 연결한다. 결과: loading/SENT/FAILED/retryable을 Email recipient 목록과 독립 표시한다.
7. API 응답 지연/유실, 최신 version 비교, 재진입 polling을 검증한다. 결과: 중복 Publish/notification과 상태 역전이 없다.
8. Java 21/Gradle 및 Frontend 저장소의 정확한 검증 명령을 확인해 API/application/component 테스트와 접근성 증거를 Issue #49에 기록한다.

## 데이터/오류 매핑

- API-015 `notificationEnabled=true` + Document saved: `sendMeetingPublished` 결과를 Notification Delivery 하나에 반영한다.
- API-015 `notificationEnabled=false`: Slack 호출 없이 Notification row를 만들지 않는다.
- Document failure: Notification 호출과 row 생성 모두 없다.
- Adapter failure: `NOTIFICATION_FAILED`, Adapter가 반환한 `retryable` 및 안전한 메시지를 기준으로 FAILED row를 기록한다.
- API-010은 `deliveries[]` projection만 노출한다. 세션/응답에는 Slack 원문 payload, webhook URL, 채널명, 전체 오류 본문을 저장하지 않는다.

## 검증 접근

Backend integration test는 fake `NotificationProvider`로 호출 횟수/순서/Delivery mapping을 확인한다. Frontend component/API test는 선택값, API-019 미설정 안내, API-010 상태별 표기와 Email 결과 격리를 확인한다. API-016 재시도와 최종 Complete 화면은 후속 TASK에서 검증한다.

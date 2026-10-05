# 구현 작업 목록

기준: `TASK-012.02`, PRD v1.7.0 (2026-10-05), `DEC-015`, `FR-018`, `SCR-007`, `API-010`, `API-015`, `EXT-005`, Issue [#49](https://github.com/donghyunlee-dev/meeting-automation/issues/49).

## 사전 확인

- [ ] TASK-012.01 `NotificationProvider`/`DeliveryResult`와 설정을 조사한다. 완료 증거: Provider 호출 계약 및 미설정 동작을 기록한다.
- [ ] TASK-011.02의 API-010 `deliveries[]` projection과 Email UI 상태를 조사한다. 완료 증거: Notification row와 Email recipient row의 분리 기준을 기록한다.
- [ ] API-015 coordinator의 Document→Email→Notification 순서 및 오류 경계를 조사한다. 완료 증거: 실제 구현 위치와 호출 순서를 기록한다.
- [ ] SCR-007/API-019 화면 데이터와 상태 갱신 경로를 조사한다. 완료 증거: 선택값, Settings 연결, polling 갱신 위치를 기록한다.

## Backend: 계약 테스트 우선

- [ ] `notificationEnabled=false` 입력 테스트를 작성한다. 기대: Slack 호출 0회, Notification Delivery 0건.
- [ ] true 입력에서 Document 실패 테스트를 작성한다. 기대: Email/Slack 호출 0회, 문서 오류만 유지.
- [ ] Document 성공 뒤 Notification Provider 호출 테스트를 작성한다. 기대: title/meetingAt/documentUrl command 1회 및 독립 Notification Delivery 생성.
- [ ] Provider 성공/실패/timeout mapping 테스트를 작성한다. 기대: 공통 Delivery 상태/errorCode/retryable 매핑, Email/Document 결과 유지.
- [ ] API-010 projection 테스트를 작성한다. 기대: recipient ID 없는 `channel=NOTIFICATION` row와 공통 envelope, 민감정보 부재.
- [ ] 최소 구현으로 coordinator 분기와 Delivery 기록을 연결한다. 기대: 위 테스트가 통과하고 Provider 원문 계약은 변하지 않는다.

## Frontend: 선택/결과 테스트 우선

- [ ] SCR-007 선택값 test를 작성한다. 기대: 초기 선택과 사용자 토글이 명시 boolean으로 API-015에 전달된다.
- [ ] API-019 provider null/unconfigured test를 작성한다. 기대: 전송 선택이 꺼지고 Settings 경로가 제공되며 자동 provider 선택 없음.
- [ ] API-010 Notification 상태 표시 테스트를 작성한다. 기대: PENDING/SENDING/SENT/FAILED 및 retryable 안내가 Email row와 독립 표시된다.
- [ ] 지연/이전 version 응답 test를 작성한다. 기대: 오래된 결과가 최신 화면을 덮지 않는다.
- [ ] 최소 UI 구현을 연결한다. 기대: 접근성 이름, 상태 텍스트/live update, 모바일 너비에서 결과를 확인할 수 있다.
- [ ] Email partial failure와 Slack mixed result 회귀 테스트를 작성한다. 기대: 각 Delivery와 Document 결과가 서로 독립적으로 유지된다.

## 완료 확인

- [ ] 선택 해제, 문서 실패, Provider 성공/실패의 Slack 호출 횟수를 검증한다.
- [ ] API-010 응답에 webhook URL, 채널명, Provider 원문 응답/오류 및 Email 주소가 없음을 확인한다.
- [ ] Complete/retry 조작을 이 작업에 추가하지 않았는지 확인하고 증거를 Issue #49에 기록한다.

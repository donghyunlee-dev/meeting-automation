# 구현 작업 목록

기준: `TASK-013.02`, PRD v1.7.0 (2026-10-05), `SCR-008`, `API-010`, `API-016`, `FR-019`, `FR-027`, Issue [#51](https://github.com/donghyunlee-dev/meeting-automation/issues/51).

## 사전 확인

- [ ] SCR-008 route/component 및 API-010 polling/data types를 조사한다. 완료 증거: route entry/re-entry와 최신 snapshot 취득 위치를 기록한다.
- [ ] API-016 request/response/error 및 TASK-013.01 idempotency 조건을 확인한다. 완료 증거: 새 사용자 동작과 같은 동작 response replay 경계를 기록한다.
- [ ] TASK-011.02 recipient label 선택 및 TASK-012.02 Notification field를 조사한다. 완료 증거: 화면 row별 표시 필드와 개인정보 경계를 기록한다.
- [ ] 기존 loading/live region/button/modal 접근성 패턴과 Frontend test command를 확인한다. 완료 증거: 재사용 component 및 실제 검증 명령을 기록한다.

## 결과 화면: 테스트 우선 구현

- [ ] Summary selector unit test를 작성한다. 기대: all sent, partial failure, all failed, in-progress, deliveries empty를 각각 구분한다.
- [ ] Document row/link component test를 작성한다. 기대: 유효 URL만 열고 저장 실패는 TASK-010.03 경계로 보낸다.
- [ ] Email row test를 작성한다. 기대: participant별 상태/attemptCount를 표시하고 이름이 없으면 ID fallback, 주소는 미표시다.
- [ ] Notification row test를 작성한다. 기대: `NOTIFICATION`만 표시하고 Email 결과와 별도로 갱신한다.
- [ ] 접근성/모바일 test를 작성한다. 기대: 상태 live region, 식별 가능한 row Retry label, 360px/keyboard 동작을 확인한다.
- [ ] 최소 Complete summary/row 구현을 연결한다. 기대: API-010을 기준으로 결과가 렌더링되고 성공을 추정하지 않는다.

## 단건 재시도: 테스트 우선 구현

- [ ] retry eligibility test를 작성한다. 기대: FAILED+retryable true에만 action이 있고 SENT/PENDING/SENDING/false에는 없다.
- [ ] API-016 202 test를 작성한다. 기대: 대상 row만 PENDING으로 바뀌고 API-010 polling이 시작된다.
- [ ] 동일 클릭의 응답 유실/replay test를 작성한다. 기대: 같은 Idempotency-Key가 유지되어 attempt가 중복 생성되지 않는다.
- [ ] 완료된 retry 뒤 다시 FAILED된 Delivery test를 작성한다. 기대: 새 명시 클릭에서만 새 key/attempt가 생긴다.
- [ ] 404/409/API validation 오류 test를 작성한다. 기대: API-010을 재조회하고 완료/다른 row를 바꾸지 않는다.
- [ ] 최종 UI action을 연결한다. 기대: 화면에서 한 번에 한 Delivery만 API-016에 전달한다.

## 완료 확인

- [ ] Document/Email/Slack 혼합 결과와 `deliveries:[]` 결과를 확인한다.
- [ ] retryable false 또는 ambiguous 결과에서는 API-016 호출이 0회인지 확인한다.
- [ ] API-016 202 이후 polling으로 API-010 최종 상태를 표시하고 원문/Secret/Email 주소가 보이지 않는지 확인한다.
- [ ] Browser/accessibility 및 실제 Frontend 명령의 결과를 Issue #51에 기록한다.

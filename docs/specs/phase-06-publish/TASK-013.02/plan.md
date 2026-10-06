# 구현 계획

기준: PRD v1.7.0 (2026-10-05), `SCR-008`, `API-010`, `API-016`, `FR-019`, `FR-027`. Issue [#51](https://github.com/donghyunlee-dev/meeting-automation/issues/51).

## 의존성과 책임

- Backend 단건 재시도 API/idempotency: TASK-013.01, Issue [#50](https://github.com/donghyunlee-dev/meeting-automation/issues/50)
- Email 선택 및 API-010 recipient Delivery projection: TASK-011.02, Issue [#47](https://github.com/donghyunlee-dev/meeting-automation/issues/47)
- Slack Publish/Notification Delivery projection: TASK-012.02, Issue [#49](https://github.com/donghyunlee-dev/meeting-automation/issues/49)
- Publish 문서 저장 성공/URL: TASK-010.03, Issue [#45](https://github.com/donghyunlee-dev/meeting-automation/issues/45)
- Frontend는 SCR-008 표시/상호작용과 API 호출을 소유한다. Retry endpoint 동시성은 Backend가 권위다.

## 변경 경계

- Complete page는 API-010만 Session/Document/Delivery 결과 원천으로 사용한다. URL query, local optimistic flag만으로 Provider 성공을 추정하지 않는다.
- Document row는 저장 성공 여부 및 검증된 URL을 표시한다. Document 실패 상태/재시작은 TASK-010.03에 위임한다.
- Email row는 `recipientParticipantId`별 Delivery이며 전체 이메일을 결과로 재표시하지 않는다. 이름 lookup은 기존 Participant data를 사용하고 조회 실패 시 ID를 쓴다.
- Notification은 API-010 `channel=NOTIFICATION`에 매핑되는 V1 Slack 결과 row다.
- API-016은 retryable 실패마다 단건 호출한다. Idempotency-Key는 사용자 클릭 단위로 생성·보존하고 다른 Delivery key와 공유하지 않는다.

## 구현 순서

API-010 결과 렌더링을 먼저 검증해 Retry 가능 여부/summary가 단일 데이터 모델을 따르게 한다. 다음으로 API-016 사용자 action을 연결하고 polling 후 결과를 갱신한다.

1. SCR-008 route, API-010 Session/document/delivery types, API-003 이름 lookup, 공통 polling hook을 조사한다. 결과: 직접 진입/재진입 때 읽을 데이터 흐름과 현재 polling 취소 규칙을 기록한다.
2. Summary selector와 row/component 테스트를 먼저 작성한다. 결과: document만 저장, all sent, partial failure, all failed, in-progress, no-delivery 상태가 명확히 분리된다.
3. Document/Email/Slack 결과 행과 안전한 링크/participant label을 구현한다. 결과: 주소/provider 원문 없이 API-010 내용이 보인다.
4. Retry eligibility 및 버튼 accessible name 테스트를 작성한다. 결과: FAILED+retryable true에만 버튼이 나타난다.
5. Row 단위 single-flight API-016 action을 구현한다. 결과: 클릭마다 한 key/한 Delivery며 202는 PENDING 접수로만 처리된다.
6. API-010 polling, response-loss same-key reconciliation, stale version guard를 연결한다. 결과: 기존 retry 결과를 재사용하며 신규 retry는 명시 클릭만 만든다.
7. 409/404/validation 오류, retryable false, Provider ambiguous outcome 메시지를 구현한다. 결과: 성공 추정/자동 재호출/불필요한 duplicate가 없다.
8. 모바일·keyboard·screen reader/browser QA와 실제 Frontend test command를 확인해 Issue #51에 기록한다.

## 검증 접근

Frontend unit/component/API mock tests로 summary selector, Delivery row eligibility, idempotency key lifecycle, polling/state reconciliation을 확인한다. Browser QA에서 route 진입/복귀, 접근성, 360px 폭, 링크 동작을 검증한다. 실제 Gmail/Slack 전송은 수행하지 않는다.

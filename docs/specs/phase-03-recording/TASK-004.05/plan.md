# 구현 계획

## 의존성

- `TASK-004.03` (#16): 검증된 `{title,templateId,participantIds,timezone}` form callback
- `TASK-004.04` (#17): POST API-006, Session create/idempotency response
- `TASK-005.01` 후속: `/meetings/{sessionId}/recording` destination 화면
- `TASK-001.02` (#2): React Router, Vitest, frontend scripts
- PRD v1.7.0 `FR-002`, `SCR-002`, `API-006`; Issue [#18](https://github.com/donghyunlee-dev/meeting-automation/issues/18)

## 변경 대상

API-006 client request/response types, New Meeting submit orchestration, idempotency attempt state, Session Context/store, React Router navigation, component/API mock/router tests를 구현한다. scaffold의 실제 path를 확인한 뒤 적용한다.

## 소유권과 계약

- Form은 title/template/participantIds/timezone을 제공한다.
- Integration은 API-006의 `recoveryKey`와 Header `Idempotency-Key`를 생성한다.
- Session Context는 `sessionId`, `version`, `status`, `uploadPolicy`를 route가 읽게 한다.
- destination route는 `/meetings/{sessionId}/recording`이며 실제 Recording UI는 후속 task에서 제공한다.

## 구현 순서

1. API client, existing route tree, Session Context/Storage 유무를 확인한다.
2. mock API/router tests를 먼저 작성해 성공 이동, 오류 draft 보존, duplicate click/key semantics의 실패를 확인한다.
3. 타입 지정 API-006 client와 immutable attempt/key 관리자를 구현한다.
4. form callback에 POST를 연결하고 loading/disable/error/retry 동작을 추가한다.
5. 성공 응답을 Session Context에 넣고 destination route로 이동한다.
6. retry payload/key, loading, API error, navigation과 build/lint를 검증한다.

Validation과 Session API는 이미 완료된 앞선 task 계약을 사용하므로 orchestration은 API service와 router 간의 integration test가 먼저다.

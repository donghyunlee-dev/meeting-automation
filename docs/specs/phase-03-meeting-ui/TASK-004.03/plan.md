# 구현 계획

## 의존성

- `TASK-004.01` (#14): Home에서 `/meetings/new` 진입 route
- `TASK-004.02` (#15): API-001 company timezone와 API-002 Template 목록
- `TASK-003.01` (#10): `{id,name,email}` Participant roster/API-003
- `TASK-004.05`: 이 Task가 제공할 유효 입력 callback을 API-006에 연결
- PRD v1.7.0 `FR-002`, `SCR-002`; Issue [#16](https://github.com/donghyunlee-dev/meeting-automation/issues/16)

## 변경 대상

New Meeting route/page, form state/validation, API-001/002/003 client calls, Template/Participant selectors, component/API fixture tests를 구현한다. `frontend/`가 아직 없으므로 scaffold와 실제 route tree를 구현 전 확인한다.

## 소유권과 계약

- Data query: `GET /api/v1/app-config`, `GET /api/v1/templates`, `GET /api/v1/participants`
- 폼은 `{title,templateId,participantIds,timezone}`를 제공한다. `recoveryKey`, Session ID 생성은 후속 Session API integration owner가 처리한다.
- Submit은 유효 payload callback만 호출한다. POST API-006 요청이나 성공 navigation은 하지 않는다.
- roster empty는 submit을 막고 Participants 관리에서 목록을 준비하라는 안내를 보여준다.

## 구현 순서

1. Frontend route/API client/test 도구 상태를 확인한다.
2. fixture 기반 tests로 selector 조회, 폼 검증, multi-select, callback을 먼저 작성한다.
3. API-001/002/003 client query와 loading/error/retry 처리기를 구현한다.
4. SCR-002 form component와 기본 Template/multi-select를 구현한다.
5. 검증 및 callback 연결, 입력 draft 보존을 구현한다.
6. 모바일·키보드 QA, `npm run test`, `npm run lint`, `npm run build`를 수행한다.

Session mutation/API 전에 폼과 검증을 독립 검증하면 API-006 통합을 TASK-004.05에서 단순한 계약 연결로 유지할 수 있다.

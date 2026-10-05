# API-010 상태 및 Review 조회 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `SCR-005`, `API-010`, `TASK-006.06`
- 선행 TASK-006.04 Issue [#30](https://github.com/donghyunlee-dev/meeting-automation/issues/30): processing status/stage/progress/transcript
- 선행 TASK-006.05 Issue [#31](https://github.com/donghyunlee-dev/meeting-automation/issues/31): REVIEW/Minutes completion
- 본 TASK Issue [#32](https://github.com/donghyunlee-dev/meeting-automation/issues/32)
- Data contracts: `docs/product/data-spec.md` Session/Speaker/TranscriptSegment/StructuredMinutes
- 후속 TASK-006.07: API-010 polling, Processing→Review navigation

## 변경 경계

- Backend Controller: `GET /meeting-sessions/{sessionId}` and common response envelope
- Application query service: memory Session read-only snapshot retrieval
- Response mapper: current status/state gated Review fields and derived `allowedActions`
- Session repository: immutable snapshot/version consistency; no writes during GET
- Authentication, provider calls, retry/processing mutation, frontend changes 없음

## 구현 순서

1. API-010 existing DTO/common envelope, Session Store concurrency/version, 006.04/006.05 mutation contracts를 확인한다. 결과: GET이 기대하는 저장 atomicity가 정해진다.
2. processing/review/failed/missing/read-only/concurrent update tests를 먼저 작성한다. 결과: 모든 status-dependent response shape가 고정된다.
3. Session Store snapshot query와 404 mapping을 구현한다. 결과: API가 정확한 version을 가진 한 시점의 state를 읽는다.
4. status별 response mapper를 구현한다. 결과: Review 이전 partial data는 숨기고 완료 후에만 full payload가 보인다.
5. `allowedActions`를 API-011~014 현재 허용 동작에서 매핑하고 version consistency/privacy를 확인한다. 결과: UI는 stale action을 추측하지 않는다.
6. common error/logging regression 및 Backend 전체 test/build를 검증한다. 결과: GET read-only와 secret boundary가 남는다.

## 검증 명령

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행한다. Mock Session Store version 변화와 API response snapshots로 consistency를 확인한다.

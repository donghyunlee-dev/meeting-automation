# Review 확정 API 구현 작업

1. API-010 Session DTO/store, API-011 mapping, API-012 Minutes validator, action calculator, common error mapper와 idempotency repository를 추적한다. 완료 결과: validator reuse 및 atomic transition 경계가 PR에 기록된다.
2. API controller/application tests를 먼저 작성한다. valid Review, body/header errors, state/version conflicts, all-issue 422, replay/collision/race cases에서 아직 구현하지 않은 기대 동작이 실패한다.
3. API-014 request DTO, JSON boolean validation, mandatory headers 및 common envelope mapping을 구현한다. 완료 결과: 잘못된 body/header는 400 `VALIDATION_FAILED`이며 Session mutation이 없다.
4. Session lookup과 idempotency fingerprint replay/conflict를 구현한다. 완료 결과: exact success replay는 원 version response 재사용, key/body/session/version mismatch는 409다.
5. REVIEW/CONFIRM action, `If-Match`와 version CAS를 적용한다. 완료 결과: missing Session 404, state conflict 409, stale version 412가 분리된다.
6. detected speakers 전체 mapping과 Session roster ID 조건을 검증한다. 완료 결과: null/missing/out-of-roster mapping issue가 정확한 path/code로 모이고 외부 Provider 호출은 없다.
7. TASK-008.01 StructuredMinutes 전체 schema 검증을 재사용/공유한다. 완료 결과: required fields/type/text/date/owner roster violations 전체가 deterministic issues array에 들어간다.
8. 성공 path에서 CONFIRMED transition, version +1, success idempotency response를 atomic commit한다. 완료 결과: 한 번의 200, action 재계산, Publish/provider 호출 없음이 확인된다.
9. concurrent/stale/replay, zero Speaker, empty sections/null optional fields, redaction 회귀 테스트를 완성한다. 완료 결과: valid boundaries는 허용되고 불완전 Review는 하나도 누락 없이 422 반환한다.
10. `./gradlew test`, `./gradlew clean build`, API manual QA를 실행한다. 완료 결과: 실제 실행 결과와 issue-to-acceptance evidence가 Issue #43에 기록된다.

# Review 확정 API 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `FR-016`, `API-014`, Section 23.1 `TASK-010.01`
- Issue [#43](https://github.com/donghyunlee-dev/meeting-automation/issues/43): HTTP 422/error issue response 결정 A
- TASK-008.01 Issue [#37](https://github.com/donghyunlee-dev/meeting-automation/issues/37): API-012와 동일한 StructuredMinutes 검증 규칙
- TASK-008.03 Issue [#39](https://github.com/donghyunlee-dev/meeting-automation/issues/39): current API-010 Session version/snapshot
- TASK-007.01 Issue [#34](https://github.com/donghyunlee-dev/meeting-automation/issues/34): detected speaker full mapping/Session roster ID
- TASK-006.06 Issue [#32](https://github.com/donghyunlee-dev/meeting-automation/issues/32): API-010 status, Review data, `allowedActions`
- Meeting 생성 시 지정된 roster는 Session에 저장된다. Confirm은 이 reference만 검사하고 Document Provider를 조회하지 않는다.

## 소유 경계

- Web adapter: API-014 route/body/header parsing 및 표준 success/error envelope
- Application: snapshot load, idempotency replay/fingerprint, state/version gate, review validator, confirm use case
- Domain: detected Speaker/mapping/Session roster/StructuredMinutes 규칙과 REVIEW→CONFIRMED/version transition
- Session repository: atomic compare-and-set transition 및 성공 idempotency response 저장
- Common error mapper: 400 `VALIDATION_FAILED`, 404 `SESSION_NOT_FOUND`, 409 state/idempotency conflicts, 412 version conflict, 422 `REVIEW_VALIDATION_FAILED`
- DocumentProvider/API-015: 호출/의존성 없음. 문서 생성과 공유는 TASK-010.02가 소유한다.

## 구현 순서

1. API-014 controller, Session snapshot/atomic update, API-012 validator, common error details, idempotency store와 API-010 action calculation을 확인한다. 결과: 실제 타입/파일 및 검증 reuse boundary가 PR에 기록된다.
2. request/body/header/state/version/idempotency 및 all-issues response tests를 먼저 작성한다. 결과: 성공 transition, 실패 상태 보존, Issue #43의 error envelope가 구현 전에 고정된다.
3. API request parsing과 Session lookup 후 idempotency replay/fingerprint ordering을 구현한다. 결과: exact success replay가 CONFIRMED 이후에도 같은 result/version을 반환하고 mismatched key 사용은 충돌한다.
4. REVIEW/CONFIRM/If-Match concurrency gate를 구현한다. 결과: stale/state mismatch가 validation/mutation 전에 지정 오류로 종료된다.
5. Session-saved detected speaker/mapping/participantIds 및 TASK-008.01 StructuredMinutes validator를 하나의 snapshot에서 검사하고 stable `{path,code}` 전체 issue 목록을 수집한다. 결과: 모든 incomplete issue가 422에 함께 나타나고 external Provider 호출은 없다.
6. all-checks-pass에서 status/version/success idempotency record를 atomic commit한다. 결과: valid request만 version +1/CONFIRMED가 되고 confirm 외 Publish side effect는 없다.
7. field privacy/error response/API-010 allowedActions, zero/empty boundary 및 duplicate concurrent confirm regression을 검증한다. 결과: sentinels 비노출과 race safety가 확인된다.
8. Backend automated tests와 API-014 수동 QA를 수행한다. 결과: 명령·acceptance evidence를 Issue #43에 기록한다.

## 처리 순서

Body/header parse → Session lookup → exact idempotency replay/conflict → REVIEW/CONFIRM state check → If-Match check → Session snapshot all-issues validation → atomic CONFIRMED/version + idempotency result commit → 200 response. 실패 validation path는 common error mapper를 통해 code/category/traceId와 결정된 details schema로 반환한다.

## 변경 후보

- `src/main/java/.../adapter/in/web/` confirm controller/request DTO
- `src/main/java/.../application/` ConfirmReview use case 및 idempotency orchestration
- `src/main/java/.../domain/` Review completeness rules/confirmation transition
- `src/main/java/.../adapter/out/session/` snapshot read와 atomic version compare-and-set
- common validation error details serializer와 operation idempotency record
- controller/application/domain/repository integration tests

정확한 Java package/class path는 구현 checkout에서 기존 backend 구조와 TASK-001.04 error mapper를 확인한다. Provider SDK/import나 publish command를 추가하지 않는다.

## 검증 방법

Backend root에서 `./gradlew test`, 전체 검증 `./gradlew clean build`. API-014 integration은 valid Review success, error-code/status matrix, multiple issue aggregation, idempotency replay/race, Session snapshot immutability, external Provider call count 0를 포함한다.

# Minutes 편집 API 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `FR-012`, `FR-015`, `API-012`, `DEC-011`, `TASK-008.01`
- 선행 TASK-006.06 Issue [#32](https://github.com/donghyunlee-dev/meeting-automation/issues/32): Review Session/version/API-010 Minutes snapshot 및 `allowedActions`
- 본 TASK Issue [#37](https://github.com/donghyunlee-dev/meeting-automation/issues/37)
- Data contract: `docs/product/data-spec.md` StructuredMinutes/Session roster
- API contract: `docs/product/api-spec.md` 공통 error envelope, `If-Match`, API-012/API-013
- Successor contract consumer: API-010이 saved Minutes/version을 재조회에 제공하고 TASK-008.03이 왕복 저장을 통합한다.

## 변경 경계

- Backend Controller/DTO: `PUT /meeting-sessions/{sessionId}/minutes`, If-Match, StructuredMinutes input 및 `{data}` response
- Application use case: Session/Review/allowed action guard, optimistic version check, atomic replacement
- Domain validation: exact template identity, all structured sections, task/date/roster owner rules
- Session repository: immutable snapshot/version compare-and-set, no partial Minutes update
- Common exception mapper: `VALIDATION_FAILED`, `SESSION_NOT_FOUND`, `SESSION_STATE_CONFLICT`, `SESSION_VERSION_CONFLICT`, `PARTICIPANT_NOT_FOUND`
- Template catalog, LLM, transcript, speaker mappings, Frontend code는 변경하지 않는다.

## 구현 순서

1. API-010 DTO/Session version mutation boundary, current Minutes model, Session participant roster 및 API-013 template ownership을 확인한다. 결과: 변경 가능 field와 불변 metadata가 구분된다.
2. valid/empty/schema/date/roster/template/state/version/no-op/race/privacy tests를 먼저 작성한다. 결과: 검증과 mutation atomicity를 구현 전에 고정한다.
3. API-012 request DTO와 full-body validation을 구현한다. 결과: 잘못된 field 하나라도 있으면 저장 boundary에 도달하지 않는다.
4. `REVIEW`/allowed action/If-Match를 검증하고 version compare-and-set로 Minutes 전체를 교체한다. 결과: success는 single version mutation, stale update는 412다.
5. 공통 response/error mapper와 API-010 read-after-write integration을 연결한다. 결과: saved content/version이 동일하게 다시 조회된다.
6. error/log redaction 및 Backend 전체 test/build를 수행한다. 결과: privacy와 회귀 evidence가 기록된다.

## 검증 명령

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행한다. API-012 mutation 후 API-010 GET으로 version, Minutes 필드, Transcript/Speaker immutability를 통합 확인한다.

# Review Minutes 저장 통합 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `API-010`, `API-012`, `FR-012`, `TASK-008.03`
- 선행 TASK-008.01 Issue [#37](https://github.com/donghyunlee-dev/meeting-automation/issues/37): API-012 full Structured Minutes replacement, `If-Match`, validation, error/version response
- 선행 TASK-008.02 Issue [#38](https://github.com/donghyunlee-dev/meeting-automation/issues/38): Review form draft/save/error state callbacks
- API-010 source: TASK-006.06 Issue [#32](https://github.com/donghyunlee-dev/meeting-automation/issues/32), latest Session/version/Minutes/allowedActions snapshot
- 본 TASK Issue [#39](https://github.com/donghyunlee-dev/meeting-automation/issues/39)

## 변경 경계

- Review parent/container: server base, user draft, in-flight submitted snapshot, dirty state machine
- FE mutation client: API-012 body, current version If-Match, response/error mapping and API-010 requery
- Query cache: mutation response와 API-010 snapshot을 version-aware merge; lower version drop
- Minutes editor: submit/cancelability, loading/no-op/error/saved signals; field rendering은 TASK-008.02 owner
- Backend API-012: 선행 구현 계약을 사용하고 API behavior는 바꾸지 않는다.
- Template regeneration, Transcript persistence, Provider connection are not mutation dependencies for this endpoint.

## 구현 순서

1. API-010 query/cache version, TASK-008.02 form callbacks, API-012 full DTO/error schema와 allowedActions를 확인한다. 결과: base/draft/in-flight state source가 하나로 정리된다.
2. clean/dirty submit, current If-Match, success merge, in-flight edit, error retention, 412/network-unknown tests를 먼저 작성한다. 결과: race/error path가 구현 전 실패 테스트로 고정된다.
3. dirty/no-op gate와 single-flight API-012 mutation을 구현한다. 결과: clean form은 network call 없고 submit은 한 번만 실행된다.
4. success response를 submitted snapshot/base/cache에 version-aware로 적용하고 API-010 requery를 연결한다. 결과: 저장 완료와 더 최신 server snapshot을 구분하고 stale GET을 무시한다.
5. 4xx/5xx, 412, network timeout을 분기하고 latest API-010 reconcile을 구현한다. 결과: draft는 보존되며 blind PUT retry가 없다.
6. Provider-null/configured-false와 Session loss 경계, API-010 full snapshot consistency를 integration 검증한다. 결과: AppConfig 상태가 API-012 저장을 불필요하게 막지 않는다.
7. Frontend/Backend automated tests, lint/build 및 manual read-after-write QA를 수행한다. 결과: 왕복/경합/error evidence가 기록된다.

## 검증 명령

Frontend `npm run test`, `npm run lint`, `npm run build`; Backend `./gradlew test`, `./gradlew clean build`를 사용한다. 검증에는 API-012 response, API-010 read-after-write, lower-version cache suppression, submitted/current draft separation을 포함한다.

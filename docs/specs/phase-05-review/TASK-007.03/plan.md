# Speaker mapping 저장 통합 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `DEC-010`, `API-011`, `TASK-007.03`
- 선행 TASK-007.01 Issue [#34](https://github.com/donghyunlee-dev/meeting-automation/issues/34): API-011 full mapping/`If-Match`/atomic version/response contract
- 선행 TASK-007.02 Issue [#35](https://github.com/donghyunlee-dev/meeting-automation/issues/35): Review Dropdown, speakerId-keyed local draft, API-003 roster 및 Settings 안내
- 선행 TASK-006.06 Issue [#32](https://github.com/donghyunlee-dev/meeting-automation/issues/32): API-010 latest Session/version/speaker/transcript/allowedActions snapshot
- 본 TASK Issue [#36](https://github.com/donghyunlee-dev/meeting-automation/issues/36)

## 변경 경계

- FE Review form/container: dirty mapping draft, submit lifecycle, conflict/error feedback
- FE API client/query cache: API-011 `If-Match`, response mapping/version merge, API-010 invalidation/reconciliation GET
- FE Transcript view model: `speakerId` join으로 모든 segment label 계산; raw Transcript DTO는 변경하지 않음
- BE API-011: TASK-007.01 구현물을 소비하고 이미 검증/원자 저장하는 endpoint로 유지
- Common error mapping: API-011 standard envelope와 API-010 query error 처리
- TASK-007.01/007.02의 API/Dropdown 책임을 다시 구현하지 않는다. Settings provider-null 결정과 사용자 draft 보존을 유지한다.

## 구현 순서

1. Review form draft owner, API-010 query cache/version, TASK-007.01 response contract, Transcript renderer의 speaker join을 확인한다. 결과: optimistic update/invalidation 경계가 일치한다.
2. API fixture 기반 clean/dirty submit, 200 read-after-write, 412 conflict, 4xx/provider failure, unknown network result 및 Settings guard tests를 먼저 작성한다. 결과: 저장 실패에서 기존 값/사용자 draft 유지가 구현 전에 검증된다.
3. dirty mapping 전체를 한 API-011 request로 보내고 저장 중 중복 submit을 차단한다. 결과: request body와 If-Match가 현재 API-010 snapshot에 일치한다.
4. 200 response의 version/mappings를 committed base 및 API-010 cache에 원자 반영하고 speaker join view model을 갱신한다. 결과: 해당 speaker의 모든 segment 표시가 한 번에 변경된다.
5. 공통 error/412/network-unknown 흐름을 구현한다. 412와 전송 불명은 자동 PUT retry 없이 API-010 GET으로 조정한다. 결과: 최신 서버 snapshot과 draft가 분리 보존된다.
6. API-010 requery, Transcript text/time immutability, provider-null Settings guard 및 전체 FE/BE contract integration tests를 수행한다. 결과: 왕복 및 오류/경합 증거를 기록한다.

## 검증 명령

Frontend `npm run test`, `npm run lint`, `npm run build`; Backend `./gradlew test`, `./gradlew clean build`를 사용한다. Integration evidence에는 API-011 request/response, API-010 latest snapshot, 여러 Transcript segment가 동일 Speaker ID로 resolve되는 결과를 포함한다.

# Processing Pipeline 오케스트레이션 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `FR-007~009`, `API-009`, `TASK-006.04`
- 선행 TASK-006.01 Issue [#27](https://github.com/donghyunlee-dev/meeting-automation/issues/27): assembly use case/output
- 선행 TASK-006.02 Issue [#28](https://github.com/donghyunlee-dev/meeting-automation/issues/28): `ProviderTranscriptionResponse`
- 선행 TASK-006.03 Issue [#29](https://github.com/donghyunlee-dev/meeting-automation/issues/29): normalized `TranscriptResult`
- 선행 command TASK-005.07 Issue [#25](https://github.com/donghyunlee-dev/meeting-automation/issues/25): API-009/idempotency job handoff
- 본 TASK Issue [#30](https://github.com/donghyunlee-dev/meeting-automation/issues/30)
- 후속 TASK-006.05: Minutes generation, `REVIEW` transition; TASK-006.06: API-010 response

## 변경 경계

- Backend `ProcessingPipeline`: ordered use case coordination and duplicate execution guard
- Session application service: stage/progress/result/failure mutation and version consistency
- Processing job executor: asynchronous invocation from API-009 job without request thread blocking
- Stage ports: assembly → transcription → normalization, with typed result/failure
- Structured logger: `sessionId`, `traceId`, stage correlation without content
- API-009/010 controller wire shape는 유지한다. Provider/storage implementation은 선행 tasks contract를 사용한다.

## 구현 순서

1. API-009 job key, Session mutation/version, stage contract와 세 predecessor ports를 확인한다. 결과: application orchestration interface가 합의된다.
2. ordering, short-circuit, concurrent duplicate, stage/result/failure storage tests를 먼저 작성한다. 결과: side effect/Session state 기대가 고정된다.
3. asynchronous runner 및 per-session job lock/idempotent execution guard를 구현한다. 결과: 같은 job 병렬 실행이 방지된다.
4. stage/progress mutation과 assembly→provider→normalizer 호출을 연결한다. 결과: 각 output만 다음 단계에 전달된다.
5. normalized transcript/speaker atomic save와 TASK-006.05 handoff를 구현한다. 결과: transcript가 partial하게 저장되지 않고 `PROCESSING`을 유지한다.
6. failure stage/status/error category/retryability 기록 및 안전 구조화 로그를 구현한다. 결과: API-010 consumers가 실패를 해석할 수 있다.
7. test/lint/build 및 restart/idempotency/failure integration을 검증한다. 결과: 실행 순서/중복/관측 evidence가 남는다.

## 검증 명령

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행한다. API-009 trigger에서 job executor까지와 Session state query까지 통합 검증한다.

# TranscriptionProvider와 STT Adapter 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `FR-007`, `EXT-001`, `TASK-006.02`
- 선행 TASK-006.01 Issue [#27](https://github.com/donghyunlee-dev/meeting-automation/issues/27): assembled Audio ref/MIME
- 본 TASK Issue [#28](https://github.com/donghyunlee-dev/meeting-automation/issues/28)
- 후속 TASK-006.03: standardized diarization/speaker identity mapping
- 후속 TASK-006.04: pipeline invocation, retry policy, Session state/result persistence

## 변경 경계

- Domain/application boundary: provider-neutral command와 typed `ProviderTranscriptionResponse`/failure classification
- Backend adapter: 설정된 STT/diarization API 호출, provider DTO → 표준 DTO 변환
- Configuration: `TRANSCRIPTION_MODEL`, Secret, timeout/size 설정 binding
- Provider response validator: raw provider label reference 및 기본 segment time/text 검증; canonical standardization은 TASK-006.03
- Pipeline/status, Chunk storage/assembly, API contract는 변경하지 않는다.

구현은 Domain port를 우선 고정하고 adapter를 나중에 붙인다. Model-specific transport/types가 domain에 새지 않게 하며 audio는 TASK-006.01 보호 참조를 전달해 메모리에 전체 적재하지 않는다.

## 구현 순서

1. EXT-001/TranscriptSegment 데이터 모델, TASK-006.01 assembled output, Backend configuration/HTTP client 선택을 확인한다. 결과: 포트와 adapter wiring이 일치한다.
2. provider fixture 기반 typed label 변환/basic validation/timeout/error mapping unit test를 먼저 작성한다. 결과: 정상·비정상 provider-neutral response가 고정된다.
3. provider-neutral port 및 typed command/result/failure를 정의한다. 결과: orchestration은 SDK에 의존하지 않는다.
4. 설정 기반 adapter 요청/response DTO와 Secret/timeout wiring을 구현한다. 결과: 테스트/mock transport 및 deploy model 교체가 가능하다.
5. provider label/reference 및 기본 time/text 검증과 safe error mapping을 구현한다. 결과: malformed provider response가 다음 표준화 단계로 전달되지 않는다.
6. log redaction, fixture size/streaming, build/test를 확인한다. 결과: 민감 데이터 및 memory 조건 근거가 남는다.

## 검증 명령

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행한다. 고정 Audio fixture는 실제 audio 대신 mock transport에서 sentinel bytes로 전송해 로그 비노출을 확인한다.

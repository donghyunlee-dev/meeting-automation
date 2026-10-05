# Minutes 재생성 STT/Diarization 미호출 회귀 검증

## 목표

API-013에서 시작하는 Minutes 재생성의 API-to-worker 실행 경로가 기존 Session Transcript와 Speaker mapping만 사용하고 Audio assembly, STT, Diarization pipeline을 다시 실행하지 않음을 통합 경계에서 입증한다. 재생성 중 Minutes 생성 port는 정상 호출되며 결과는 API-010 Review snapshot에 반영된다. PRD v1.7.0 (2026-10-05), `DEC-011`, `FR-014`, `API-010`, `API-013`, `TASK-009.03`을 구체화한다. Issue [#42](https://github.com/donghyunlee-dev/meeting-automation/issues/42).

## 범위

- API-013 controller/application/background worker와 실제 Session job orchestration을 통한 통합 회귀 검증
- Audio assembly, `TranscriptionProvider`, `Diarization` port를 instrument해 호출 횟수 및 order 검증
- 기존 Transcript/Speaker mapping 입력과 API-010 성공/rollback Review 결과 확인
- API-013 성공/실패 및 중복 delivery/idempotent replay 회귀
- 일반 initial processing 경로를 positive control로 사용해 spy wiring과 0회 assertion의 신뢰성 확인
- 안전한 고정 fixture, 결정적 job drain/wait 및 민감 데이터 없는 test evidence

## 비범위

재생성 endpoint/business validation/rollback 구현 (`TASK-009.01`), Template/Review UI (`TASK-009.02`), 상세 provider adapter contract와 일반 pipeline stage 처리 (`TASK-006.02~006.04`), 성능 부하/실제 외부 Provider 호출, STT 결과 품질 및 추가 재시도 정책은 포함하지 않는다.

## 실행 경로 계약

- 통합 테스트는 모의 controller를 직접 호출하는 대신 application에 연결된 API-013 route와 비동기 worker handoff까지 통과한다. 외부 LLM/Transcription/Audio storage는 deterministic fake 또는 test spy를 사용한다.
- 정상 Transcript가 있는 `REVIEW` Session에는 Transcript segment와 Speaker mapping을 사전 적재하고 API-010에서 `REVIEW` snapshot/version을 확인한다.
- regeneration 요청은 API-013의 유효 Template, `If-Match`, 고유 `Idempotency-Key`로 제출한다. 202 응답 뒤 실제 비동기 작업이 terminal이 될 때까지 test executor/job latch를 기다린다. 임의 sleep으로 완료를 추정하지 않는다.
- `MinutesGenerationProvider`는 요청 Transcript/mapping/Template에 대해 결정적 유효 StructuredMinutes를 돌려준다. API-010 `REVIEW`/`DRAFT_READY` 및 새 Template/Minutes와 `lastOperation.SUCCEEDED`를 확인한다.
- 각 regeneration attempt 동안 `AudioAssembly`, `TranscriptionProvider.transcribe`, `DiarizationProvider.diarize` 호출 횟수는 각각 0이다. 단순 API method 단위에서 끝내지 않고 worker 실행 완료 뒤까지 계수한다.
- 성공 뒤 Transcript와 Speaker mapping은 baseline과 필드 동등하다. Audio resource read/assembly 호출도 없어야 한다.
- generation provider가 안전하게 실패하도록 설정한 경우 TASK-009.01 rollback 이후 API-010은 기존 Minutes/provenance, Transcript, mapping을 그대로 반환하고 Session은 `REVIEW`; Audio/STT/Diarization 호출은 여전히 0이다.
- 동일 idempotency request의 replay/worker duplicate delivery가 한 regeneration attempt만 만든다. `MinutesGenerationProvider` 호출은 최초 attempt당 최대 1회이고 세 pipeline 의존성은 계속 0회다.
- 기존 API-009 initial processing positive control에서는 동일 spy가 AUDIO_ASSEMBLY → `TranscriptionProvider` → `DiarizationProvider` 순으로 실제 한 번씩 호출되는지 별도 확인한다. 이렇게 해서 0회 assertion이 테스트 wiring 오류 때문이 아닌 것을 검증한다.
- raw audio/transcript, provider request/response, Secret은 assertion failure message, test report, log fixture에 남기지 않는다.

## 수용 기준

- 실제 API-013 route부터 worker completion까지 통과한 정상 재생성의 HTTP 202/terminal API-010 snapshot이 확인된다.
- 정상 regeneration에서 Minutes generation port가 예상된 횟수로 실행되고 Audio assembly/STT/Diarization ports는 모두 0회다.
- provider failure 후 기존 Minutes 전체와 Template provenance 복구 및 `REVIEW`가 확인되고 STT/Diarization/Audio 횟수는 0회다.
- 동일 Idempotency-Key replay와 worker duplicate는 재생성 작업을 중복하지 않고 Audio/STT/Diarization 0회를 지킨다.
- API-013 성공/실패 전후 Transcript와 Speaker mapping은 변경되지 않는다.
- positive control initial processing은 계측된 pipeline port들이 실제 순서대로 실행되는 것을 보여준다.
- test executor는 polling/sleep timing과 무관하게 terminal 상태를 결정적으로 기다리며 timeout에서 명확한 원인으로 실패한다.
- test output 및 일반 log에는 회의 콘텐츠, Audio, Secret, raw provider payload가 없다.

## 의존성

- TASK-009.01 Issue #40: API-013 비동기 처리, generator 성공/실패 rollback, API-010 terminal snapshot
- TASK-009.02 Issue #41: 사용자 흐름의 명시적 request 및 API-010 polling contract
- TASK-006.02 Issue #28: `TranscriptionProvider` port
- TASK-006.03 Issue #29: Diarization normalization/port
- TASK-006.04 Issue #30: initial Audio → STT → Diarization orchestration 및 비동기 runner
- TASK-006.05 Issue #31: Minutes generation port/StructuredMinutes output

## 결정 및 전제

TASK-009.01의 port-level 호출 assertion과 TASK-009.02의 browser network 검증을 대체하지 않는다. 이 작업은 Spring/API와 실제 application/job wiring 사이에서 regeneration이 일반 initial processing pipeline을 우회하는지 확인한다. 테스트는 외부 Provider/Audio storage를 사용하지 않고 고정 fixture로 실행한다.

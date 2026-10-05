# Processing Pipeline 오케스트레이션

## 목표

API-009가 만든 Processing 작업에서 Audio 조립, STT, diarization 표준화 순으로 유스케이스를 실행하고 각 단계의 결과/실패를 Session에 기록해 API-010 조회가 처리 지점과 안전한 오류를 제공하도록 한다. PRD v1.7.0 (2026-10-05), `FR-007~009`, `API-009`, `TASK-006.04`를 구체화한다. Issue [#30](https://github.com/donghyunlee-dev/meeting-automation/issues/30).

## 범위

- API-009 idempotent processing command와 비동기 job runner 연결
- 단계 순서: `AUDIO_ASSEMBLY` → `TRANSCRIPTION` → `DIARIZATION`
- 각 stage 완료 전이와 `processing.stage`/진행률 기록
- TASK-006.01 assembled Audio, 006.02 provider response, 006.03 표준 Transcript를 순서대로 handoff
- 최종 `speakers`/`transcript`를 Session memory에 원자적으로 저장
- 실패 stage/error code/retryability 보존, Session은 `PROCESSING_FAILED`로 안전 전이
- sessionId/traceId/stage structured logs와 중복 job 방지
- TASK-006.05 Minutes 생성으로 이어질 처리 context/handoff 제공

## 비범위

API-009 request 검증/job 생성(`TASK-005.07`), assembly/STT/provider/normalizer 내부 구현, Minutes generation (`TASK-006.05`), Processing GET endpoint (`TASK-006.06`), Frontend polling (`TASK-006.07`), persistent queue/database, 자동 무한 재시도는 포함하지 않는다.

## 오케스트레이션 계약

- API-009가 완료한 idempotency entry와 Session ID로 job을 식별한다. 같은 Session/key 재요청은 같은 job/result를 재사용하며 실행 중인 동일 stage를 두 번 시작하지 않는다.
- Stage order는 `AUDIO_ASSEMBLY` → `TRANSCRIPTION` → `DIARIZATION` 고정이다. 앞 단계가 실패하면 후속 stage를 호출하지 않는다.
- `processing.stage`는 현재 실행 중 stage를 가리킨다. API-009 202 초기값은 `AUDIO_ASSEMBLY`다. stage 진입 시 진행률은 `0, 34, 67`을 기록하고 stage 성공 때 각각 `33, 66, 100`으로 갱신한다. 임의의 provider 내부 progress를 위조하지 않는다.
- assembly는 TASK-006.01 output reference/MIME을 반환한다. STT는 `ProviderTranscriptionResponse`, normalizer는 PRD 표준 `TranscriptResult`를 반환한다. 각 단계는 실제 dependency output을 다음 입력으로 넘긴다.
- diarization 정상 완료 시 `speakers`와 `transcript`를 한 Session mutation으로 함께 저장한다. 둘 중 하나만 저장된 상태를 만들지 않는다. Processing status는 Minutes 초안 완료 전까지 `PROCESSING`으로 유지하고 다음 TASK-006.05에 같은 Session/job context를 넘긴다.
- stage failure는 해당 단계가 제시한 safe error code/category/retryable을 저장하고 Session status를 `PROCESSING_FAILED`로 전이한다. Session processing stage는 실패한 stage 값으로 유지해 관측 가능하게 한다. API-010은 failure detail 원문이나 provider response를 반환하지 않는다.
- 한 job은 한 번에 한 stage만 실행한다. 같은 job이 동시에 실행 요청되면 기존 실행을 반환하거나 무시하며, stage completion 저장은 compare-and-set/version 경계에서 한 번만 반영한다.
- 자동 재시도는 구현하지 않는다. retryable 실패 표시는 다음 재시도 유스케이스의 근거이며 같은 Session/job 재실행은 기존 완료 stage를 건너뛰고 실패한 safe stage부터 재개하는 후속 정책으로 다룬다. 이 Task에서는 initial execution 중복 방지와 failure recording까지만 보장한다.
- 각 stage 시작/완료/실패 로그는 `sessionId`, `traceId`, stage, duration, errorCode만 기록한다. Transcript, Audio, provider body, Secret은 log/error에 남기지 않는다.

## 수용 기준

- 세 stage가 정확한 순서로 실행되고 predecessor 실패 시 후속 호출이 없다.
- 성공한 각 stage와 표준 Transcript가 해당 Session/job에 정확히 한 번 반영된다.
- 동시/중복 API-009 trigger가 동일 Session에 processing job을 중복 실행하지 않는다.
- failure stage가 `PROCESSING_FAILED`와 safe code/retryability로 보존되고 이전 성공 결과를 손상시키지 않는다.
- API-009 초기 stage/진행률과 stage별 진행/완료 값이 API-010 processing shape와 일치한다.
- Minutes 완료 전에는 `REVIEW`로 전이하지 않으며 TASK-006.05에 Session/job context를 전달한다.
- 구조화 로그에 trace/session/stage가 있고 Audio/Transcript/Secret/provider raw body가 없다.

## 결정 및 전제

Minutes 생성과 `REVIEW` 전이는 TASK-006.05 범위다. 재시작 복구 불가 전제는 memory-only Session/queue 정책을 따르며, automatic provider retry를 추가하지 않아 외부 호출 중복 비용을 방지한다.

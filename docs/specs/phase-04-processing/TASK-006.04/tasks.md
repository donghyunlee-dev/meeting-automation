# Processing Pipeline 작업 항목

1. API-009 job/idempotency, Session application mutation과 선행 stage port 계약을 확인한다. 완료 증거: job identity와 input/output type이 연결된다.
2. 순서·failure short circuit·동시 trigger·stage/progress persistence 테스트를 먼저 작성해 실패를 확인한다.
3. asynchronous runner 및 per-session 중복 실행 방지 guard를 구현한다. 완료 증거: 같은 job은 한 번 실행된다.
4. `AUDIO_ASSEMBLY` → `TRANSCRIPTION` → `DIARIZATION` stage progression과 진행률을 구현한다. 완료 증거: 순서/값이 query에 보인다.
5. 각 adapter output handoff 및 normalized Speaker/Transcript atomic Session 저장을 구현한다. 완료 증거: partial result가 노출되지 않는다.
6. stage failure를 `PROCESSING_FAILED`/safe error로 기록하고 후속 stage 호출을 멈춘다. 완료 증거: 실패 지점과 retryability가 유지된다.
7. TASK-006.05 handoff와 `sessionId`/`traceId` 구조화 로그를 연결한다. 완료 증거: Minutes 전에 REVIEW transition이 없고 민감값이 없다.
8. `./gradlew test`, `./gradlew clean build`와 API-009/010 integration을 실행한다. 완료 증거: race, 실패, success 흐름 증거가 기록된다.

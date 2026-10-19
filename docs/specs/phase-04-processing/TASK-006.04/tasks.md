> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# Processing Pipeline 작업 항목

1. API-009 job/idempotency, Session application mutation과 선행 stage port 계약을 확인한다. 완료 증거: job identity와 input/output type이 연결된다.
2. 순서·failure short circuit·동시 trigger·stage/progress persistence 테스트를 먼저 작성해 실패를 확인한다.
3. asynchronous runner 및 per-session 중복 실행 방지 guard를 구현한다. 완료 증거: 같은 job은 한 번 실행된다.
4. `AUDIO_ASSEMBLY` → `TRANSCRIPTION` → `DIARIZATION` stage progression과 진행률을 구현한다. 완료 증거: 순서/값이 query에 보인다.
5. 각 adapter output handoff 및 normalized Speaker/Transcript atomic Session 저장을 구현한다. 완료 증거: partial result가 노출되지 않는다.
6. stage failure를 `PROCESSING_FAILED`/safe error로 기록하고 후속 stage 호출을 멈춘다. 완료 증거: 실패 지점과 retryability가 유지된다.
7. TASK-006.05 handoff와 `sessionId`/`traceId` 구조화 로그를 연결한다. 완료 증거: Minutes 전에 REVIEW transition이 없고 민감값이 없다.
8. `.\gradlew.bat test --tests com.meetingautomation.api.processing.ProcessingPipelineTests --tests com.meetingautomation.api.processing.ProcessingJobRunnerTests --tests com.meetingautomation.ProcessingStartApiTests`를 Backend root에서 실행하고 API-009/010 관련 경계를 통합 검증한다. 완료 증거: 선택 테스트의 race, 실패, success 결과가 기록된다. 전체 Backend suite와 `clean build`는 실행하지 않는다.

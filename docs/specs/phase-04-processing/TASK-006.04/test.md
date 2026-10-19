> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# Processing Pipeline 검증 계획

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| PIPE-01 | API-009 job과 모든 stage 정상 | assembly → transcription → diarization 순서, 표준 result atomic save | pipeline integration test |
| PIPE-02 | assembly failure | `PROCESSING_FAILED`, stage `AUDIO_ASSEMBLY`, 후속 port 미호출 | short-circuit test |
| PIPE-03 | transcription timeout/permanent error | stage `TRANSCRIPTION`, safe category/code/retryable 보존, normalizer 미호출 | failure mapping test |
| PIPE-04 | normalizer invalid result | stage `DIARIZATION`, Session에 partial speaker/transcript 저장 없음 | atomic result test |
| PIPE-05 | 두 thread가 같은 Session/idempotency job trigger | pipeline concurrent execution 한 건 | lock/idempotency test |
| PIPE-06 | 첫 stage 성공, 다음 stage 실패 | 완료 stage 기록 보존, 실패 stage 정확히 표시 | stage state persistence test |
| PIPE-07 | all 3 stage success | Speaker/Transcript 동시 commit, `PROCESSING` 상태로 TASK-006.05 handoff | repository/handoff test |
| PIPE-08 | stage entry/completion | progress: 0→33→34→66→67→100, stage별 API-010 shape consistency | progress contract test |
| PIPE-09 | retryable failure | retryable flag 기록, 자동 무한/즉시 반복 실행 없음 | retry policy test |
| PIPE-10 | API-009 동일 key retry 또는 runner duplicate delivery | 같은 job/result, 외부 stage duplicate 실행 없음 | job idempotency test |
| PIPE-11 | task log capture | sessionId/traceId/stage 존재, Audio/Transcript/Secret/provider body 없음 | logging assertion |
| PIPE-12 | Session이 Backend restart로 사라짐 | job 자동 복구를 주장하지 않고 `SESSION_NOT_FOUND` 처리 | memory-only restart test |

Backend root에서 다음 세 관련 테스트 클래스만 실행하고 결과를 기록한다. 전체 Backend suite와 `clean build`는 실행하지 않는다.

```powershell
.\gradlew.bat test --tests com.meetingautomation.api.processing.ProcessingPipelineTests --tests com.meetingautomation.api.processing.ProcessingJobRunnerTests --tests com.meetingautomation.ProcessingStartApiTests
```

## 통합/수동 QA

- API-009로 시작해 mock stage event 순서 및 API-010 processing stage/progress를 확인한다.
- 각 단계에 timeout/validation failure를 주입해 실패한 stage, Session error mapping, 후속 미실행을 확인한다.
- 같은 Idempotency-Key/API job을 반복 전달해 stage 호출/Session mutation이 중복되지 않는지 확인한다.
- 정상 diarization 뒤 Minutes 완료 전 `PROCESSING` 상태와 표준 Transcript가 후속 TASK-006.05에 전달되는지 확인한다.
- 일반 로그/trace에서 Audio, Transcript, Secret, provider 원문이 없는지 확인한다.

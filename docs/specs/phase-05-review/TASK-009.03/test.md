> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# Minutes regeneration STT and diarization regression plan

## Focused Windows command

Run from the `backend` directory with Java 25:

```powershell
.\gradlew.bat test --tests com.meetingautomation.MinutesRegenerationApiTests --tests com.meetingautomation.ProcessingStartApiTests
```

The selectors compile Backend sources and run only API-013 regeneration integration coverage plus the API-009 processing positive control. Do not run the full Backend suite for this task.

## Automated integration coverage

| ID | Scenario | Assertions | Test class |
|---|---|---|---|
| REG-PIPE-01 | API-013 accepts a valid request, then its captured worker delivery is drained | HTTP 202; terminal API-010 Review snapshot has the selected Template Minutes; generation provider called once | `MinutesRegenerationApiTests` |
| REG-PIPE-02 | Regeneration starts from existing Transcript and speaker mapping | Successful terminal snapshot preserves transcript and speaker mappings | `MinutesRegenerationApiTests` |
| REG-PIPE-03 | Inspect processing pipeline ports during regeneration | Audio assembly, audio storage, transcription provider, and diarization adapter receive no interactions | `MinutesRegenerationApiTests` |
| REG-PIPE-04 | Minutes generation returns a typed runtime failure | Worker completes; previous Review snapshot and Minutes/provenance are restored; safe failure operation is recorded | `MinutesRegenerationApiTests` |
| REG-PIPE-05 | Compare failure snapshot with baseline | Transcript, speakers, meeting and existing Minutes are preserved; terminal status returns to Review | `MinutesRegenerationApiTests` |
| REG-PIPE-06 | Replay the same Idempotency-Key and body after completion | Same acceptance response; generation call count remains one and terminal version is unchanged | `MinutesRegenerationApiTests` |
| REG-PIPE-07 | Dispatch the captured worker Runnable twice | Generation and terminal state mutation happen once; duplicate delivery leaves the completed snapshot unchanged | `MinutesRegenerationApiTests` |
| REG-PIPE-08 | Reuse a key with a different Template or session | `IDEMPOTENCY_KEY_CONFLICT`; no additional generation | `MinutesRegenerationApiTests` |
| REG-PIPE-09 | Stale `If-Match`, invalid Template, missing session, and malformed preconditions | API validation/conflict response; no generation and no unintended snapshot mutation | `MinutesRegenerationApiTests` |
| REG-PIPE-10 | API-009 accepts one synthetic upload and processes its queued job through the real runner | Audio assembly → `TranscriptionProvider` → `DiarizationResultAdapter` order; each called once and terminal Review contains one segment | `ProcessingStartApiTests` |
| REG-PIPE-11 | Keep fixture and failure evidence private | Assertions use synthetic inputs and safe API responses; no provider request/response or real audio is used | Both selected classes |
| REG-PIPE-12 | Worker completion | Test executor captures the submitted worker task and drains it synchronously; no polling or sleeps are used | `MinutesRegenerationApiTests` |

The API-013 integration test uses the production controller, service, in-memory queue adapter, worker runner, session store, and Minutes generation use case. A test-only executor retains the exact submitted Runnable, so the HTTP acceptance occurs before deterministic worker execution and the same delivery can be replayed.

The API-009 positive control uses the production upload, start-processing route, storage, assembly use case, job runner, and processing pipeline. Test-only transcription and Minutes providers avoid external calls. The request contains only a tiny synthetic fixture.

Real STT, diarization, document, or audio providers and user audio are not required. Keep developer verification scoped to the command above; CI remains configured as-is.

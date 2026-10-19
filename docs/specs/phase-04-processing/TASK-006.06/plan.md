> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# API-010 상태 및 Review 조회 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `SCR-005`, `API-010`, `TASK-006.06`
- 선행 TASK-006.04 Issue [#30](https://github.com/donghyunlee-dev/meeting-automation/issues/30): processing status/stage/progress/transcript
- 선행 TASK-006.05 Issue [#31](https://github.com/donghyunlee-dev/meeting-automation/issues/31): REVIEW/Minutes completion
- 본 TASK Issue [#32](https://github.com/donghyunlee-dev/meeting-automation/issues/32)
- Data contracts: `docs/product/data-spec.md` Session/Speaker/TranscriptSegment/StructuredMinutes
- 후속 TASK-006.07: API-010 polling, Processing→Review navigation

## 변경 경계

- Backend Controller: `GET /meeting-sessions/{sessionId}` and common response envelope
- Application query service: memory Session read-only snapshot retrieval
- Response mapper: current status/state gated Review fields and derived `allowedActions`
- Session repository: immutable snapshot/version consistency; no writes during GET
- Authentication, provider calls, retry/processing mutation, frontend changes 없음

## 구현 순서

1. API-010 existing DTO/common envelope, Session Store concurrency/version, 006.04/006.05 mutation contracts를 확인한다. 결과: GET이 기대하는 저장 atomicity가 정해진다.
2. processing/review/failed/missing/read-only/concurrent update tests를 먼저 작성한다. 결과: 모든 status-dependent response shape가 고정된다.
3. Session Store snapshot query와 404 mapping을 구현한다. 결과: API가 정확한 version을 가진 한 시점의 state를 읽는다. 처리 시작 전에도 `processing` object를 유지하고 stage를 null로 표현한다.
4. status별 response mapper를 구현한다. 결과: Review 이전 partial data는 숨기고 완료 후에만 full payload가 보인다. Speaker mapping 전 `participantId`는 null이다.
5. `allowedActions`를 API-011~014 현재 허용 동작에서 매핑하고 version consistency/privacy를 확인한다. `PROCESSING_FAILED`는 저장된 safe error code/retryability와 `audioAvailable:false`를 제공하되 action/expiry는 Audio lifecycle TASK-017.02/#61까지 비워 둔다.
6. API-010 응답/동시 snapshot regression을 검증한다. Developer/QA는 다음 focused selectors만 실행하고, Backend 전체 suite 및 `clean build`를 반복하지 않는다. GitHub Actions CI 설정은 그대로 둔다.

## 검증 명령

Windows Backend root에서 다음 selectors를 실행한다. Session response, API-009 status boundary, worker 중 status read의 직접 영향 범위를 검증한다.

```powershell
& .\gradlew.bat test --tests com.meetingautomation.MeetingSessionStatusApiTests --tests com.meetingautomation.ProcessingStartApiTests --tests com.meetingautomation.api.processing.ProcessingJobRunnerTests
```

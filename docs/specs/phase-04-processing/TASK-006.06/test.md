> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# API-010 Processing/Review 조회 검증 계획

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| API-010-01 | CREATED/UPLOADING Session | `{data}` envelope와 non-null `processing`; stage는 null, progress는 0 | status API test |
| API-010-02 | PROCESSING 직후 API-009 snapshot 및 worker stage 진행 | Session/version/status와 현재 processing stage/progress 일치, partial review fields 숨김 | status API and worker tests |
| API-010-03 | PROCESSING_FAILED at TRANSCRIPTION | 실패 status/stage/progress와 safe `errorCode`/`retryable`/`audioAvailable:false`; 빈 Review fields/actions, provider 원문 없음 | status API test; Audio expiry/actions in TASK-017.02 |
| API-010-04 | REVIEW 완료, Speakers/Transcript/Minutes 저장 | 같은 version snapshot의 전체 review data 및 allowedActions; 아직 매핑되지 않은 participantId는 null | response contract test |
| API-010-05 | API-011~014 action별 허용/불허 상태 | REVIEW allowedActions에 현재 유효 action만 포함하고 비-Review 상태에서는 비어 있음 | status API test |
| API-010-06 | 없는 Session 조회 | HTTP 404 `SESSION_NOT_FOUND` common error envelope | status API test |
| API-010-07 | 동일 Session 연속 GET | response fields 동일, version/state 저장 변화 없음 | status API test |
| API-010-08 | GET과 processing worker update 동시 발생 | payload 내 version/fields가 동일 Session snapshot에서 직렬화됨 | worker/status API tests |
| API-010-09 | sentinel Transcript/Minutes/provider error/Secret in Session | API response 및 일반 로그에 민감 sentinel 없음 | status API test |

Windows Backend root에서 아래 focused selectors를 실행하고 결과를 기록한다. 전체 Backend suite와 `clean build`는 developer/QA 로컬 검증에서 반복하지 않으며, GitHub Actions CI 설정은 변경하지 않는다.

```powershell
& .\gradlew.bat test --tests com.meetingautomation.MeetingSessionStatusApiTests --tests com.meetingautomation.ProcessingStartApiTests --tests com.meetingautomation.api.processing.ProcessingJobRunnerTests
```

## 통합/수동 QA

- API-009 뒤 각 processing stage와 실패 stage를 조회해 progress/status 응답이 UI에 유용한지 확인한다.
- Minutes 완료 전 Transcript/Minutes partial field가 보이지 않고 완료 후 Review 전체 payload가 반환되는지 확인한다.
- API-011/012 변경 후 version/action 갱신, Session 없음 404 및 반복 GET read-only를 확인한다.
- 일반 로그와 응답에서 Transcript 원문, Minutes, provider error 원문, Secret이 노출되지 않는지 확인한다.

# 검증 계획

기준: `TASK-011.02`, PRD v1.7.0 (2026-10-05), `FR-017`, `SCR-007`, `API-003`, `API-010`, `API-015`, Issue [#47](https://github.com/donghyunlee-dev/meeting-automation/issues/47).

## 자동화 검증

Frontend는 변경된 API/component 테스트 파일만 `npm run test -- --run <changed-test-file>`로 선택한다. `npm run lint`와 `npm run build`는 변경 코드에 필요한 경우에만 실행한다. Backend는 Java 25 및 Windows Gradle Wrapper를 사용하고 변경 동작과 직접 영향받는 호출자 테스트만 `test --tests <fully.qualified.TestClass>`로 선택한다. Backend 전체 suite, `clean build`, local service 또는 task-local 별도 QA pass는 실행하지 않는다. Gmail/Document Provider 호출은 contract/unit 테스트의 mock/fake로 검증하며, 설계 단계에서는 실행하지 않는다.

| ID | 준비 및 입력 | 기대 결과 | 증거 |
|---|---|---|---|
| EMAIL-SHARE-01 | API-010 Session roster `{pt_001,pt_002}`, API-003 includes more IDs | SCR-007 shows only two Session roster participants; display name/email joins by ID | Roster projection component test |
| EMAIL-SHARE-02 | API-003 missing record for one Session ID | Session ID remains in roster selection; safe fallback label, no active/existence badge | Missing display metadata test |
| EMAIL-SHARE-03 | API-003 request loading/error | cached participant display/selection preserved; safe retry/info, no Session roster mutation | Participant list error test |
| EMAIL-SHARE-04 | default SCR-007 state | Session attendee rows default checked per screen mock; user can uncheck each | Checkbox default/interaction test |
| EMAIL-SHARE-05 | all participants unchecked | API-015 contains `emailRecipientParticipantIds:[]`, EmailProvider invocation 0; document/other channel path continues | Empty selection contract test |
| EMAIL-SHARE-06 | selected IDs pt_001 and pt_003 where pt_003 outside roster | HTTP 400 `VALIDATION_FAILED`, no delivery created/provider call | API roster subset test |
| EMAIL-SHARE-07 | API-015 successful Publish request | Request body includes selected IDs and notification setting only; no email addresses | API payload privacy test |
| EMAIL-SHARE-08 | API-010 Session response before Publish | `meeting.participantIds` exists; `deliveries` is omitted; common envelope intact | API-010 serialization test |
| EMAIL-SHARE-09 | Publish accepted with multiple selected recipients | API-010 generic `deliveries[]` contains recipientParticipantId/status/retryable per selected person; no email | Delivery projection test |
| EMAIL-SHARE-10 | Participant record list resolves all selected IDs | only chosen addresses reach EmailProvider; request order/results preserved | Backend recipient resolution test |
| EMAIL-SHARE-11 | one selected ID missing record/email or invalid address | that recipient `FAILED/EMAIL_FAILED`; no address leak; other valid recipient sends | Recipient-level failure isolation test |
| EMAIL-SHARE-12 | mixed Gmail API successes/failures | API-010 shows independent statuses and retryable flags; successful rows never resend | Delivery result mapping test |
| EMAIL-SHARE-13 | ParticipantProvider list unavailable after document save | selected email recipients report failure safely; document saved and notification result not rolled back | Publish channel isolation test |
| EMAIL-SHARE-14 | Session status/document version changes while Delivery polls | lower API-010 version ignored; latest result/cache unchanged | Polling stale response test |
| EMAIL-SHARE-15 | `SENT` Email Provider result | UI reports provider accepted send request; no inbox arrival/read claim | Success message semantics test |
| EMAIL-SHARE-16 | Email FAILED and retryable true | UI explains eligible for later retry; API-016 not called from this task | Retry ownership test |
| EMAIL-SHARE-17 | API-010 deliveries contain unknown future channel/status | Email UI filters channel=EMAIL and renders safe fallback for unknown status | Forward compatibility test |
| EMAIL-SHARE-18 | page with multiple addresses/status updates, keyboard and 360px width | checkbox accessible name, textual/live result, no horizontal overflow | Accessibility/browser test |
| EMAIL-SHARE-19 | API responses/log captures/delivery history | full recipient email, OAuth/authorization secret, body/provider raw errors absent from delivery projection/logs | PII redaction assertion |

## 통합 contract/component 검증

- API/Publish 테스트에서 roster subset 검증, 선택한 수신자만 전송, 빈 선택의 Email 호출 0건, Document 결과 보존을 확인한다.
- API-010 직렬화 및 UI 테스트에서 recipient별 상태, retryable, Email 외 채널 독립성, 이메일 주소 및 Provider 원문 미노출을 확인한다.
- 컴포넌트 테스트에서 API-003 로딩/실패와 ID fallback, 선택 상태 유지, keyboard 접근 가능한 checkbox label 및 텍스트/live status를 확인한다.
- 성공 문구는 Provider send 요청 수락을 나타내며 수신함 도착/열람을 주장하지 않는다. 이 작업은 자동 재전송이나 API-016 호출을 추가하지 않는다.

## 완료 증거

정확한 선택 테스트 명령 및 결과, API-010 sample without PII, mixed/empty recipient assertion과 redaction 증거를 `docs/evidence/TASK-011.02/verification.md` 및 Issue #47에 기록한다.

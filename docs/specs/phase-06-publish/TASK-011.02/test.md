# 검증 계획

기준: `TASK-011.02`, PRD v1.7.0 (2026-10-05), `FR-017`, `SCR-007`, `API-003`, `API-010`, `API-015`, Issue [#47](https://github.com/donghyunlee-dev/meeting-automation/issues/47).

## 자동화 검증

Frontend 명령은 `npm run test`, `npm run lint`, `npm run build`다. Backend는 Java 21/Gradle wrapper 위치를 project layout에서 확인한 뒤 JUnit 5 및 build를 실행한다. Gmail/Document Provider 호출은 mock/fake를 사용한다. 설계 단계에서는 tests를 실행하지 않는다.

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

## 통합 및 수동 QA

- New Meeting에서 네 명 roster를 선택하고 두 명만 SCR-007 Email checkbox에 남겨 API-015 body ID와 일치하는지 확인한다.
- Mock Gmail을 사용해 success/failure가 다른 두 recipient 결과가 API-010 및 UI에 별도 표시되는지 확인한다.
- 모든 Email checkbox를 해제하고 Publish해 document save는 계속되고 Gmail API 호출은 없는지 확인한다.
- API-003 error 및 missing email fixture에서 화면 선택 내용이 보존되고 없는 주소를 추정해 보내지 않는지 확인한다.
- Email 성공 문구가 API accepted send만 나타내고 inbox/열람 결과를 주장하지 않는지 확인한다.
- Email retryable 상태에서도 본 task에서 자동 또는 직접 resend하지 않고 TASK-013.02 소유 UI 연결만 안내하는지 확인한다.
- Keyboard-only, screen reader status update, mobile width와 개인정보 미노출을 확인한다.

## 완료 증거

Actual FE/Backend command output, API-010 sample without PII, mixed result browser capture, empty-recipient result 및 redaction evidence를 Issue #47에 기록한다. Gmail live email은 별도 승인받은 test mailbox/recipient만 사용한다.

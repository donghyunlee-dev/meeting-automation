# 구현 작업 목록

기준: `TASK-011.02`, PRD v1.7.0 (2026-10-05), `FR-017`, `SCR-007`, `API-003`, `API-010`, `API-015`, Issue [#47](https://github.com/donghyunlee-dev/meeting-automation/issues/47).

## 사전 확인

- [ ] TASK-004.03 API-003 participant record and selected Session IDs behavior, TASK-010.02 API-015 body, TASK-011.01 Gmail command/result contract 확인. 완료 증거: DTO/ownership cross-check 기록.
- [ ] API-010 Session response mapper/cache and in-memory Session Delivery model 확인. 완료 증거: optional projection lifecycle and version source recorded.
- [ ] Existing RecipientDeliveryResult/Delivery retryable error classification and no-email-address logging tests 확인. 완료 증거: reused types/error semantics inventory.

## Backend 작업

- [ ] API-010 `meeting.participantIds` and `deliveries[]` projection normal/empty/history-hidden cases를 failing test로 작성한다. 완료 증거: response contract assertions.
- [ ] API-015 selected IDs must be subset of immutable Session roster, duplicate IDs handled, empty accepted, external participant status never queried for roster eligibility를 테스트한다. 완료 증거: boundary test.
- [ ] API-010 response mapper에 roster IDs 및 generic Delivery projection을 추가한다. 결과: API-010 single Session version/snapshot으로 statuses를 반환한다.
- [ ] API-015 coordinator가 ParticipantProvider data를 selected IDs로 제한해 email address를 resolve하고, miss/bad email을 recipient-level `FAILED`로 저장한다. 결과: roster itself and other channels stay unchanged.
- [ ] EmailProvider를 recipient별 invoke하고 success/failure/retryable result를 generic Delivery로 mapping한다. 결과: mixed recipients are isolated; no raw addresses persisted.
- [ ] Missing Provider list or batch failure를 safe Email failed outcomes로 mapping하고 Notification/document result를 유지한다. 결과: generic API-010 still returns other channel outcomes.

## Frontend 작업

- [ ] API-010/003 clients/types/query tests를 먼저 작성한다. 결과: Session roster IDs만 eligible, API-003 records are display metadata, unknown records fallback to ID.
- [ ] SCR-007 checkbox test를 작성한다. 결과: default selected per screen, user can deselect, zero selection persists as empty array.
- [ ] Selection state를 API-015 request test와 연결한다. 결과: emailRecipientParticipantIds only selected Session roster IDs, never emails.
- [ ] Publish `202` 이후 API-010 polling/cache version guard를 구현한다. 결과: each delivery status updates from authoritative server snapshot only.
- [ ] Per-recipient PENDING/SENDING/SENT/FAILED/retryable feedback를 구현한다. 결과: mixed outcomes independent, no resend controls before TASK-013.02.
- [ ] Provider/email roster read errors and PII redaction/accessibility/mobile states를 구현한다. 결과: selection/draft preserved; no raw email in errors/logs.

## 통합 및 완료 확인

- [ ] Document save→email handoff and API-010 delivery results end-to-end with mocked Gmail and ParticipantProvider. 결과: selected IDs only, message delivery only after document ref exists.
- [ ] FE `npm run test`, `npm run lint`, `npm run build`; Backend Java21/Gradle unit/build commands from actual wrapper path run. 결과: command output/evidence on Issue #47.
- [ ] Manual QA records recipient list, sent/failed partial outcomes, empty selection, no sensitive response/log data, and Email completion indicator accuracy.

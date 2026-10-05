# 구현 계획

기준: PRD v1.7.0 (2026-10-05), `FR-017`, `SCR-007`, `API-003`, `API-010`, `API-015`, `EXT-004`.

## Dependencies

- 선행 TASK-011.01 EmailProvider Gmail Adapter — Issue [#46](https://github.com/donghyunlee-dev/meeting-automation/issues/46)
- 선행 TASK-004.03 New Meeting input/participant selection model — Issue [#16](https://github.com/donghyunlee-dev/meeting-automation/issues/16)
- Publish/API contracts: TASK-010.02 Issue [#44](https://github.com/donghyunlee-dev/meeting-automation/issues/44), Review→Publish UI TASK-010.03 Issue [#45](https://github.com/donghyunlee-dev/meeting-automation/issues/45)
- 본 Task: [Issue #47](https://github.com/donghyunlee-dev/meeting-automation/issues/47)

## 변경 경계와 ownership

- Backend API-010 mapper: Session-stored `meeting.participantIds`와 generic `deliveries[]` response
- Backend Publish coordinator: recipient ID subset 검증, Session roster와 ParticipantProvider record join, Email Provider에 주소를 server-side로 전달, 결과를 generic Delivery model로 기록
- Frontend SCR-007: API-010 roster IDs와 API-003 display/email details join, per-recipient checkbox state, API-015 selected IDs body
- Frontend Share results: API-010 polling/cache refresh 및 Email-only status view. Document URL/progress는 TASK-010.03, Notification result는 TASK-012.02, retry/Complete는 TASK-013.02가 owning 한다.
- API-003/DocumentProvider는 현재 구성된 Document provider의 Participant records를 반환한다. 수신자 주소 해석은 전달 목적에만 사용하며 live active/presence check를 추가하지 않는다.

## 계약 변경

API-010 response의 기존 `{data}` envelope를 유지한다. `meeting:{participantIds}`는 immutable Session roster를, `deliveries:Delivery[]`는 Publish 후 common channel results를 제공한다. API-015 body는 기존 `emailRecipientParticipantIds`의 ID 목록을 사용한다. Email addresses는 browser request에 포함하지 않는다. API error table의 `EMAIL_FAILED` retryability는 per-Delivery `retryable` 값에 따른다.

## 구현 순서

Participant roster와 Delivery response가 FE selection/results의 input이므로 Backend response contract 및 coordinator mapping을 먼저 고정한 뒤 SCR-007을 연결한다.

1. API-010 response DTO, Session aggregate, API-003 client/Provider, TASK-011.01 EmailProvider command/result, API-015 coordinator/current delivery model을 확인한다. 결과: participant details와 IDs의 source, polling cache owner를 확정한다.
2. Backend API-010 snapshot/delivery mapping, ID subset validation, missing email result tests를 먼저 작성한다. 결과: out-of-roster ID/PII leakage/mixed result는 assertion 실패로 포착된다.
3. Backend가 Session participant IDs 및 published Delivery projection을 API-010 common envelope에 추가한다. 결과: REVIEW/PUBLISH lifecycle에서 optional fields와 version consistency가 유지된다.
4. API-015 coordinator가 provider participant records 중 선택 ID의 email만 resolution하고 Gmail Provider를 recipient별 호출한 뒤 generic delivery state/result를 저장하도록 연결한다. 결과: omitted/unselected recipients are never sent.
5. Frontend API types/query를 API-010 fields로 확장하고 API-003 result를 Session ID allowlist로 제한한다. 결과: screen에는 current Session roster만 나타난다.
6. SCR-007 checkbox/default/all-off state와 API-015 request mapping을 구현한다. 결과: request body는 chosen IDs and notification setting only다.
7. API-010 delivery projection을 version-aware poll/cache에 반영하고 recipient result/status/error rendering을 구현한다. 결과: newer snapshot만 update하며 retry action ownership을 넘긴다.
8. API-003 unavailable/missing address, empty roster/selection, partial send failure, accessibility/privacy paths를 검증한다.
9. 실제 FE/Backend test/build 명령 및 manual QA를 실행해 Issue #47에 결과를 기록한다.

## Project commands

PRD/기초 TASK-001.02가 제공하는 Frontend scripts는 `npm run test`, `npm run lint`, `npm run build`다. Backend project baseline은 Java 21/Gradle이며 root/path와 wrapper command는 구현 당시 repository structure에서 확인한다. 자동 검증 대상 및 수동 절차는 [test.md](./test.md)에 고정한다. 설계 단계에서는 실행하지 않는다.

# Document Publish API 및 Session 멱등성

## 목표

API-015가 `CONFIRMED` Session의 Meeting 문서를 생성하거나 동일 Session의 기존 문서를 재사용하도록 한다. Publish 접수는 비동기 `202 PUBLISHING`이며, Document 저장 실패는 `DOCUMENT_FAILED`로 기록하고 Email/Notification을 시작하지 않는다. 실패 후 새 key/current version으로 문서 단계 재시도를 허용한다. PRD v1.7.0 (2026-10-05), `FR-016`, `API-015`, `EXT-003`을 구체화한다. Issue [#44](https://github.com/donghyunlee-dev/meeting-automation/issues/44).

## 범위

- API-015 경로, 공통 응답 envelope, `If-Match` 및 `Idempotency-Key` 검증
- Session의 `CONFIRMED` 상태와 version을 확인하고 Publish를 한 번만 접수
- 저장된 Session snapshot으로 `CreateMeetingCommand` 구성; `externalSessionId=sessionId`
- `findMeetingBySessionId` 결과가 있으면 기존 참조 재사용, 없을 때만 `createMeeting`
- Document 참조 저장, Session version 및 상태 전이, API-010 조회 결과 연결
- 기존 오류 규약에 따른 Provider 실패 처리와 로그/응답 민감정보 제거

## 비범위

- Email 발송은 TASK-011.01, Notification 전달은 TASK-012.01의 책임이다. API-015가 선택 정보를 접수할 수 있지만 이 작업은 각 채널을 호출하지 않는다.
- Delivery 재시도 API-016은 TASK-013.01 범위다.
- Provider 설정/Health 선택, DocumentProvider Port/Confluence Adapter 구현은 TASK-002.01 및 Provider 구현 작업이 제공하는 기존 계약을 소비한다.
- 영속 DB/Queue 및 Backend 재시작 뒤 작업 복구는 V1 범위가 아니다.

## 요청 및 접수 계약

```http
POST /api/v1/meeting-sessions/{sessionId}/publish
If-Match: "<current-version>"
Idempotency-Key: <opaque-key>
Content-Type: application/json

{"emailRecipientParticipantIds":["pt_001","pt_002"],"notificationEnabled":true}
```

두 header는 필수다. body에는 `emailRecipientParticipantIds` 문자열 ID 배열과 `notificationEnabled` boolean이 모두 필요하다. 수신자 배열은 비어 있을 수 있지만 중복 ID는 거절한다. 전달된 모든 ID는 Session에 저장된 `meeting.participantIds`에 있어야 한다. Email 주소 조회/검증과 발송은 후속 Email 작업이 수행한다. 필드 누락, 알 수 없는 필드, null 또는 잘못된 타입은 400 `VALIDATION_FAILED`다.

Session이 없으면 404 `SESSION_NOT_FOUND`, 상태가 `CONFIRMED` 또는 직전 문서 저장이 실패한 `DOCUMENT_FAILED`가 아니거나 Publish action이 불가하면 409 `SESSION_STATE_CONFLICT`, version 불일치는 412 `SESSION_VERSION_CONFLICT`다. 정확히 같은 key/session/정규화 request/If-Match fingerprint가 이미 접수되었으면 상태 검사보다 먼저 최초 `202` 응답을 replay한다. 동일 key를 다른 입력에 재사용하면 409 `IDEMPOTENCY_KEY_CONFLICT`다. 성공 접수는 Session을 `PUBLISHING`으로 변경하고 version을 한 번 증가시킨 뒤 HTTP 202 `{data:{sessionId,status:"PUBLISHING"}}`를 반환한다. 새 key로 `DOCUMENT_FAILED`에서 재시도하면 최신 version을 사용하며 문서 provider lookup을 다시 수행한다.

## Document 작업 계약

Accepted Session의 일관된 snapshot으로 Meeting title/date, Session roster, Speaker mapping이 반영된 Transcript, Structured Minutes와 metadata `{schemaVersion,externalSessionId,meetingAt,templateId,participantIds}`를 구성한다. Email/Notification 선택값은 후속 workflow가 사용하도록 접수 기록에 보존한다. Provider 요청/응답 본문과 문서 본문은 일반 로그에 남기지 않는다.

Document 저장 단계는 다음 순서를 따른다.

1. `DocumentProvider.findMeetingBySessionId(sessionId)`를 호출한다.
2. 기존 참조가 있으면 create를 건너뛰고 그 `documentId`/`documentUrl`을 채택한다.
3. 없으면 `createMeeting(command)`를 호출한다. 생성과 metadata 기록 중 응답 유실/재실행이 발생해도 다음 시도는 같은 Session ID 조회를 우선한다.
4. 성공 참조를 Session의 publish 결과에 기록하고 `DOCUMENT_SAVED`로 전이한다. 그 후 별도 후속 작업이 채널 전달을 시작한다.

같은 Session의 서로 다른 Idempotency-Key, 동시 요청도 Session 상태/version의 원자적 조건 변경과 provider-side `externalSessionId` 조회를 함께 사용해 문서 하나만 채택한다. Memory-only V1에서 프로세스 재시작 복구를 보장하지 않지만 Provider에 이미 생성된 문서를 재접수/실행 시 재발견할 수 있어야 한다.

## 실패 및 경계 조건

- Document 구조 누락: 기존 422 `DOCUMENT_STRUCTURE_NOT_FOUND`; Document Provider 연결/저장 오류: 502 `DOCUMENT_FAILED` (`DOCUMENT_FAILURE`).
- Document 단계 실패는 Session을 `DOCUMENT_FAILED`로 기록하고 안전한 오류 코드/traceId만 노출한다. Email/Notification 호출은 0회다.
- API-015가 202를 반환한 뒤 처리 실패하므로 Provider 오류는 접수 API 응답으로 동기 반환하지 않는다. API-010이 최종 상태와 문서 결과를 제공한다.
- Provider 조회 결과가 일시 오류이면 생성으로 우회하지 않는다. 중복 방지를 보장할 수 없으므로 단계 실패 처리한다.
- 기존 문서 참조가 발견되면 새 문서 생성 없이 그 참조를 기록한다.
- API-010과 공통 성공 envelope를 유지하고 task-specific 확장은 문서 결과 및 publish 오류처럼 필요한 경우에만 추가한다. Secret, Authorization, 원 Provider 오류 본문, Minutes/Transcript를 오류에 복사하지 않는다.

## 완료 기준

- 유효한 요청은 Session/version을 한 번만 변경하고 같은 key 재전송에 최초 202 결과를 반환한다.
- stale version, 잘못된 상태, 잘못된 요청, key 충돌은 각각 명세된 오류이며 상태/document 기록을 변경하지 않는다.
- Provider 조회 결과 재사용, 조회 후 생성, Provider 오류 및 조회 불능 사례가 검증된다.
- Session당 여러 key/동시 요청에서도 DocumentProvider create는 최대 한 번의 논리적 문서로 수렴한다.
- 저장 성공 때 `documentId`와 유효 `documentUrl`이 API-010 결과에 노출되고 저장 실패 시 Email/Notification 호출은 없다.
- 네 문서와 PRD 추적 행이 master에 게시되고 원격 확인된다.

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

# Meeting Automation API Specification

> **문서 역할:** Frontend와 Backend 사이의 REST 계약을 정의한다.
> **기준 문서:** [PRD.md](./PRD.md)
> **데이터 정의:** [data-spec.md](./data-spec.md)
> **외부 Provider 계약:** [integrations.md](./integrations.md)
> **기준일:** 2026-10-05

## 공통 HTTP 규칙

- Base path: `/api/v1`
- JSON 요청/응답은 UTF-8 `application/json`이다. Audio chunk body는 raw binary이며 `Content-Type`에 실제 녹음 MIME type을 보낸다.
- 성공 응답은 `{ "data": ... }` envelope를 사용한다.
- 오류 응답은 `{ "error": { "code", "message", "category", "retryable", "traceId", "details" } }`다.
- 시간은 ISO 8601 offset datetime 또는 UTC `Z`이며, duration은 millisecond 정수다.
- 목록 응답은 `items`를 사용한다.
- API는 인증 추가 여부를 임의로 정의하지 않는다. V1 단일 회사의 보호 경계는 배포 접근 정책/환경 설정에서 결정되며, 외부 공개 접근이 필요해지면 PRD/ADR 변경이 선행되어야 한다.

### 공통 Header

| Header | 사용 | 의미 |
|---|---|---|
| `Accept: application/json` | 모든 호출 | JSON 응답 요청 |
| `Content-Type` | body가 있을 때 | JSON 또는 chunk MIME type |
| `X-Request-Id` | 선택 | trace correlation. 누락 시 서버 생성 |
| `Idempotency-Key` | 생성/처리/Publish/전송 부작용 요청 | 동일 키·동일 payload는 최초 결과 재사용, 동일 키·상이 payload는 409 충돌 |
| `If-Match: "<version>"` | Session 변경 요청 | 낙관적 동시성 검사 |
| `X-Audio-SHA256` | chunk 업로드 | chunk bytes 무결성 |
| `X-Audio-Byte-Length` | chunk 업로드 | 선언 크기 검증 |

`If-Match` 불일치는 `SESSION_VERSION_CONFLICT` 및 HTTP 412로 처리한다. 허용되지 않은 상태는 409 `SESSION_STATE_CONFLICT`다. 잘못된 입력은 400, 찾을 수 없는 자원은 404, 외부 Provider 오류는 해당 표준 오류 코드와 안전한 메시지로 매핑한다. Secret과 외부 오류 원문은 노출하지 않는다.

공통 오류 예시:

```json
{
  "error": {
    "code": "SESSION_STATE_CONFLICT",
    "message": "현재 상태에서는 요청을 수행할 수 없습니다.",
    "category": "CONFLICT",
    "retryable": false,
    "traceId": "tr_xxx",
    "details": {}
  }
}
```

## 상태/오류 코드

Session 상태와 전이 규칙은 [architecture.md](./architecture.md)의 상태 섹션을 따른다. 대표 오류 코드는 아래와 같다.

| 코드 | HTTP | 재시도 | 의미 |
|---|---:|---:|---|
| `VALIDATION_FAILED` | 400 | N | 요청 필드 검증 실패 |
| `INTERNAL_ERROR` | 500 | N | 처리되지 않은 서버 설정 또는 필수 리소스 오류 |
| `PARTICIPANT_NOT_FOUND` | 404 | N | 존재하지 않는 Participant ID |
| `SESSION_NOT_FOUND` | 404 | N | 존재하지 않거나 메모리에서 만료된 Session |
| `SESSION_STATE_CONFLICT` | 409 | N | 현재 상태에서 요청 불가 |
| `SESSION_VERSION_CONFLICT` | 412 | 조건부 | If-Match 버전이 오래됨. 최신 상태 조회 후 사용자 수정 반영 |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | N | 같은 키로 다른 요청 payload 사용 |
| `AUDIO_CHUNK_INVALID` | 400 | N | MIME, checksum, 길이, 크기 검증 실패 |
| `AUDIO_CHUNK_CONFLICT` | 409 | N | 같은 sequence가 다른 bytes로 이미 저장됨 |
| `AUDIO_CHUNKS_INCOMPLETE` | 409 | Y | 누락 chunk가 있어 처리 시작 불가 |
| `DOCUMENT_STRUCTURE_NOT_FOUND` | 422 | N | Root/Meetings/Participants 구조 오류 |
| `PARTICIPANT_LIST_FAILED` | 502 | 조건부 | Provider 참가자 목록 조회 실패 (`DOCUMENT_FAILURE`) |
| `PROCESSING_FAILED` | 502 | 조건부 | Audio/STT/Minutes 처리 실패 |
| `DOCUMENT_FAILED` | 502 | 조건부 | Document Provider 실패 |
| `EMAIL_FAILED` | 502 | Y | 개별 Email delivery 실패 |
| `NOTIFICATION_FAILED` | 502 | Y | Notification delivery 실패 |

위 세부 HTTP 매핑은 표준 제안이다. 구현 시 Spring 예외 처리기에서 일관되게 매핑하며 PRD 오류 유형 네 가지(`PROCESSING_FAILURE`, `DOCUMENT_FAILURE`, `EMAIL_FAILURE`, `NOTIFICATION_FAILURE`)를 운영 분류로 유지한다.

## API 목록

| ID | Method / Path | 목적 |
|---|---|---|
| API-001 | `GET /app-config` | 공개 가능한 앱 설정 |
| API-002 | `GET /templates` | Template 목록 |
| API-003 | `GET /participants` | Participant 목록 |
| API-004 | `POST /participants` | 생성 |
| API-005 | `PATCH /participants/{participantId}` | 수정 |
| API-006 | `POST /meeting-sessions` | Session 생성 |
| API-007 | `PUT /meeting-sessions/{sessionId}/audio/chunks/{sequence}` | Chunk 업로드 |
| API-008 | `GET /meeting-sessions/{sessionId}/audio` | 업로드 현황 |
| API-009 | `POST /meeting-sessions/{sessionId}/process` | 처리 시작 |
| API-010 | `GET /meeting-sessions/{sessionId}` | 상태와 검토 데이터 조회 |
| API-011 | `PUT /meeting-sessions/{sessionId}/speaker-mappings` | Speaker 일괄 mapping |
| API-012 | `PUT /meeting-sessions/{sessionId}/minutes` | Minutes 편집 저장 |
| API-013 | `POST /meeting-sessions/{sessionId}/minutes/regenerate` | 기존 Transcript로 재생성 |
| API-014 | `POST /meeting-sessions/{sessionId}/confirm` | Review 확정 |
| API-015 | `POST /meeting-sessions/{sessionId}/publish` | 문서 저장 및 공유 시작 |
| API-016 | `POST /meeting-sessions/{sessionId}/deliveries/{deliveryId}/retry` | 실패 delivery 재시도 |
| API-017 | `GET /meetings` | 과거 Meeting 목록 |
| API-018 | `GET /meetings/{documentId}` | 읽기 전용 상세 |
| API-019 | `GET /integrations/health` | Provider 연결 상태 |

## Endpoint 계약

### API-001 App Config

`GET /api/v1/app-config` → `200`

```json
{"data":{"company":{"id":"sfood","name":"SFOOD","timezone":"Asia/Seoul"},"document":{"provider":null,"configured":false},"email":{"enabled":true,"configured":true},"notification":{"provider":"SLACK","enabled":true},"recording":{"chunkDurationSeconds":15,"maxMeetingDurationMinutes":60}}}
```

공개 설정만 반환한다. Secret, Provider token, root credential은 절대 응답하지 않는다.

`DOCUMENT_PROVIDER`가 없거나 빈 값이면 자동 Provider 선택을 하지 않고 `document.provider=null`, `document.configured=false`를 반환한다. 유효 Provider가 선택됐지만 해당 credentials가 없거나 형식이 잘못되면 선택된 Provider ID와 `configured=false`를 반환한다. 지원하지 않는 비어 있지 않은 enum은 HTTP 500 `INTERNAL_ERROR`로 변환한다.

### API-002 Templates

`GET /api/v1/templates` → `200`

```json
{"data":{"items":[{"id":"default.md","name":"기본 회의록","version":"1.0.0"},{"id":"project.md","name":"프로젝트 회의","version":"1.0.0"}]}}
```

두 Template은 Backend static resources에서 읽는다. 필수 파일이 빠지면 목록 일부를 반환하지 않고 안전한 HTTP 500 `INTERNAL_ERROR`를 반환한다.

### API-003 Participants List

`GET /api/v1/participants` → `200`

응답 item은 `{id,name,email}`다. 결과가 없으면 `{data:{items:[]}}`를 반환한다. 필수 Participants 구조가 없으면 `DOCUMENT_STRUCTURE_NOT_FOUND`(422, 재시도 불가), 목록을 읽지 못하면 `PARTICIPANT_LIST_FAILED`(502, 일시적 네트워크/Provider 오류에 한해 재시도 가능)다. 오류 응답과 로그에는 Provider 원문을 포함하지 않는다.

### API-004 Participant Create

`POST /api/v1/participants` (`Idempotency-Key`) → `201`

Request: `{ "name": "홍길동", "email": "hong@example.com" }`

서버는 name/email 앞뒤 공백을 제거하고, name 비어 있음 또는 email 누락/형식 오류를 400 `VALIDATION_FAILED`로 반환한다. 유효 입력은 TASK-003.01 저장 형식으로 Participants child page를 생성하고 `{data:{id,name,email}}`를 반환한다. 동일 Idempotency-Key와 동일 정규화 payload는 최초 결과를 재사용한다. 같은 키의 다른 payload는 409 `IDEMPOTENCY_KEY_CONFLICT`다. 구조 누락은 422 `DOCUMENT_STRUCTURE_NOT_FOUND`, Provider 저장 오류는 502 `DOCUMENT_FAILED` (`DOCUMENT_FAILURE`)다.

### API-005 Participant Update

`PATCH /api/v1/participants/{participantId}` → `200`

Request는 `name`, `email` 중 하나 이상이며 제공된 필드만 수정한다. name은 trim 후 비어 있지 않아야 하고 email은 trim 후 주소 형식이어야 한다. 빈 body 또는 제공 필드가 null/잘못된 형식이면 400 `VALIDATION_FAILED`; 없는 ID는 404 `PARTICIPANT_NOT_FOUND`; Provider 저장 오류는 502 `DOCUMENT_FAILED` (`DOCUMENT_FAILURE`)다. 성공 시 전체 표준 `{id,name,email}`를 반환한다.

### API-006 Meeting Session Create

`POST /api/v1/meeting-sessions` (`Idempotency-Key`) → `201`

```json
{"title":"AX 주간회의","templateId":"default.md","participantIds":["pt_001","pt_002"],"timezone":"Asia/Seoul","recoveryKey":"browser_generated_key"}
```

Backend는 Template 존재, 선택된 Participant ID의 roster 참조와 중복 ID, 제목/timezone을 검증한다. `201` 응답은 `{sessionId,version:1,status:"CREATED",uploadPolicy:{chunkDurationSeconds,maxChunkBytes,acceptedMimeTypes}}`다.

### API-007 Audio Chunk Upload

`PUT /api/v1/meeting-sessions/{sessionId}/audio/chunks/{sequence}` → `200`

Raw bytes body. `sequence`는 0 이상의 정수. Header에 `Content-Type`, `X-Audio-SHA256`, `X-Audio-Byte-Length`, `Idempotency-Key`를 보낸다. 동일 bytes 재전송은 `{sequence,received:true}`로 멱등 ACK한다. 같은 sequence에 다른 bytes, 잘못된 MIME/길이, 허용 크기 초과는 명시 오류다.

### API-008 Audio Upload Status

`GET /api/v1/meeting-sessions/{sessionId}/audio` → `200`

응답: `{receivedSequences:number[],receivedChunks:number,totalBytes:number}`. 상태 응답은 누락 sequence를 계산할 수 있게 실제 수신 번호 전체를 준다.

### API-009 Processing Start

`POST /api/v1/meeting-sessions/{sessionId}/process` (`Idempotency-Key`) → `202`

Request: `{expectedChunks:number,durationMs:number,mimeType:string}`. Backend는 chunk 연속성 및 duration 상한 검증 후 비동기 처리 상태를 만들고 `{sessionId,status:"PROCESSING",stage:"AUDIO_ASSEMBLY"}` 반환. 누락은 `AUDIO_CHUNKS_INCOMPLETE`; 같은 요청의 재실행은 동일 processing 작업을 반환한다.

### API-010 Session Status / Review Data

`GET /api/v1/meeting-sessions/{sessionId}` → `200`

응답에는 `{sessionId,version,status,processing,speakers,transcript,minutes,allowedActions}`가 포함된다. `allowedActions`는 현재 상태에서 허용되는 `UPDATE_SPEAKER_MAPPING`, `UPDATE_MINUTES`, `REGENERATE_MINUTES`, `CONFIRM` 등만 포함한다. Browser는 이를 표시 힌트로 사용하되 서버가 항상 재검증한다.

### API-011 Speaker Mapping Update

`PUT /api/v1/meeting-sessions/{sessionId}/speaker-mappings` (`If-Match`) → `200`

Request: `{ "mappings": [{"speakerId":"speaker_a","participantId":"pt_001"}] }`

Mapping set은 감지된 각 speaker를 최대 한 Participant에 매핑한다. Participant는 Session에 선택된 roster 참조여야 한다. null/unmapped는 Confirm 전 허용할 수 있다. 응답은 새 version과 `{speakerId,participantId,participantName}` 목록이다. 매핑은 해당 화자의 모든 Transcript segment 표시 결과에 적용된다.

### API-012 Minutes Update

`PUT /api/v1/meeting-sessions/{sessionId}/minutes` (`If-Match`) → `200`

Request는 Structured Minutes를 전달한다. 모든 배열/문자열, action item의 Participant 참조와 날짜 형식을 검증한다. 응답은 새 version과 저장된 Minutes를 반환한다.

### API-013 Minutes Regenerate

`POST /api/v1/meeting-sessions/{sessionId}/minutes/regenerate` (`Idempotency-Key`, `If-Match`) → `202`

Request: `{ "templateId":"project.md" }`. Frontend는 편집 중 Minutes가 교체될 수 있음을 확인받은 뒤 호출한다. 처리 입력은 기존 Transcript + 현재 mapping + Template뿐이다. STT/diarization을 호출하지 않는다. 응답 `{sessionId,status:"PROCESSING",stage:"DRAFT_REGENERATION",templateId}`.

### API-014 Review Confirm

`POST /api/v1/meeting-sessions/{sessionId}/confirm` (`If-Match`, `Idempotency-Key`) → `200`

Request `{ "confirm":true }`. 감지된 모든 Speaker mapping, Minutes 필수 구조, 상태/version을 검증하고 `CONFIRMED`로 전이한다. 응답 `{status:"CONFIRMED",version}`.

### API-015 Publish / Share

`POST /api/v1/meeting-sessions/{sessionId}/publish` (`Idempotency-Key`, `If-Match`) → `202`

```json
{"emailRecipientParticipantIds":["pt_001","pt_002"],"notificationEnabled":true}
```

`DOCUMENT SAVE → EMAIL DELIVERY → NOTIFICATION` 순서를 따른다. 문서 저장이 실패하면 Email/Notification을 보내지 않는다. Document 저장 성공 후 채널 결과는 독립적으로 기록한다. 응답은 우선 `{sessionId,status:"PUBLISHING"}`이며 완료/부분 실패 결과는 API-010 상태에서 조회한다.

### API-016 Delivery Retry

`POST /api/v1/meeting-sessions/{sessionId}/deliveries/{deliveryId}/retry` (`Idempotency-Key`) → `202`

실패하고 재시도 가능한 delivery만 허용한다. 이미 성공한 Delivery 재발송은 금지. 응답 `{deliveryId,status:"PENDING"}`.

### API-017 Meetings List

`GET /api/v1/meetings?limit=100` → `200`

`limit` 기본 100, 허용 범위 1..100. 응답 item은 `{documentId,title,meetingAt,participants:[{id,name}],documentUrl}`. Provider child 페이지와 metadata를 읽으며 서버 full text search는 제공하지 않는다. 잘못된 범위는 400.

### API-018 Meeting Detail

`GET /api/v1/meetings/{documentId}` → `200`

읽기 전용 `{documentId,title,meetingAt,participants,minutes,transcript,documentUrl}`. 앱을 통한 과거 문서 수정 API는 없다.

### API-019 Integration Health

`GET /api/v1/integrations/health` → `200`

응답은 `document:{provider,configured,reachable,rootAccessible}`, `email:{configured,reachable}`, `notification:{provider,configured,reachable}`, `ai:{configured,reachable}`를 항상 제공한다. 미선택/미연동 영역은 `configured=false`, `reachable=false`로 응답하며, `document`의 Provider 미선택은 `provider=null`이다. `rootAccessible`은 설정 Root와 필수 child 구조를 탐색할 수 있음을 뜻한다. 자격증명 원문이나 연결 오류의 민감한 응답 본문은 포함하지 않는다. Provider별 Health 구현은 같은 공통 응답에 contributor를 등록해 해당 영역을 갱신한다.

## 비동기 처리와 조회

API-009, API-013, API-015, API-016은 작업 개시 응답에 `202 Accepted`를 사용한다. 처리 상태는 API-010으로 조회한다. polling 간격은 클라이언트에서 지수 backoff를 적용하고 서버 부하를 제한한다. 구체 기본 간격은 구현 설정이며 계약상 고정값은 아니다.

## 요구사항 추적

PRD Traceability Matrix를 기준으로 한다. 핵심 연결: API-006은 FR-002, API-007~009는 FR-003~008, API-010~014는 FR-009~015, API-015~016은 FR-016~019/027, API-017~018은 FR-020~023, API-003~005는 FR-024, API-001/019는 FR-025~026이다.

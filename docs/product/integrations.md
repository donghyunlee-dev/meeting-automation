# Meeting Automation Integration Specification

> **문서 역할:** 외부 시스템 연동 Port, 표준 DTO, Adapter 책임, 오류/보안 규칙을 정의한다.
> **기준 문서:** [PRD.md](./PRD.md)
> **시스템 구성:** [architecture.md](./architecture.md)
> **HTTP 경계:** [api-spec.md](./api-spec.md)
> **기준일:** 2026-10-05

## 공통 연동 규칙

- Domain과 Application은 Notion, Confluence, OpenAI, Slack 또는 Email Vendor SDK 모델을 참조하지 않는다.
- 각 외부 Adapter는 provider-specific Request/Response를 내부 표준 DTO로 변환한다.
- credential은 Backend 런타임 secret에서만 읽고 frontend로 보내지 않는다.
- 외부 오류 본문을 그대로 사용자 응답, 일반 로그, Admin Slack에 넣지 않는다.
- timeout, 재시도 횟수, rate limit backoff는 외부 API의 현행 공식 제한 및 배포 설정에 맞춘다. 안전한 멱등성을 확보한 호출만 재시도한다.
- Provider 변경은 Port 구현 교체로 제한한다. Domain 공통 모델은 provider의 특수 기능을 흡수하지 않는다.

## Port/Adapter 구조

```text
Application Use Case
  ├─ TranscriptionProvider
  ├─ MinutesGenerationProvider
  ├─ DocumentProvider
  ├─ EmailProvider
  └─ NotificationProvider
       └─ Adapter-specific client and DTO
```

각 Port는 업무 목적의 명령/응답을 노출하고 HTTP, SDK 타입, 외부 ID 형식을 감춘다. Adapter가 credential 조회, HTTP 호출, 제한 처리, 결과 정규화, 오류 분류를 담당한다.

## EXT-001 Transcription / Speaker Diarization

### Port 계약

```text
transcribeWithDiarization(TranscriptionCommand) -> TranscriptResult
```

입력:

```text
TranscriptionCommand = {
  audio: assembled temporary bytes or protected file reference,
  mimeType: string,
  languageHint?: string,
  meetingDurationMs: integer
}
```

출력:

```text
TranscriptResult = {
  speakers: [{providerLabel, speakerId, label}],
  segments: [{segmentId, speakerId, startMs, endMs, text}]
}
```

### 변환/검증 규칙

- 선택된 모델/API는 배포 시 공식 지원되는 speaker diarization 기능을 제공해야 한다. 모델명은 영구 도메인 계약이 아니며 `TRANSCRIPTION_MODEL` 설정으로 선택한다.
- Provider의 화자 ID가 없거나 불안정하면 단일 transcription 실행 내에서 결정적인 internal `speakerId`로 정규화한다.
- 모든 segment의 speaker 참조, timestamp 범위, 결과 정렬을 검증한다.
- Provider 결과에서 자동 실명 인식/voiceprint를 시도하지 않는다.
- 외부 최대 파일 크기나 duration 제한에 걸리면 처리 실패를 사용자에게 안전한 코드로 표시한다. Audio 장기 보관이나 새 Object Storage는 자동 도입하지 않는다.
- Transcript 원문 및 Audio를 요청/응답 로그에 남기지 않는다.

## EXT-002 Minutes Generation

### Port 계약

```text
generateMinutes(MinutesCommand) -> StructuredMinutes
```

입력에는 `templateId`, `templateVersion`, Template prompt/content, 최소 Participant 참조 목록, Speaker mapping, 표준 Transcript가 들어간다. 출력은 `data-spec.md`의 Structured Minutes와 일치해야 한다.

### 생성 원칙

- 모델명은 `MINUTES_MODEL` 설정이며 배포 시 선택한다.
- 가능한 경우 Markdown 대신 schema-constrained 구조화 출력을 사용한다.
- `default.md`와 `project.md`는 Backend static resources로 관리한다.
- 재생성 요청에는 Transcript와 Speaker mapping만 사용하며 Audio/STT Port를 호출하지 않는다.
- 근거 없는 결정/담당자/날짜를 만들지 않도록 prompt와 출력 검증에서 제약한다.
- 모델 출력 parsing 실패, 필수 필드 누락, schema 위반은 `PROCESSING_FAILURE`로 분류한다.

## EXT-003 Document Provider

### 공통 Port

```text
validateConnection() -> ProviderHealth
discoverStructure(rootId) -> DocumentStructure
listMeetings(max=100) -> MeetingSummary[]
getMeeting(documentId) -> MeetingDocument
findMeetingBySessionId(sessionId) -> Optional<MeetingDocumentRef>
createMeeting(command) -> MeetingDocumentRef
listParticipants() -> Participant[]
createParticipant(command) -> Participant
updateParticipant(participantId, command) -> Participant
```

Provider capability는 root 검증, child page 탐색/생성/읽기, 최소 metadata 갱신, URL 반환이다. 공통 계약은 Page hierarchy/basic CRUD만 사용한다. Database/Data Source, provider 내 검색 서비스, 임의 custom schema 기능은 요구하지 않는다.

### 초기 구조 검사

1. 설정된 root 식별자 접근 가능 여부를 확인한다.
2. root 직속 `Meetings`, `Participants` child page를 발견한다.
3. `Meetings` 또는 `Participants` child가 빠졌으면 health를 unhealthy로 보고하고 요청을 `DOCUMENT_STRUCTURE_NOT_FOUND`로 실패시킨다. V1 Adapter는 누락 구조를 묵시적으로 생성하지 않는다.
4. 새 Participant/Meeting은 각 지정 child 아래에만 생성한다.

### Publish 멱등성

- `externalSessionId`를 Provider metadata/property에 기록한다.
- Create 전 `findMeetingBySessionId`를 수행한다.
- 동일 Session의 기존 문서가 있으면 생성 대신 해당 문서 참조를 재사용한다.
- 검색/metadata 접근은 Adapter에서 숨긴다.
- 부분 실패 뒤 재시도에서도 중복 Meeting page를 만들지 않도록 create와 metadata 기록 순서를 검증한다.

### Notion Adapter

- Page hierarchy에서 child page를 나열/읽기/생성/갱신한다.
- Notion Database/Data Source 의존 기능을 사용하지 않는다.
- 내부 `documentId`와 URL로 변환하며 Page 원본 JSON은 밖으로 내보내지 않는다.

### Confluence Adapter

- V1 대상은 Confluence Cloud이며 REST API v2를 사용한다. `CONFLUENCE_BASE_URL`은 `https://<site>.atlassian.net` 형식의 사이트 origin이다.
- `CONFLUENCE_ACCOUNT_EMAIL`과 `CONFLUENCE_AUTH_TOKEN`으로 Basic 인증을 구성한다. Authorization 값은 UTF-8 `email:API token`을 Base64 인코딩한 뒤 `Basic` scheme으로 전송한다. Password 기반 인증은 사용하지 않는다.
- 설정된 `DOCUMENT_ROOT_ID`의 직속 자식은 `GET /wiki/api/v2/pages/{id}/direct-children`으로 페이지네이션해 조회한다. 응답 중 `type=page`인 항목만 사용하며 Database, Folder, Whiteboard, Embed 등은 무시한다.
- 정확한 제목 `Meetings`, `Participants`의 직속 Page가 각각 하나씩 있어야 한다. 누락 또는 중복이면 `DOCUMENT_STRUCTURE_NOT_FOUND`를 반환하고 생성/복구하지 않는다.
- Basic 자격 증명은 Backend runtime secret/config에서만 읽는다. Authorization header, email, token, 원본 오류 본문을 응답이나 로그에 남기지 않는다.
- child page 조회/읽기/생성/갱신, 최소 content metadata/property를 처리한다.
- 내부 `documentId`와 URL로 변환하며 vendor response는 Adapter에 격리한다.

## EXT-004 Email Provider

### Port 계약

```text
validateConnection() -> ProviderHealth
sendMeetingEmail(command) -> RecipientDeliveryResult[]
```

Command는 회의 작성자가 선택한 Participant roster record의 이메일 주소, 제목, 요약/본문, 성공적으로 저장된 Document URL을 포함한다. Provider 호출 직전에 이메일 주소 형식을 검증한다. 수신자마다 독립 성공/실패를 반환하고 성공 수신자에게 재전송하지 않는다.

V1 Email Adapter는 `EMAIL_PROVIDER=GMAIL_API`일 때 Gmail API를 사용한다. Backend runtime secret/config에 `EMAIL_OAUTH_CLIENT_ID`, `EMAIL_OAUTH_CLIENT_SECRET`, `EMAIL_OAUTH_REFRESH_TOKEN`을 주입하고 Gmail `https://www.googleapis.com/auth/gmail.send` scope로 offline OAuth 2.0 access token을 갱신한다. Refresh token은 Google 계정의 최초 사용자 동의로 별도 발급하며 제품이 OAuth 동의/계정 연결 화면을 제공하지 않는다. Service account/domain-wide delegation은 범위에 포함하지 않는다.

Adapter는 Gmail `users.messages.send`와 RFC 2822 MIME/base64url 메시지 형식을 사용한다. 선택된 수신자마다 별도 메시지 한 건을 보내 다른 수신자 주소가 서로에게 노출되지 않도록 한다. Gmail API 성공 응답은 Gmail의 전송 접수를 뜻하며 수신함 도착/열람까지 보장하지 않는다. Empty recipient list는 발송하지 않고 빈 결과를 반환한다.

Email은 Document 저장 성공 이후에만 발송한다. 전체 주소는 운영 로그/Admin Slack에 노출하지 않는다. Gmail OAuth client secret/refresh token은 Render 환경 secret으로만 저장한다. access token과 Authorization 값은 응답/로그에 쓰지 않는다. `validateConnection()`은 설정과 OAuth token refresh를 검증하며 메일을 발송하지 않는다. Gmail `gmail.send` 범위 외 mailbox read/modify scope는 요청하지 않는다.

## EXT-005 Notification Provider

### Port 계약

```text
validateConnection() -> ProviderHealth
sendMeetingPublished(command) -> DeliveryResult
sendAdminIncident(command) -> DeliveryResult
```

회의 알림 입력은 title, meeting date, document URL이다. Admin incident 입력은 오류 분류, sessionId, stage, traceId, 안전한 오류 요약이다.

V1 `NotificationProvider` adapter는 `NOTIFICATION_PROVIDER=SLACK`일 때 `SLACK_MEETING_WEBHOOK_URL` Incoming Webhook을 사용한다. 설치 때 webhook에 고정한 Channel로만 메시지를 보내고 payload에서 channel/username/icon을 변경하지 않는다. Meeting message는 title, meeting date, Document URL만 포함한다. Slack HTTP 200 body `ok`를 받으면 요청 수락으로 기록하며 실제 구성원 열람/보관을 보장하지 않는다.

Incoming Webhook URL은 secret이다. `validateConnection()`은 health 조회만으로 test message를 게시하지 않는다. `notification.reachable`은 가장 최근 실제 webhook POST 성공 결과를 뜻하고 아직 발송이 없으면 false다. 회의 알림은 Document 저장 성공 뒤 수행하고 Email과 독립 상태로 기록한다. 알림 실패는 Document/Email 성공을 되돌리지 않는다.

## Admin Incident 안전 규칙

허용 payload:

```json
{
  "incidentType": "PROCESSING_FAILURE",
  "sessionId": "ms_xxx",
  "stage": "TRANSCRIPTION",
  "traceId": "tr_xxx",
  "message": "Transcription failed",
  "retryable": true,
  "occurredAt": "2026-10-04T02:16:00Z"
}
```

포함 금지: Audio, Transcript, Minutes 본문, Token/Secret, 전체 Email 주소, Provider의 원본 error body. 오류 분류는 `PROCESSING_FAILURE`, `DOCUMENT_FAILURE`, `EMAIL_FAILURE`, `NOTIFICATION_FAILURE` 네 종류만 사용한다.

## Health 확인 의미

- `configured`: 필수 설정값이 존재하고 형식이 유효함.
- `reachable`: 안전한 확인 요청이 Provider에 도달하고 인증됨.
- `rootAccessible`: Document root와 필수 child 구조 접근 가능함.
- `DOCUMENT_PROVIDER`가 비었거나 공백이면 자동 선택하지 않는다. App Config는 `document.provider=null`, `configured=false`를 반환하며 Frontend가 연결 안내를 제공한다. 비어 있지 않은 미지원 enum은 Backend 설정 오류다.
- Integration Health는 `document`, `email`, `notification`, `ai` 영역을 항상 포함한다. Provider Health contributor가 없는 영역은 `configured=false`, `reachable=false`로 시작하고 해당 Provider 설계/구현이 공통 aggregator에 contributor를 추가한다.
- Document Health의 `rootAccessible`은 Root와 필수 직속 `Meetings`/`Participants` 구조를 모두 탐색할 수 있을 때만 true다. 구조 누락/중복은 Health 조회를 실패시키지 않고 false 상태로 표현한다.
- Health endpoint는 key 자체, 외부 상세 오류 본문, 개인정보를 반환하지 않는다.
- Health 확인 요청은 불필요한 문서/메시지/메일을 생성하지 않는다.

## 환경 설정과 Secret

```text
APP_COMPANY_ID
APP_COMPANY_NAME
APP_TIMEZONE
DOCUMENT_PROVIDER
DOCUMENT_ROOT_ID
NOTION_TOKEN
CONFLUENCE_BASE_URL
CONFLUENCE_ACCOUNT_EMAIL
CONFLUENCE_AUTH_TOKEN
OPENAI_API_KEY
TRANSCRIPTION_MODEL
MINUTES_MODEL
EMAIL_PROVIDER
EMAIL_OAUTH_CLIENT_ID
EMAIL_OAUTH_CLIENT_SECRET
EMAIL_OAUTH_REFRESH_TOKEN
EMAIL_SENDER_ADDRESS
NOTIFICATION_PROVIDER
SLACK_MEETING_WEBHOOK_URL
SLACK_ADMIN_WEBHOOK_URL
ALLOWED_ORIGINS
TEMP_AUDIO_DIR
```

실제 값은 Render Secret/환경 설정에 저장한다. sample 파일은 값 없는 placeholder만 담는다. Frontend에는 Backend public URL 외 Provider 설정을 넣지 않는다.

## 실패 분류와 재시도

| 연동 | 운영 분류 | 안전한 재시도 조건 |
|---|---|---|
| Audio assembly/STT/Minutes | `PROCESSING_FAILURE` | 같은 Session/job에 중복 side effect가 없거나 작업 상태로 보호됨 |
| Notion/Confluence | `DOCUMENT_FAILURE` | `externalSessionId`로 기존 문서 탐색 가능 |
| Email | `EMAIL_FAILURE` | 실패한 수신자 delivery만, provider가 성공 수락한 건 제외 |
| Slack/Notification | `NOTIFICATION_FAILURE` | 성공 delivery는 제외, 명시 실패만 재시도 |

외부 timeout 시 처리 결과가 불명확할 수 있다. Document는 Session ID 조회로 판별한다. Email/Slack의 중복 허용 여부는 Provider의 idempotency capability가 없는 경우 보장할 수 없으므로 응답 불명 timeout은 임의로 반복하지 않고 결과 불명 상태를 노출한다.

## 추적성

PRD 외부 계약 `EXT-001~005`, API-019, FR-007~009, FR-016~019, FR-024~026, FR-029~030, TASK-002, TASK-006, TASK-010~017과 연결된다.

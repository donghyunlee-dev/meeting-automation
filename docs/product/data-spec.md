# Meeting Automation Data Specification

> **문서 역할:** V1의 표준 데이터 모델, 메모리 수명주기, Provider 저장 형태를 정의한다.
> **기준 문서:** [PRD.md](./PRD.md)
> **상호 참조:** [architecture.md](./architecture.md), [api-spec.md](./api-spec.md)
> **기준일:** 2026-10-04

## 설계 원칙

- 별도 application database를 두지 않는다.
- 진행 중 Session과 조회 cache는 메모리에만 둔다.
- Participant와 완료 Meeting 문서는 Document Provider가 영속 Source of Truth다.
- Audio는 처리용 임시 데이터이며 처리 이후 삭제한다.
- 모든 모델은 Provider SDK 타입과 분리된 표준 DTO/Domain model이다.
- 시간은 ISO 8601 offset/Z 표기를 사용한다. Segment 위치는 정수 millisecond다.

## 저장 위치와 수명주기

| 데이터 | 정본/저장 위치 | 수명 |
|---|---|---|
| Company, Provider 설정 | Backend 환경 설정 | 배포 기간 |
| Secret | Render Secret | 배포 기간, 비응답 |
| Templates | Backend static resources | 배포 버전 |
| Participants | Document Provider `Participants` child page | 비활성화 전까지 |
| 완료 Meeting, Minutes, Transcript | Document Provider `Meetings` child page | Provider 정책에 따름 |
| 진행 MeetingSession | Backend memory | 완료/실패 또는 프로세스 재시작까지 |
| 브라우저 대기 Audio chunk | IndexedDB 등 Browser temporary storage | 업로드 ACK/세션 정리까지 |
| Backend chunk 및 assembled Audio | 설정된 임시 디렉터리 | processing 종료 후 삭제 |
| Meeting list cache | Backend memory | 짧은 휘발성 cache, 재조회 가능 |

Session은 서버 재시작 뒤 복구되지 않는다. 구현은 서버 영속화를 암묵적으로 기대하지 말고, 상태 조회 시 휘발된 Session에 `SESSION_NOT_FOUND`를 반환한다. Browser의 미전송 chunk는 재전송할 수 있지만 Session이 유실된 경우 새 Session 재생성이 필요할 수 있다.

## 식별자와 공통 필드

| 식별자 | 형식 예시 | 생성자 | 범위 |
|---|---|---|---|
| `sessionId` | `ms_<opaque>` | Backend | Meeting workflow |
| `participantId` | `pt_<provider-id>` | Document Adapter 또는 Backend 표준화 | 회사 Participants |
| `speakerId` | `speaker_a` | Backend 표준화 | Session Transcript |
| `segmentId` | `seg_<opaque>` | Backend 표준화 | Session Transcript |
| `documentId` | `doc_<provider-id>` | Document Adapter | Provider namespace |
| `deliveryId` | `dlv_<opaque>` | Backend | Session delivery |
| `traceId` | `tr_<opaque>` | API boundary | 한 요청/연계 처리 |

Opaque ID의 실제 encoding은 구현 세부사항이다. ID를 순차 수치로 노출하지 않는다.

## MeetingSession 모델

```json
{
  "sessionId": "ms_xxx",
  "version": 3,
  "status": "REVIEW",
  "meeting": {
    "title": "AX 주간회의",
    "templateId": "default.md",
    "participantIds": ["pt_001", "pt_002"],
    "timezone": "Asia/Seoul",
    "startedAt": "2026-10-04T10:00:00+09:00",
    "endedAt": "2026-10-04T10:45:00+09:00"
  },
  "speakers": [
    {"speakerId": "speaker_a", "label": "Speaker A", "participantId": "pt_001"}
  ],
  "transcript": [
    {"segmentId": "seg_001", "speakerId": "speaker_a", "startMs": 0, "endMs": 4200, "text": "회의를 시작하겠습니다."}
  ],
  "minutes": {
    "templateId": "default.md",
    "templateVersion": "1.0.0",
    "summary": "...",
    "discussionPoints": [],
    "decisions": [],
    "actionItems": [],
    "followUps": []
  },
  "document": null,
  "deliveries": []
}
```

### Meeting fields

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---:|---|
| `title` | string | Y | 공백 제거 후 비어 있으면 거절 |
| `templateId` | enum string | Y | `default.md`, `project.md` 중 하나 |
| `participantIds` | string[] | Y | 선택된 활성 Participant ID, 중복 불가 |
| `timezone` | IANA zone string | Y | 브라우저 표시/현지 시간 변환에 사용 |
| `startedAt`, `endedAt` | offset datetime/null | 조건부 | 녹음 시작/종료 기록. 종료 전에는 null 허용 |

Meeting 시작 전에 제목, Template, Participant를 확정한다. 회의 생성 입력에는 PRD API 예시의 `recoveryKey`가 있지만 이는 API 전달용 복구 식별자이며 Provider Meeting 정본의 업무 필드가 아니다.

### Speaker와 TranscriptSegment

```text
Speaker = { speakerId, label, participantId? }
TranscriptSegment = { segmentId, speakerId, startMs, endMs, text }
```

- `speakerId`는 Transcript의 화자 원천 ID다. Participant 이름을 Transcript 문장에 치환해 저장하지 않는다.
- `participantId`가 null일 수 있는 것은 검토 중에만 허용한다. Confirm 시 모든 감지 Speaker가 매핑되어야 한다.
- 하나의 Speaker mapping 변경은 해당 `speakerId`를 가진 모든 Segment 표시 결과에 즉시 적용한다.
- `startMs >= 0`, `endMs >= startMs`; segment는 시간순 정렬한다.
- Transcript text 원문을 오류/일반 로그에 남기지 않는다.

### Structured Minutes

```json
{
  "templateId": "default.md",
  "templateVersion": "1.0.0",
  "summary": "string",
  "discussionPoints": ["string"],
  "decisions": ["string"],
  "actionItems": [
    {"ownerParticipantId": "pt_001", "task": "API 검토", "dueDate": "2026-10-10"}
  ],
  "followUps": ["string"]
}
```

각 필드는 생성 및 편집 가능한 구조화 필드다. `ownerParticipantId`는 실제 Participant를 참조하거나 null이다. 날짜는 `YYYY-MM-DD` 또는 null이다. AI는 Transcript 근거가 없는 결정, 담당자, 날짜를 만들어 내지 않는다. Template별 Markdown은 Backend renderer가 이 구조를 표시하는 데 사용한다.

## Chunk 업로드 데이터

Chunk는 session 내 0부터 시작하는 정수 `sequence`로 식별한다. 각 저장 항목은 다음 metadata와 bytes로 구성한다.

```text
AudioChunk = {
  sessionId,
  sequence,
  mimeType,
  byteLength,
  sha256,
  bytes
}
```

- 동일 `(sessionId, sequence, sha256)` 업로드는 중복 전송으로 간주하고 기존 ACK를 반환한다.
- 동일 sequence인데 checksum 또는 byte length가 다르면 `AUDIO_CHUNK_CONFLICT`다.
- 전체 수신 sequence가 `0..expectedChunks-1`를 빠짐없이 채우기 전 processing을 시작할 수 없다.
- content length와 checksum을 서버에서 검증한다. 클라이언트 선언값만 신뢰하지 않는다.
- chunk 시간/크기 정책은 Session 생성 응답에서 전달한다.

## Delivery 모델

```text
Delivery = {
  deliveryId,
  channel: EMAIL | NOTIFICATION,
  recipientParticipantId?: string,
  status: PENDING | SENDING | SENT | FAILED,
  attemptCount,
  lastAttemptAt?: datetime,
  errorCode?: string,
  retryable: boolean
}
```

Email은 수신자별 독립 Delivery다. Notification은 선택된 목적지에 대한 별도 Delivery다. 외부 응답 원문, 인증 값, 전체 이메일 주소는 Session delivery 이력이나 로그에 저장하지 않는다.

## 문서 Provider 저장 구조

```text
Meeting Automation (configured root)
├─ Meetings
│  └─ <meeting date> <title> (one provider page per meeting)
└─ Participants
   └─ <participant name> (one provider page per participant)
```

다른 자동 생성 문서 계층은 만들지 않는다. Meeting page 본문은 회의 정보, 요약, 논의사항, 결정사항, Action Items, 후속 확인사항, Transcript 순서다.

필수 최소 metadata:

```json
{
  "schemaVersion": "1.0",
  "externalSessionId": "ms_xxx",
  "meetingAt": "2026-10-04T10:00:00+09:00",
  "templateId": "default.md",
  "participantIds": ["pt_001", "pt_002"]
}
```

Provider가 허용하는 최소 속성으로 저장한다. `externalSessionId`는 Publish 멱등 조회에 쓰며, Provider query가 완전 일치 검색을 보장하지 않으면 Adapter에서 child 목록 metadata를 확인한다.

Participant 표준 모델:

```json
{"id":"pt_001","name":"김동현","email":"user@example.com","active":true}
```

부서, 직급, Slack ID, Voiceprint, Meeting History 필드는 V1 표준 모델에 추가하지 않는다. 비활성화는 삭제 대신 `active=false`로 표현한다.

## 목록/캐시

- Meeting 목록 최대치는 100이며 기본 요청 limit은 100이다.
- 서버 검색 인덱스와 DB pagination은 없다. Provider 목록 제한/페이지 탐색을 Adapter가 처리한다.
- 브라우저가 최대 결과 안에서 제목 및 참석자 이름으로 필터한다.
- cache는 성능 보조 수단일 뿐 정본이 아니다. 만료나 프로세스 재시작 후 Provider에서 다시 조회한다.

## 민감 데이터 분류

| 분류 | 예 | 처리 |
|---|---|---|
| Secret | API key, token, webhook | 배포 secret store 전용, 응답/로그 금지 |
| 민감 콘텐츠 | Audio, Transcript, Minutes | 필요한 처리에만 사용, 일반 로그/Admin Slack 금지 |
| 개인 정보 | 이름, 이메일 | 업무상 필요 최소 사용, Admin Slack에 전체 이메일 금지 |
| 운영 상관관계 | sessionId, traceId, stage | 구조화 로그 허용 |

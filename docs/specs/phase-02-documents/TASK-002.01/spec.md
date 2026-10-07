# DocumentProvider 공통 계약 및 계약 테스트

## 작업 식별 정보

- 작업: `TASK-002.01`
- 상위 작업 묶음: `TASK-002 Document Provider Foundation`
- 단계: `Phase 2 — Document / Participants`
- PRD 기준: `PRD-MA-001`, 버전 `1.6.0`, 기준일 `2026-10-05`
- 관련 요구사항: `DEC-006`, `DEC-013`, `DEC-014`, `EXT-003`, `API-003`, `API-004`, `API-005`, `API-017`, `API-018`, `API-019`
- 영역: `BE`
- 선행 작업: `TASK-001.05` FE/BE 독립 빌드 및 환경 샘플 (#5)
- GitHub Issue: [#6](https://github.com/donghyunlee-dev/meeting-automation/issues/6)

## 결과

Notion/Confluence 세부 구현과 분리된 `DocumentProvider` Port 및 표준 DTO/error 계약을 고정한다. 공통 계약 테스트는 Adapter 구현 전에 작성하고, 향후 각 Adapter가 같은 검증 모음을 실행할 수 있게 한다.

## 범위

- Backend 내부에 `DocumentProvider` Port를 정의하고 EXT-003에 명시된 동작을 Provider-neutral 표준 타입으로 표현한다: 연결 확인, Root 구조 탐색, Meeting 목록/읽기/Session ID 조회/생성, Participant 목록/생성/수정.
- Port 서명은 EXT-003을 따른다: `validateConnection() -> ProviderHealth`, `discoverStructure(rootId) -> DocumentStructure`, `listMeetings(max=100) -> MeetingSummary[]`, `getMeeting(documentId) -> MeetingDocument`, `findMeetingBySessionId(sessionId) -> Optional<MeetingDocumentRef>`, `createMeeting(command) -> MeetingDocumentRef`, `listParticipants() -> Participant[]`, `createParticipant(command) -> Participant`, `updateParticipant(participantId, command) -> Participant`.
- `ProviderHealth`는 `configured`, `reachable`, `rootAccessible` 상태를 제공한다. `DocumentStructure`는 설정 Root와 그 직속 `Meetings`, `Participants` Page 참조를 제공한다. Root의 누락된 child는 자동 생성하지 않는다.
- `MeetingSummary`는 API-017 필드(`documentId`, `title`, `meetingAt`, `participants[{id,name}]`, `documentUrl`)를, `MeetingDocument`는 API-018 필드(`documentId`, `title`, `meetingAt`, `participants`, `minutes`, `transcript`, `documentUrl`)를 제공한다.
- `MeetingDocumentRef`는 `documentId`, `documentUrl`만 외부 경계로 반환한다. Provider Page ID는 표준 `documentId`로 변환하고 Page 원본 JSON, Vendor SDK 타입, 원본 오류 본문은 Port 밖으로 전달하지 않는다.
- `CreateMeetingCommand`는 `title`, offset datetime `meetingAt`, 표준 Participant 참조, Structured Minutes, Transcript와 metadata `{schemaVersion, externalSessionId, meetingAt, templateId, participantIds}`를 가진다. `externalSessionId`는 Session ID를 사용한다. 생성 후 provider-assigned identity는 `MeetingDocumentRef`로 반환한다.
- Participant DTO와 명령은 PRD/Data Specification/API-003~005 모델을 따른다. `Participant`는 `{id,name,email}`, 생성 명령은 `{name,email}`, 수정 명령은 `name`, `email` 중 하나 이상이다.
- Root 구조, 표준 Meeting 읽기, Meeting 생성/참조, Participant 생성·수정의 공통 테스트 계약을 작성한다. 테스트는 Provider factory를 주입받는 재사용 가능한 JUnit 5 계약 모음과 결정적인 test fixture로 구성한다. TASK-002.02/.03에서 실제 Adapter를 해당 계약 모음에 연결한다.
- 오류 코드는 기존 규약만 사용한다. 구조 누락은 `DOCUMENT_STRUCTURE_NOT_FOUND`(HTTP 422, 재시도 불가), `getMeeting`의 미존재 문서는 API-018의 `MEETING_NOT_FOUND`(HTTP 404, 재시도 불가), `listParticipants` 조회 실패는 API-003의 `PARTICIPANT_LIST_FAILED`(HTTP 502, 조건부 재시도), 그 밖의 Provider 작업 실패는 `DOCUMENT_FAILED`(HTTP 502, 상황별 재시도)로 표현한다. Provider 작업 실패는 모두 운영 분류 `DOCUMENT_FAILURE`를 사용한다. 자격 증명·Provider 응답 원문은 오류에 포함하지 않는다.

## 명시적 제외 범위

- Notion 또는 Confluence SDK, HTTP client 및 Adapter 구현을 추가하지 않는다. 각 Adapter는 TASK-002.02와 TASK-002.03이 소유한다.
- Provider 설정 선택과 실제 연결 Health API 구현은 TASK-002.04가 소유한다. 이 작업은 Health Port의 표준 결과만 정의한다.
- Meetings/Participants Page를 자동 생성하거나 Database/Data Source, Provider 전용 검색/스키마 기능을 도입하지 않는다.
- Publish workflow, 재시도 조정, 이메일/Slack 연동, API Controller를 구현하지 않는다.
- PRD에 없는 새로운 오류 코드, 영속 저장 계층 또는 Provider 전용 타입을 Domain 계약에 추가하지 않는다.

## 완료 기준

- `DocumentProvider`의 EXT-003 메서드와 입력/출력 타입이 Vendor SDK와 분리되어 선언된다.
- 구조 결과는 root 및 직속 `Meetings`, `Participants` 참조를 표현하고 누락 구조를 생성하지 않는다.
- Meeting 목록/읽기와 Participant 모델이 PRD/API/Data Specification의 필드와 일치한다. 생성 결과에는 `documentId`, `documentUrl`이 포함된다.
- 구조 누락은 기존 `DOCUMENT_STRUCTURE_NOT_FOUND`, 문서 미존재는 API-018의 `MEETING_NOT_FOUND`, 참가자 목록 조회 실패는 API-003의 `PARTICIPANT_LIST_FAILED`, 나머지 Provider 작업 실패는 `DOCUMENT_FAILED` 계약을 사용한다. Provider 실패의 운영 분류는 `DOCUMENT_FAILURE`이며 원 Provider 오류 본문과 Secret은 노출되지 않는다.
- 공통 계약 테스트는 Adapter 구현 전에 구조 탐색·표준 읽기·생성의 정상/오류 사례를 고정한다. 테스트 fixture에서 잘못된 계약 구현은 assertion 실패로 검출되고 테스트 자체는 독립 실행 가능하다.
- Backend 단위 테스트와 `./gradlew clean build`가 성공한다. test fixture가 실제 Provider에 연결하지 않는다.
- 결정 내용과 테스트 결과를 `docs/evidence/TASK-002.01.md`에 비민감 정보로 기록한다.

## 결정 및 전제

- Root 구조는 PRD `Meeting Automation > Meetings`, `Participants`를 따르며 두 child는 Root 바로 아래에 둔다.
- `DocumentStructure`의 최소 필드는 `rootId`, `meetingsPageId`, `participantsPageId`다. Page 제목이나 원본 응답을 추가 노출하지 않는다.
- 구조 누락은 API Specification의 `DOCUMENT_STRUCTURE_NOT_FOUND`로 고정한다. 전송/권한 등 Provider 작업 실패는 기존 `DOCUMENT_FAILED`로 정규화하고 운영 분류는 `DOCUMENT_FAILURE`로 둔다.
- `externalSessionId`와 `schemaVersion`, `meetingAt`, `templateId`, `participantIds` metadata는 Data Specification 계약을 따른다. Publish의 중복 방지 동작은 `findMeetingBySessionId`로 노출하고 전체 멱등 workflow는 후속 Publish 작업이 소유한다.
- 선행 Issue #5는 PR #83으로 병합되고 완료됐다. TASK-002.01 구현을 시작할 수 있다.

## 연결된 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [테스트 계획](./test.md)

## 인접 작업 계약

- 선행 작업: #5가 Frontend/Backend 독립 빌드와 환경 경계를 제공한다.
- 후속 작업: `TASK-002.02`와 `TASK-002.03`은 공통 계약 모음으로 각각 Notion/Confluence Adapter를 검증한다. `TASK-002.04`는 `ProviderHealth`를 이용해 선택 Provider 상태를 API에 연결한다.
- 기능 소비자: 참가자 작업은 표준 Participant 모델을, Publish/History 작업은 `MeetingSummary`, `MeetingDocument`, `MeetingDocumentRef`를 사용한다.

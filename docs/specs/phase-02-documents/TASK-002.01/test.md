# DocumentProvider 공통 계약 및 계약 테스트 계획

## 테스트 목표

DocumentProvider Port의 Provider-neutral 구조 탐색, 표준 문서 읽기/생성 결과, 오류 정규화 계약을 고정한다.

- GitHub Issue: [#6](https://github.com/donghyunlee-dev/meeting-automation/issues/6)
- 선행 조건: TASK-001.05 (#5) 구현 완료
- 실행 위치: `backend/`

## 자동 테스트

### 공통 계약 모음 자체 실행

- 절차: 결정적인 fake provider를 `DocumentProviderContractTest`에 주입한다.
- 기대 결과: 동일 계약 모음을 후속 Notion/Confluence Adapter가 재사용할 수 있고 테스트는 실제 외부 연결을 요구하지 않는다.
- TDD red: 불완전 fixture를 대상으로 테스트를 먼저 실행한다. 모듈/컴파일 오류가 아니라 Root mapping, DTO, 오류 code assertion이 실패해야 한다.
- TDD green: fake를 계약에 맞춘 뒤 모든 contract assertion이 통과한다.
- 증거: 대상 테스트 초기 assertion 실패와 최종 통과 결과.

### Root/child Page 구조

- 입력: `rootId`와 root 직속 `Meetings`, `Participants` child page 두 개를 가진 fixture.
- 기대 결과: `DocumentStructure`에 `rootId`, `meetingsPageId`, `participantsPageId`가 정확히 매핑된다.
- 경계 입력: 두 child 중 하나가 누락된다.
- 기대 결과: 누락 Page를 자동 생성하지 않고 `DOCUMENT_STRUCTURE_NOT_FOUND`로 실패한다. 공개 API 경계에서 HTTP 422, 재시도 불가로 매핑한다.
- 증거: 구조 필드 assertion, 호출 기록상 create 미호출, 오류 코드 assertion.

### 표준 읽기 및 생성 참조

- Meeting 목록 입력: 표준 Meeting metadata와 Participant 참조가 있는 fixture.
- 기대 결과: 목록 항목은 `{documentId,title,meetingAt,participants:[{id,name}],documentUrl}` 필드를 제공한다.
- Meeting 상세 입력: 같은 문서의 minutes와 transcript 포함 표준 콘텐츠.
- 기대 결과: 상세는 API-018 필드에 맞고 Provider 원본 JSON은 DTO에 나타나지 않는다.
- 생성 입력: Meeting document의 제목, meetingAt, 참여자, Structured Minutes/Transcript와 필수 metadata를 포함한다.
- 기대 결과: 생성된 `MeetingDocumentRef`에 표준 `documentId`, `documentUrl`이 있으며 Provider Page ID/응답은 밖으로 전달되지 않는다.
- Participant 입력: 생성 `{name,email}`, 수정 `{name,email}` 중 하나 이상.
- 기대 결과: 표준 `{id,name,email}` roster 모델을 반환한다.
- 증거: 필드 assertion과 민감 원문 부재 assertion.

### Provider 오류 변환 및 민감 정보 경계

- 입력: Root/child 누락, 권한 거부, 일시 전송 오류 fixture.
- 기대 결과: 구조 누락은 `DOCUMENT_STRUCTURE_NOT_FOUND`; `listParticipants` 조회 실패는 API-003의 `PARTICIPANT_LIST_FAILED`; 그 밖의 Provider 작업 실패는 `DOCUMENT_FAILED`로 전달된다. 모든 Provider 작업 실패의 운영 분류는 `DOCUMENT_FAILURE`이며 구체적 재시도 가능성은 오류 종류/안전한 중복 여부를 따른다.
- 보안 assertion: Secret, Provider raw response body, SDK 예외 메시지가 표준 오류 응답/일반 로그에 포함되지 않는다.
- 증거: code/category/retryable assertion 및 비노출 assertion.

### Backend 회귀 빌드

```powershell
./gradlew test
./gradlew clean build
```

- 기대 결과: 계약 테스트 및 기존 Backend 검증이 모두 통과한다.
- 추가 점검: dependency tree에 Notion/Confluence SDK와 DB/JPA/Redis/Queue가 없다.
- 증거: Java/Gradle 버전, 명령, 종료 코드, dependency 확인 결과.

## 수동 QA

- DocumentProvider 및 DTO 소스에 Provider SDK import 또는 Vendor raw DTO 노출이 없는지 검토한다.
- `DocumentStructure`가 Root 직속 두 child만 표현하고 누락 Page 자동 생성을 시도하지 않는지 확인한다.
- 테스트와 로그를 확인해 credential, Page 원본 JSON, Provider 오류 본문이 기록되지 않는지 점검한다.
- 결과를 `docs/evidence/TASK-002.01.md`에 기록한다.

## 릴리스 전용 검사

실제 Notion/Confluence 계정 연결, Provider 권한 구성, 운영 API health는 후속 TASK-002.02~002.04에서 검증한다. 이 TASK의 test fixture에는 실 credential을 사용하지 않는다.

## 완료 기준과 검사 연결

| 완료 기준 | 검사와 증거 |
|---|---|
| EXT-003 공통 Port와 표준 DTO | 소스/API compile 및 contract assertion |
| Root/child 발견 및 누락 오류 | 구조 탐색 테스트 |
| Meeting/Participant 읽기·생성 | 표준 필드 테스트 |
| 기존 오류 코드와 민감정보 격리 | Provider 오류 및 비노출 테스트 |
| Adapter 구현 전 재사용 가능한 contract suite | 불완전 fixture red 및 정상 fixture green |
| Backend 회귀 통과 | `./gradlew test`, `./gradlew clean build` |

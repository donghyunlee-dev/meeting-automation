# Meeting 상세 조회 API 설계

## 작업 식별 정보

- 작업: `TASK-014.02`
- 상위 작업: `TASK-014 Meeting History`
- 단계: `Phase 7 — History`
- PRD 기준: `PRD-MA-001` v1.7.0, 2026-10-05
- 관련 요구사항: `FR-021`, `FR-022`, `FR-023`, `API-018`, `EXT-003`
- 영역: Backend
- 선행 작업: `TASK-014.01`
- GitHub Issue: [#53](https://github.com/donghyunlee-dev/meeting-automation/issues/53)

## 결과와 요청

Provider에 저장된 과거 Meeting 문서를 읽기 전용으로 반환한다. 원문 URL은 웹 화면에서 별도로 열 수 있도록 응답에 포함한다.

```http
GET /api/v1/meetings/{documentId}
```

`documentId`는 API-017이 반환한 불투명 표준 ID다. Provider Page ID나 Vendor 타입을 URL/API에 직접 노출하지 않는다.

## 성공 응답

```json
{
  "data": {
    "documentId": "doc_xxx",
    "title": "AX 주간회의",
    "meetingAt": "2026-10-04T10:00:00+09:00",
    "participants": [{"id": "pt_001", "name": "김동현"}],
    "minutes": {
      "templateId": "default.md",
      "templateVersion": "1.0.0",
      "summary": "...",
      "discussionPoints": [],
      "decisions": [],
      "actionItems": [],
      "followUps": []
    },
    "transcript": [
      {"segmentId":"seg_001","speakerId":"speaker_a","startMs":0,"endMs":1250,"text":"..."}
    ],
    "documentUrl": "https://provider/..."
  }
}
```

모든 성공 응답은 공통 `{data:...}` envelope를 사용한다. `meetingAt`은 Provider metadata의 ISO 8601 offset datetime을 보존한다. Participant는 문서 metadata `participantIds` 순서에 따른 `{id,name}`만 포함하며 이메일은 반환하지 않는다. Transcript segment의 필드는 `segmentId`, `speakerId`, `startMs`, `endMs`, `text`다. `speakerId`는 원 Transcript 식별자다. Transcript 문장에 현재 Participant 이름을 치환하거나 화자 매핑을 새로 추론하지 않는다.

`minutes`는 Data Specification의 `StructuredMinutes` 전체 schema를 따른다: `templateId`, `templateVersion`, `summary`, `discussionPoints`, `decisions`, `actionItems[{ownerParticipantId,task,dueDate}]`, `followUps`. Provider 원본 구조는 Adapter 내부에서 이 표준 구조로 변환한다.

## 오류와 안전 경계

- 존재하지 않는 `documentId`는 404 `MEETING_NOT_FOUND`로 응답한다. Provider Not Found만 이 오류로 매핑하며, 권한/전송/provider 오류를 “없는 문서”로 숨기지 않는다.
- 필수 `Meetings` 구조 누락은 기존 422 `DOCUMENT_STRUCTURE_NOT_FOUND`다.
- Provider 접근 실패, 본문/metadata 파싱 실패는 기존 502 `DOCUMENT_FAILED` (`DOCUMENT_FAILURE`)로 매핑한다. 불완전한 상세를 성공으로 반환하지 않는다.
- Provider 원문 URL이 없거나 `http`/`https`가 아니면 `documentUrl:null`로 반환한다. Frontend는 유효한 HTTP(S) URL만 외부 링크로 연다.
- Provider 원문 응답, Secret, 이메일, authorization 정보와 상세 콘텐츠를 일반 로그/오류에 기록하지 않는다. 응답 body에는 사용자 화면에 필요한 Transcript/Minutes가 포함되므로 API access log에서 body logging을 금지한다.
- 조회는 읽기 전용이며 과거 문서/Participant를 수정하지 않는다. Session 상태나 존재 여부에 의존하지 않는다.

## 비범위

- 목록 정렬/limit은 `TASK-014.01`; Home/Meetings 화면과 Loading/Empty/Error 상태는 `TASK-014.03`이다.
- 검색/필터와 화면 Detail 연결·외부 URL click은 `TASK-014.04`다.
- 과거 회의 수정/삭제, Transcript 재처리, Provider 상태 관리, 새로운 DB/cache 정본은 범위 밖이다.

## 완료 기준

- 표준 documentId로 상세 문서를 조회하고 공통 envelope와 API-018 필드를 반환한다.
- Minutes, Transcript, Participant roster, 원문 URL이 표준 모델로 매핑된다. 이메일은 제외된다.
- 없는 문서 404, 구조 누락 422, Provider 실패/잘못된 문서 502가 분리된다.
- URL이 누락되거나 안전한 HTTP(S)가 아니면 null이며, 부분 콘텐츠를 성공으로 반환하지 않는다.
- 조회가 읽기 전용이고 상세 본문/Secret/provider 원문이 로그에 남지 않는다.
- 모든 기준이 [검증 계획](./test.md)의 사례와 연결된다.

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

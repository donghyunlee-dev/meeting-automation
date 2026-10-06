# 구현 계획

## 의존성

- `TASK-014.01` API-017 목록의 표준 `documentId` 및 최신순 요약을 사용한다.
- `TASK-002.01`이 정의한 `DocumentProvider.getMeeting(documentId) -> MeetingDocument`와 선택된 Provider Adapter를 사용한다.
- `TASK-010.02`가 저장한 Meeting metadata, Structured Minutes, Transcript 표현을 읽는다.
- PRD v1.7.0 (2026-10-05), `FR-021~023`, `API-018`, `EXT-003` 및 API-017 응답 계약을 따른다.
- GitHub Issue: [#53](https://github.com/donghyunlee-dev/meeting-automation/issues/53).

## 변경 대상과 책임

- Backend Controller: `GET /api/v1/meetings/{documentId}` 경로 및 식별자 형식 검증.
- Application service: provider getMeeting 호출과 read-only 상세 use case 조정.
- DocumentProvider Adapter: Provider page read API를 호출하고 page ID, metadata, roster reference, body, web URL을 표준 `MeetingDocument`로 변환한다.
- Participant mapper: 가능한 경우 단일 roster snapshot을 ID map으로 변환한다. 문서에 참조된 Participant가 roster에서 제거되었으면 API-017/API-018의 참조 이름을 해결할 수 없으므로 기존 `DOCUMENT_FAILED`로 실패한다. 문서별 N+1 read는 금지한다.
- API DTO/mapper: `MeetingDocument`를 `{data:{...}}`로 변환하며 email/Provider raw data를 제거한다.
- 공통 exception mapper: Not Found, 구조 누락, Provider/parsing failure를 각각 `MEETING_NOT_FOUND`, `DOCUMENT_STRUCTURE_NOT_FOUND`, `DOCUMENT_FAILED`에 매핑한다.

## 구현 순서

이 API는 Provider 읽기 계약의 동작을 외부에 전달하므로 Adapter 매핑부터 경계 테스트로 고정하고, 그다음 application endpoint를 연결한다.

1. `DocumentProvider.getMeeting` contract/Adapter 테스트에 metadata, Structured Minutes, Transcript, URL 및 Not Found/permission/parse 오류 fixture를 추가한다.
2. Provider read path가 부족하면 선택된 Notion 또는 Confluence Adapter만 보완한다. Notion의 page content는 block children pagination을 따라가고 필요한 nested block children을 읽는다. Confluence Cloud REST API v2 page GET은 상세 body format을 지정해 사용하며, Cloud Basic 인증은 기존 Adapter 설정을 재사용한다.
3. 표준 `MeetingDocument` mapping 및 오류 정규화를 구현한다. 원 Provider page JSON이 application 경계를 넘지 않게 한다.
4. Service/API controller 테스트를 작성해 불투명 ID, common envelope, read-only 조회 및 오류 HTTP 계약을 확인한다.
5. HTTP 통합 테스트 및 민감 정보/logging 검토를 완료한다.

## 검증 및 후속 작업

자동화·수동 검증은 [검증 계획](./test.md)에 따른다. `TASK-014.04`는 이 API 결과를 표시하고 URL 열기 동작을 연결한다. 공식 API 근거: [Notion Retrieve block children](https://developers.notion.com/reference/get-block-children), [Confluence REST API v2 Page](https://developer.atlassian.com/cloud/confluence/rest/v2/api-group-page/).

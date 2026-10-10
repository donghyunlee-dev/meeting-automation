# Meetings 목록 조회 API 설계

> 📌 v1.9.0 변경 계약: 활성 전역 connection과 TASK-022.04의 문서 codec을 사용한다. 전환 완료 후 이전 Provider cache와 ID를 재사용하지 않는다. 일반 History의 목록 제한을 자료 이전 completeness 판정으로 사용하지 않는다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

## 작업 식별 정보

- 작업: `TASK-014.01`
- 상위 작업: `TASK-014 Meeting History`
- 단계: `Phase 7 — History`
- PRD 기준: `PRD-MA-001` v1.7.0, 2026-10-05
- 관련 요구사항: `FR-020`, `API-017`, `EXT-003`
- 영역: Backend
- 선행 작업: `TASK-002.02` 또는 `TASK-002.03`, `TASK-010.02`
- GitHub Issue: [#52](https://github.com/donghyunlee-dev/meeting-automation/issues/52)

## 결과

`GET /api/v1/meetings`가 설정된 Document Provider의 Meetings 하위 Meeting 문서에서 요약 목록을 읽어 공통 `{data:{items}}` 응답으로 제공한다. 목록은 최신 `meetingAt` 순이며 `limit` 범위는 1~100, 기본값은 100이다. PRD 운영 전제상 100건 미만의 이력을 조회하며 검색은 브라우저가 반환된 목록 안에서 수행한다.

## 요청 및 응답 계약

```http
GET /api/v1/meetings?limit=100
```

`limit` 생략 시 100이며 정수 1..100만 허용한다. 범위 또는 형식 오류는 400 `VALIDATION_FAILED`다.

```json
{
  "data": {
    "items": [
      {
        "documentId": "doc_xxx",
        "title": "AX 주간회의",
        "meetingAt": "2026-10-04T10:00:00+09:00",
        "participants": [{"id": "pt_001", "name": "김동현"}],
        "documentUrl": "https://provider/..."
      }
    ]
  }
}
```

`meetingAt`은 저장된 offset datetime을 그대로 보존한다. 정렬은 시간대 오프셋을 고려한 실제 시각 기준 내림차순이다. 같은 시각에는 `documentId` 오름차순으로 안정 정렬한다. `participants`는 각 문서 metadata의 `participantIds`를 Provider의 표준 Participant `{id,name,email}` roster에 대응시켜 `{id,name}`만 반환하며, 이메일은 응답에 넣지 않는다. 순서는 문서의 `participantIds` 순서를 보존한다. 빈 목록은 HTTP 200 `{data:{items:[]}}`다.

Provider `Meetings` child page의 모든 cursor/page를 따라가며 Meeting 요약 후보를 읽는다. `limit`은 child page별이 아니라 합산 목록의 정렬 이후 적용한다. 응답에는 최대 `limit`개만 포함한다. 완전한 최신 N개를 보장하려고 모든 child page를 확인한다. 첫 페이지 결과만으로 자르지 않는다. 중복 `documentId`는 한 항목으로 취급한다.

## 오류 및 데이터 품질

- 필수 `Meetings` 구조를 찾지 못하면 기존 422 `DOCUMENT_STRUCTURE_NOT_FOUND`를 반환한다.
- Provider 접근/페이지네이션 실패, 필수 metadata(`meetingAt`, `participantIds`) 파싱 실패, 또는 roster 참조 해석 실패는 기존 502 `DOCUMENT_FAILED` (`DOCUMENT_FAILURE`)로 실패한다. 불완전한 일부 목록을 성공 응답으로 반환하지 않는다.
- 잘못되거나 누락된 `documentUrl`은 `null`로 반환해 목록/앱 내 상세 접근을 유지한다. URL을 사용할 때 Frontend는 유효한 HTTP(S)만 링크로 연다.
- 원 Provider 응답, Secret, 이메일 주소, 회의 본문/Transcript/Minutes는 응답/로그에 노출하지 않는다.
- 목록 조회는 읽기 전용이며 Meeting 문서나 roster를 갱신하지 않는다. Cache가 있더라도 성능 보조 자료이며 Provider가 정본이다.

## 비범위

- 상세 문서, Minutes, Transcript 조회는 `TASK-014.02` / API-018 소유다.
- Home/Meetings UI, 최근 5건 표시, Loading/Empty/Error 화면은 `TASK-014.03` 소유다.
- 제목/참석자 필터와 Detail 이동은 `TASK-014.04` 소유다.
- 서버 전문 검색, DB/검색 인덱스, Provider Database/Data Source, Meeting 수정·삭제·상태 필드는 추가하지 않는다.

## 완료 기준

- `limit` 기본값/경계/오류와 공통 response envelope가 API-017과 일치한다.
- 빈 목록은 성공 응답이며, 정렬은 실제 `meetingAt` 최신순이고 동률은 결정적으로 정렬된다.
- child pagination을 끝까지 탐색한 뒤 최대 `limit`개만 반환하며, 중복 문서는 한 번만 표시한다.
- participant roster 이름/ID를 문서 순서대로 반환하고 이메일/본문은 제외한다.
- metadata 오류나 Provider 페이지 실패는 기존 표준 오류로 정규화하고 부분 결과를 노출하지 않는다.
- 모든 완료 기준이 [검증 계획](./test.md)의 사례에 연결된다.

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

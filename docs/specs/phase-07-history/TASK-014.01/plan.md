# 구현 계획

## 의존성

- `TASK-002.01` 공통 `DocumentProvider`/`MeetingSummary` 계약과 Notion/Confluence Adapter(#7 또는 #8 중 선택된 Provider 구현)를 소비한다.
- `TASK-010.02`가 저장하는 Meeting page와 metadata `{schemaVersion,externalSessionId,meetingAt,templateId,participantIds}`를 읽는다.
- 명세 및 PRD 추적: `FR-020`, `API-017`, `EXT-003`, PRD v1.7.0 (2026-10-05).
- Issue: [#52](https://github.com/donghyunlee-dev/meeting-automation/issues/52).

## 소유 경계와 변경 대상

- Backend REST Controller: query `limit` 바인딩 및 `VALIDATION_FAILED` 매핑.
- Application service: 범위 검증, DocumentProvider 호출, 최신순 정렬·안정 tie-break, 응답 수 제한.
- `DocumentProvider.listMeetings(max)` / Adapter: 설정된 Meetings child page를 provider-native pagination 끝까지 읽고 `MeetingSummary` 표준 필드와 participant roster 이름을 구성한다. provider 타입과 원본 payload는 Port 밖으로 넘기지 않는다.
- Participant 이름 참조가 이미 별도 port/application 계층에서 제공되면 그 계약을 재사용한다. 목록에서 전체 roster 조회가 필요하면 요청당 한 번 조회하고 ID map으로 매핑하며, 문서별 N+1 조회를 만들지 않는다.
- API DTO/mapper: `{data:{items}}` common envelope 및 API-017 필드만 직렬화한다.
- Unit/adapter/API tests: Controller boundary, service ordering/limit, provider pagination/data mapping, error translation.

## 구현 순서

API/backend 계약이 PRD와 선행 Provider port에서 이미 고정되어 있으므로, 먼저 실패 테스트로 경계 동작을 확정한 뒤 application/service와 Adapter의 누락 구간을 구현한다. 마지막으로 HTTP endpoint를 통합한다.

1. Controller/service 단위 테스트에서 query 경계, 빈 목록, 정렬·limit·오류 계약을 고정한다.
2. Adapter 계약 테스트에서 cursor 끝까지 순회, child type/page 처리, participant 참조 매핑을 고정한다.
3. `DocumentProvider` 기존 `listMeetings(max)` 계약을 유지하며 필요한 Adapter 읽기/요약 로직을 구현한다. 계약 변경이 불가피하면 TASK-002 공통 계약과 이 task의 영향 파일을 같은 변경에서 갱신한다.
4. API-017 Controller와 application service를 연결하고 공통 envelope 및 기존 오류 매퍼를 사용한다.
5. 전체 테스트에서 provider mock/fake만 사용해 두 Adapter와 endpoint의 성공·실패 경로를 통합 검증한다.

## 검증 및 인접 작업

테스트 절차 및 증거는 [검증 계획](./test.md)을 따른다. 후속 `TASK-014.02`는 API-018 상세 읽기를, `TASK-014.03`은 SCR-001/SCR-009 UI의 최근 5건·전체 표시를 이 API에서 소비한다. `TASK-014.04`는 클라이언트 측 검색을 최대 반환 집합 안에서 수행한다.

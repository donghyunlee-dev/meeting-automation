# Notion Root 하위 Page 구조 탐색 구현 계획

## 목표

Notion Root의 직속 child Page 목록을 안전하게 읽고 공통 `DocumentStructure` 계약으로 변환한다.

- GitHub Issue: [#7](https://github.com/donghyunlee-dev/meeting-automation/issues/7)
- 선행 Issue: #6 완료 후 구현 착수
- PRD 기준: `PRD-MA-001` v1.6.0, `2026-10-05`
- 공식 API 기준 확인일: `2026-10-05`, Notion API version `2026-03-11`

## 관련 원본

- [PRD](../../../product/PRD.md), DEC-013/014, API-019, EXT-003, TASK-002.02
- [Integration Specification](../../../product/integrations.md), Notion Adapter 및 초기 구조 검사
- [DocumentProvider 공통 계약](./../TASK-002.01/spec.md)
- [Notion Retrieve block children](https://developers.notion.com/reference/get-block-children): root child block 조회, pagination, read-content 권한, 필수 `Notion-Version`
- [Notion Block objects](https://developers.notion.com/reference/block): `child_page` 타입의 `id`와 평문 `title`; Database와 Page 구분
- [Notion Versioning](https://developers.notion.com/reference/versioning): 버전 헤더 의무와 `2026-03-11` 버전 baseline

## 변경 대상과 소유 경계

| 경로/컴포넌트 | 책임 |
|---|---|
| `backend/src/main/java/.../document/notion/NotionPageHierarchyAdapter.java` | 공통 Port의 Root 연결 확인/구조 발견 구현 |
| `NotionApiClient` 또는 기존 Backend HTTP 경계 | `/v1/blocks/{block_id}/children` 호출, 인증/버전 헤더, cursor 요청 |
| Notion 응답 DTO | 필요한 `id`, `type`, `child_page.title`, `has_more`, `next_cursor`, `results`만 매핑 |
| Backend 설정 | `NOTION_TOKEN`, `DOCUMENT_ROOT_ID`, 고정 `Notion-Version` 연결 |
| Adapter 테스트 | 성공/빈 구조/권한/HTTP/페이지네이션 fixture와 공통 계약 모음 |
| `docs/evidence/TASK-002.02.md` | 버전, 명령, 비민감 검증 결과 |

Java 구현은 기존 Backend HTTP/JSON 설정을 재사용하고 새로운 Notion SDK 의존성을 추가하지 않는다. Adapter 내부의 Notion DTO는 `DocumentProvider` 표준 타입으로 변환한 뒤 폐기한다.

## 구현 순서와 소유권

1. **BE — 선행 계약/현재 API 확인:** #6과 관련 소스가 원격에 있고 Notion 공식 API 기준 버전이 최신인지 확인한다. 결과: 입력 설정, 공통 반환 타입, 필수 헤더를 고정한다.
2. **BE — Adapter 실패 테스트 작성:** fake Notion HTTP 응답으로 Root/child 매핑, 페이지네이션, 빈 구조, 권한 오류를 먼저 작성한다. 결과: 테스트 harness는 실행되고 계약 assertion이 실패한다.
3. **BE — 최소 HTTP 클라이언트:** child 목록 endpoint 호출, `Bearer` 인증, `Notion-Version: 2026-03-11`, `start_cursor` 요청을 구현한다. 결과: fake server/client가 페이지별 응답을 공급한다.
4. **BE — 표준 구조 변환:** `type=child_page`이며 제목이 정확히 일치하는 직속 Page만 모아 공통 `DocumentStructure`로 바꾼다. 결과: DB 제외, 중복/누락 거부, 생성 부작용 없음.
5. **BE — 오류와 상태 정규화:** 네트워크/401/403/404 및 429/5xx를 안전한 Provider 오류/Health 필드로 변환한다. 결과: 원문 메시지와 Secret은 응답·로그에서 제외된다.
6. **BE — 공통 계약/회귀 확인:** TASK-002.01 구조 테스트 그룹, Notion fixture 테스트, `./gradlew test`, `./gradlew clean build`를 실행한다. 결과: 테스트가 모두 통과하고 Evidence가 기록된다.

Provider Adapter 작업이므로 Backend 구현을 먼저 한다. FE나 공개 Controller를 변경하지 않고 API-019 연결은 후속 TASK-002.04에 남긴다.

## 의존성

- `TASK-002.01` (#6): 공통 `DocumentProvider` 계약, 표준 DTO, 오류 코드와 테스트 fixture 인터페이스.
- `TASK-001.05` (#5): Backend 독립 Wrapper와 runtime Secret 경계.
- Backend 환경에는 `NOTION_TOKEN`, `DOCUMENT_ROOT_ID`가 주입된다. `NOTION_TOKEN`은 응답/로그/예외 텍스트에 출력하지 않는다.
- 자동 검증은 mock HTTP 경계를 사용해 실제 Notion workspace/credential에 의존하지 않는다.

## 검증 접근

- Header 검증: 모든 요청에서 Bearer token과 Notion API version 헤더가 전달되고 값이 로그에 남지 않는지 확인한다.
- 페이지네이션 검증: 첫 응답 뒤 `has_more=true`일 때 `next_cursor`를 다음 요청에 전달하고 마지막 응답까지 읽는다.
- 구조 검증: direct `child_page` 두 개만 `DocumentStructure`로 변환하고 `child_database`/손자 Page는 사용하지 않는다.
- 오류 검증: 401/403/404/429/5xx 및 네트워크 예외가 안정된 코드/상태로 변환되고 원문이 유출되지 않는지 확인한다.
- Regression: `./gradlew test`, `./gradlew clean build`; dependency diff에 Provider SDK, DB/JPA/Redis가 없는지 검사한다.

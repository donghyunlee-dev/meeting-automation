# Notion Root 하위 Page 구조 탐색

## 작업 식별 정보

- 작업: `TASK-002.02`
- 상위 작업 묶음: `TASK-002 Document Provider Foundation`
- 단계: `Phase 2 — Document / Participants`
- PRD 기준: `PRD-MA-001`, 버전 `1.6.0`, 기준일 `2026-10-05`
- 관련 요구사항: `DEC-013`, `DEC-014`, `API-019`, `EXT-003`
- 영역: `BE`
- 선행 작업: `TASK-002.01` DocumentProvider 공통 Port 계약 및 계약 테스트
- GitHub Issue: [#7](https://github.com/donghyunlee-dev/meeting-automation/issues/7)

## 결과

Backend 설정의 Notion Root Page에서 직속 `Meetings`, `Participants` child page를 찾아 `DocumentStructure`로 변환한다. Notion의 child page hierarchy만 사용하며 Database/Data Source는 조회하거나 생성하지 않는다.

## 범위

- `NOTION_TOKEN`과 `DOCUMENT_ROOT_ID`를 Backend runtime 설정에서 읽어 Notion API 요청에 사용한다. 자격증명은 호출 로그, 오류 메시지, 반환 DTO에 포함하지 않는다.
- Notion REST API의 `GET /v1/blocks/{block_id}/children`으로 Root의 첫 단계 child block을 전부 읽는다. 응답의 `type=child_page`, `id`, `child_page.title`만 구조 판별에 사용한다.
- 페이지 목록은 `has_more`와 `next_cursor`를 사용해 끝까지 순회한다. `next_cursor`는 불투명 값으로 파싱/검증하지 않고 다음 `start_cursor`에 그대로 전달한다. 페이지 크기는 결과 완전성의 기준으로 사용하지 않는다.
- 정확한 대소문자 표기 `Meetings`, `Participants`의 직속 `child_page`만 각각 container로 선택한다. `child_database`는 같은 제목이어도 Page로 취급하지 않는다.
- 두 Page가 각각 하나씩 발견되면 공통 `DocumentStructure(rootId, meetingsPageId, participantsPageId)`를 반환한다. 하나라도 없거나 동일 제목의 직속 Page가 중복되면 `DOCUMENT_STRUCTURE_NOT_FOUND`로 실패한다. Adapter는 누락 Page를 자동 생성하지 않는다.
- `validateConnection`/Root 접근의 `ProviderHealth` 필드는 공통 Port 계약을 따른다. `configured`는 token과 root ID가 모두 설정됐는지, `reachable`은 Notion API에서 HTTP 응답을 받았는지, `rootAccessible`은 Root child 조회가 성공했는지를 나타낸다. 네트워크 오류는 `reachable=false`, 권한/Root 접근 오류는 `rootAccessible=false`다. 모든 경우 실제 설정값은 반환하지 않는다.
- 401/403/404 등 접근 실패와 전송/제한 오류는 표준 `DOCUMENT_FAILED`/`DOCUMENT_FAILURE`로 변환하고 Notion 원문 오류 메시지를 감춘다. HTTP 404는 Root 미존재와 연결 권한 부족을 구분할 수 없으므로 구체 원인을 노출하지 않는다. 성공적으로 목록을 읽었는데 필수 child가 없는 경우에만 `DOCUMENT_STRUCTURE_NOT_FOUND`를 사용한다.
- Notion API 요청은 필수 `Notion-Version` 헤더를 보낸다. 2026-10-07 공식 문서 확인 결과 최신 값은 `2026-03-11`이다. 구현 시 이 버전을 고정 설정으로 관리한다.

## 명시적 제외 범위

- Meetings/Participants Page 생성, 이름 변경, 이동 또는 복구를 수행하지 않는다.
- Meeting/Participant CRUD, 본문 파싱, metadata 검색 및 `externalSessionId` 멱등 처리를 구현하지 않는다.
- API-019 Controller/응답 조립은 `TASK-002.04`가 소유한다. 이 작업은 공통 Port의 Notion 연결/Root 결과를 제공한다.
- Database, Data Source, Search API 또는 Notion 전용 스키마를 사용하지 않는다.
- 실제 workspace credential을 자동 테스트에 사용하지 않는다.

## 완료 기준

- 공통 `DocumentProvider` 구조 계약에 맞는 Notion hierarchy 구현이 Root의 직속 Page만 조사한다.
- 2개 이상의 페이지 응답에 걸친 cursor pagination 후에도 대상 child를 찾아 올바른 ID를 반환한다.
- 빈 구조, 필수 Page 하나 누락, 중복 제목 또는 Database만 존재하는 경우 Page를 만들지 않고 `DOCUMENT_STRUCTURE_NOT_FOUND`를 반환한다.
- 401/403/404와 전송 실패는 안전한 공통 오류/Health 값으로 변환되고 Secret 및 원 Provider 응답 본문은 외부에 노출되지 않는다.
- 실제 Notion 연결 없이 성공·빈 계층·권한 오류 Adapter 테스트가 통과한다.
- Backend `./gradlew test` 및 `./gradlew clean build`가 통과하고 Provider SDK 또는 금지 저장 인프라가 추가되지 않는다.
- 명령, fixture 결과, Notion API 버전과 비민감 증거를 `docs/evidence/TASK-002.02.md`에 기록한다.

## 결정 및 전제

- PRD와 공통 Port 계약에 따라 구조 이름은 `Meetings`, `Participants`이며 Root의 직속 Page만 인정한다.
- Notion block children 응답은 첫 단계 children만 제공하므로 Root ID로 페이지네이션을 완료한다. 하위 Meeting/Participant Page 재귀 탐색은 이 작업의 범위가 아니다.
- `next_cursor`는 opaque cursor로 취급해 응답 값 그대로 전달하며 형식 분석이나 UUID 검증을 하지 않는다. `has_more`만 다음 페이지 요청 여부를 결정한다.
- `rootAccessible`은 설정 Root의 child block 목록을 읽을 수 있는지 나타낸다. 목록은 읽었지만 구조가 불완전한 경우 Root 접근은 성공으로 보고 `discoverStructure`가 구조 오류를 반환한다.
- Notion 공식 API 기준 자료는 [Retrieve block children](https://developers.notion.com/reference/get-block-children), [Block objects](https://developers.notion.com/reference/block), [Versioning](https://developers.notion.com/reference/versioning)이며 2026-10-07 확인 시 `Notion-Version: 2026-03-11`을 최신으로 안내한다.
- 선행 Issue #6은 PR #84로 병합되고 완료됐다. 공통 `DocumentProvider` Port/DTO 패키지를 사용할 수 있다.

## 연결된 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [테스트 계획](./test.md)

## 인접 작업 계약

- 선행 작업: #6이 Provider-neutral Port와 `DocumentStructure`, `ProviderHealth`, 오류 코드를 제공한다.
- 후속 작업: `TASK-002.04`가 설정된 Provider의 API-019 상태에 Notion `configured`, `reachable`, `rootAccessible` 결과를 연결한다. Notion Meeting/Participant CRUD는 소유한 기능 TASK에서 별도로 연결한다.

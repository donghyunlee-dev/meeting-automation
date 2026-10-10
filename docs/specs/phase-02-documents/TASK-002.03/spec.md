# Confluence Cloud Page 계층 Adapter

> 📌 v1.9.0 변경 안내: 이 문서는 완료된 TASK-002.03의 당시 구현/검증 이력이다. Backend env로 선택·credential을 고정하는 계약과 초기 구조 생성 제외 범위는 새 TASK-022에서 변경한다. 현재 구현 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)와 TASK-022 패키지다. 일반 Health/탐색은 계속 읽기 전용이며 명시적 초기화만 구조를 생성한다. 기존 DONE/검증 증거는 보존한다.

## 작업 식별 정보

- 작업: `TASK-002.03`
- 상위 작업 묶음: `TASK-002 Document Provider Foundation`
- 단계: `Phase 2 — Document / Participants`
- PRD 기준: `PRD-MA-001`, 버전 `1.6.1`, 기준일 `2026-10-05`
- 관련 요구사항: `DEC-013`, `DEC-014`, `API-019`, `EXT-003`
- 영역: `BE`
- 선행 작업: `TASK-002.01` DocumentProvider 공통 Port 및 계약 테스트 (#6)
- 결정 기록: [GitHub Issue #8](https://github.com/donghyunlee-dev/meeting-automation/issues/8)
- 구현 Issue: [GitHub Issue #8](https://github.com/donghyunlee-dev/meeting-automation/issues/8)

## 결과

설정된 Confluence Cloud Root Page의 직속 `Meetings`, `Participants` Page를 탐색해 공통 `DocumentStructure`로 변환한다. 인증과 Vendor DTO는 Adapter 경계 안에 두고 공통 `DocumentStructureProvider` slice 및 오류 규칙을 따른다. 완전한 `DocumentProvider` CRUD는 기능 task가 소유한다.

## 범위

- Confluence Cloud REST API v2를 사용한다. `CONFLUENCE_BASE_URL`은 `https://<site>.atlassian.net` origin, `DOCUMENT_ROOT_ID`는 설정된 Root Page ID다.
- Basic 인증은 `CONFLUENCE_ACCOUNT_EMAIL`과 Atlassian API token인 `CONFLUENCE_AUTH_TOKEN`으로 구성한다. UTF-8 `email:token`을 Base64로 인코딩해 `Authorization: Basic <credentials>` 헤더로 보낸다. 계정 비밀번호 인증은 금지한다.
- Root 직속 child는 `GET /wiki/api/v2/pages/{id}/direct-children`으로 가져온다. `limit`을 설정하고 응답의 `_links.next` 또는 `Link` 헤더가 가리키는 다음 페이지를 끝까지 조회한다. 다음 링크는 opaque 값으로 취급하며 같은 base origin 안에서만 요청하고 반복 링크면 Provider 오류로 안전하게 종료한다. 응답에서 `type=page` 항목의 `id`와 `title`만 구조 탐색에 사용한다.
- 대소문자까지 정확히 일치하는 `Meetings`, `Participants` 직속 Page가 각 하나씩 있을 때 `DocumentStructure(rootId, meetingsPageId, participantsPageId)`를 반환한다. 다른 content type은 무시한다.
- 필수 Page가 없거나 같은 제목의 직속 Page가 중복되면 `DOCUMENT_STRUCTURE_NOT_FOUND`(HTTP 422, 재시도 불가)로 실패한다. 성공한 조회에서 보이지 않는 child도 사용할 수 없으므로 구조 누락으로 처리한다. Adapter는 Page를 생성/이름변경/복구하지 않는다.
- `ProviderHealth`는 공통 제품 Health 계약의 `configured`, `reachable`, `rootAccessible`을 반환한다. `configured`는 HTTPS site origin, account email, token, 숫자형 Confluence Page ID root의 존재와 형식 유효성이다. `reachable`은 안전한 확인 요청이 Confluence에 도달하고 인증됐는지, `rootAccessible`은 Root 직속 child 목록을 성공적으로 조회했는지를 나타낸다. 따라서 401/403 인증 거부는 `reachable=false`, 404 Root 접근 실패는 `rootAccessible=false`다. 설정값은 반환하지 않는다.
- HTTP 401/403/404는 권한/Root 접근 실패 원인을 세분화하지 않고 `DOCUMENT_FAILED` 및 `DOCUMENT_FAILURE`로 안전하게 변환한다. 429/5xx/네트워크 오류도 같은 표준 실패 경계를 사용하며 재시도는 기존 공통 정책을 따른다. Vendor 응답 본문, Authorization 값, 계정 이메일, API token은 API 응답과 로그에 노출하지 않는다.

## 명시적 제외 범위

- Meeting/Participant CRUD, Page 읽기/생성/수정 본문 처리, metadata/property 설계 및 `externalSessionId` 검색은 다루지 않는다.
- Provider 선택과 API-019 Controller/연결 Health 응답 연결은 `TASK-002.04`가 소유한다.
- Confluence Data Center, OAuth, 개인 비밀번호 인증, Confluence SDK 및 Database/Data Source는 사용하지 않는다.
- 실제 Atlassian credential 또는 운영 Workspace를 자동 테스트에 사용하지 않는다.
- 누락된 Root/하위 Page를 자동으로 생성하거나 권한 문제를 구체적인 사용자 메시지로 추측하지 않는다.

## 완료 기준

- Basic 인증 헤더가 계정 이메일과 API token으로 정확히 만들어지고 어느 입력값도 로그/예외/표준 DTO에 남지 않는다.
- REST API v2의 Root 직속 children 전 페이지를 읽으며 `type=page`만 구조 결과에 반영한다.
- 정확히 하나씩 있는 두 Page가 표준 `DocumentStructure`에 매핑된다. 빈 계층, 누락, 중복 및 다른 content type만 있는 경우 표준 구조 오류가 된다.
- 401/403/404 및 전송/제한/서버 오류가 공통 `DOCUMENT_FAILED`/`DOCUMENT_FAILURE`와 `ProviderHealth`로 안전하게 변환된다.
- Adapter 정상, 빈 구조, 권한 실패와 pagination 테스트가 외부 Confluence 연결 없이 통과하며 공통 계약 테스트에도 연결된다.
- 변경 파일, `./gradlew test`, `./gradlew clean build` 결과와 비민감 증거를 `docs/evidence/TASK-002.03.md`에 기록한다.

## 결정 및 전제

- V1 배포 대상은 Confluence Cloud이며 직접 REST API Basic 인증을 사용한다. 인증 계약은 `CONFLUENCE_BASE_URL`, `CONFLUENCE_ACCOUNT_EMAIL`, `CONFLUENCE_AUTH_TOKEN` 세 Backend 설정값으로 확정했다.
- 구조 이름과 위치는 PRD `DEC-013`/`DEC-014`에 따라 Root 직속 `Meetings`, `Participants` Page다.
- 공식 API의 direct-children 응답은 `results`와 `_links.next`를 제공하고 child content type으로 Database, Embed, Folder, Page, Whiteboard를 포함할 수 있다. 권한이 있는 콘텐츠만 반환하므로 조회 성공 결과에 필수 Page가 없는 경우는 Adapter가 이용 가능한 구조 누락으로 처리하며, 목록 조회 자체의 인증/권한 실패는 Provider 실패로 처리한다.
- API 참고: [Confluence Cloud REST API v2 — Get direct children of a page](https://developer.atlassian.com/cloud/confluence/rest/v2/api-group-children/), [Atlassian Basic auth for REST APIs](https://developer.atlassian.com/cloud/confluence/basic-auth-for-rest-apis/). 2026-10-07 확인.

## 연결된 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [테스트 계획](./test.md)

## 인접 작업 계약

- 선행 작업: #6의 `DocumentProvider`, `DocumentStructure`, `ProviderHealth`, 표준 오류 및 계약 테스트를 그대로 사용한다.
- 후속 작업: `TASK-002.04`가 `DOCUMENT_PROVIDER` 선택 결과에 이 Adapter의 Health를 연결한다. CRUD 기능 TASK는 이 Adapter의 공통 Port 기능을 사용한다.

# Confluence Cloud Page 계층 Adapter 구현 계획

## 목표

Cloud Basic 인증으로 Root 직속 Page를 조회하고 표준 구조/Health/error 계약을 제공한다.

- 구현 Issue: [#8](https://github.com/donghyunlee-dev/meeting-automation/issues/8)
- 결정 기록: [#8](https://github.com/donghyunlee-dev/meeting-automation/issues/8)
- 선행 Issue: #6 `TASK-002.01`
- PRD 기준: `PRD-MA-001` v1.6.1, `2026-10-05`
- 공식 자료 확인일: `2026-10-07`

## 관련 원본

- [PRD](../../../product/PRD.md), DEC-013/014, API-019, EXT-003
- [Integration Specification](../../../product/integrations.md), Confluence Adapter/환경 설정
- [Backend Setup](../../../setup/backend-setup.md), Backend secret 경계
- [공통 Port 계약](../TASK-002.01/spec.md)
- [Confluence REST API v2 direct children](https://developer.atlassian.com/cloud/confluence/rest/v2/api-group-children/): current response returns `results` and `_links.next`; child types include Page and non-Page content.
- [Atlassian REST Basic 인증](https://developer.atlassian.com/cloud/confluence/basic-auth-for-rest-apis/): Basic header encodes UTF-8 Atlassian email and API token.

## 변경 대상과 소유 경계

| 경로/컴포넌트 | 책임 |
|---|---|
| `backend/src/main/java/.../document/confluence/ConfluencePageHierarchyAdapter.java` | 구조/Health Port의 Root 구조 구현과 표준 타입 변환 |
| Confluence HTTP client 경계 | Cloud v2 endpoint, Basic Authorization, JSON 및 cursor pagination |
| Confluence response DTO | 응답의 page `id`, `title`, `type`, cursor/next link만 Adapter 내부에서 역직렬화 |
| Backend 설정 | base URL, account email, API token, Root ID 형식 검증 및 주입 |
| Adapter 테스트 | fake HTTP 응답으로 성공, pagination, 빈/중복 구조, auth 및 HTTP 실패 검증 |
| `docs/evidence/TASK-002.03.md` | 비민감 구현/검증 기록 |

구현은 Backend Adapter를 먼저 만든다. 새 UI 또는 공개 Controller가 필요하지 않으며 API-019 Health 노출은 후속 `TASK-002.04`에서 공통 ProviderHealth를 소비한다. 기존 Backend HTTP/JSON 구성을 우선 사용하고 Confluence SDK는 추가하지 않는다.

## 구현 순서와 소유권

1. **BE — 설정과 공통 계약 확인:** #6의 Port/DTO 및 확정된 Cloud Basic 설정을 확인한다. 결과: 세 credentials 설정과 Root ID가 backend runtime 설정 경계에 정의된다.
2. **BE — 실패/정상 테스트 우선 작성:** HTTP fake를 사용해 인증 헤더, direct-child page 매핑, 빈/중복 구조, 권한 오류 및 pagination 테스트를 만든다. 결과: 아직 Adapter 구현이 없어 해당 assertion이 실패한다.
3. **BE — 설정 검증과 인증 client 구현:** 유효한 base URL와 필수 설정을 검증하고 UTF-8 `email:token` Basic 헤더 및 API 요청을 구현한다. 결과: fake server가 헤더를 검증하며 비밀값은 logging interceptor에 나타나지 않는다.
4. **BE — 전체 직속 Page 순회:** `_links.next`/`Link`가 가리키는 다음 페이지가 있는 동안 same-origin direct-children 응답을 가져오고 `type=page` 후보만 수집한다. 다음 URL은 opaque로 전달하며 반복 URL에서 안전하게 종료한다. 결과: 여러 페이지 응답의 구조도 완전하게 탐색한다.
5. **BE — 표준 구조 및 오류/Health 매핑:** 정확한 직속 제목과 중복/누락, Provider 상태를 공통 타입으로 변환한다. 결과: 잘못된 구조는 422 구조 오류, 외부 실패는 502 Provider 오류 계약에 맞는다.
6. **BE — 구조 계약 연결/회귀:** TASK-002.01의 재사용 가능한 structure/Health 계약 slice에 Adapter를 연결하고 단위/회귀 빌드를 실행한다. Meeting/Participant CRUD slice는 해당 기능 task에서 검증한다. 결과: 로컬 fake만으로 구조 계약과 `./gradlew test`, `./gradlew clean build`가 통과하고 evidence가 작성된다.

## 의존성

- `TASK-002.01` (#6): Provider-neutral Port, DTO, error 및 Adapter 계약 테스트.
- `TASK-001.05` (#5): Backend 런타임 설정과 Secret 비노출 경계.
- 필수 Backend 설정: `CONFLUENCE_BASE_URL`, `CONFLUENCE_ACCOUNT_EMAIL`, `CONFLUENCE_AUTH_TOKEN`, `DOCUMENT_ROOT_ID`.
- 자동 테스트는 자격 증명을 fake 값으로 제공하고 실제 외부 사이트에 연결하지 않는다.

## 검증 접근

- 인증: Authorization scheme과 Base64 인코딩된 UTF-8 `email:token` 값이 요청마다 정확하고 로그에서 비밀 문자열이 사라지는지 검사한다.
- 범위: 호출 경로가 `/wiki/api/v2/pages/{rootId}/direct-children`이며 직속 Page만 결과로 사용되는지 확인한다.
- pagination: `_links.next` 또는 `Link` 헤더가 있는 후속 페이지를 모두 읽고 마지막 페이지에서 종료하는지, 반복 링크가 무한 요청을 만들지 않는지 검증한다.
- 구조: 정확한 두 Page, 빈/누락/중복 Page, 비-Page content type을 검증한다.
- 실패: 401/403/404, 429/5xx, timeout의 공통 error/Health 변환과 원문/Secret 비노출을 확인한다.
- 회귀: `./gradlew test`, `./gradlew clean build`; 의존성 변경에 SDK 및 금지 저장 인프라가 없는지 검토한다.

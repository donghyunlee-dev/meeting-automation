# Confluence Cloud Adapter 테스트 계획

자동화 테스트는 Backend의 JUnit/Gradle 테스트 구성과 fake HTTP 경계를 사용한다. 실제 Cloud tenant, 계정 이메일, API token에 연결하지 않는다. 공통 결과 필드 및 오류 코드는 [공통 계약](../TASK-002.01/spec.md)을 따른다.

## 자동화 테스트

| ID | 준비/입력 | 기대 결과 | 증거 |
|---|---|---|---|
| `CONF-AUTH-01` Basic 인증 구성 | 설정에 fake account email과 fake API token을 제공하고 Root children 요청 실행 | `Authorization: Basic <Base64(UTF-8 email:token)>`가 전달된다. 원문 email/token은 예외와 로그에 없다. | client request assertion 및 captured log 검토 |
| `CONF-CONFIG-01` 필수 설정 누락 | base URL, email, token, Root ID 중 하나씩 누락 | `configured=false` 또는 기존 설정 검증 계약에 따른 실패; 어떤 secret 값도 반환하지 않는다. | property validation assertion |
| `CONF-ROOT-01` 정상 구조 | 두 개의 직속 Page 응답에 `Meetings`, `Participants` 제목 제공 | 공통 `DocumentStructure`에 Root와 각각의 page ID가 정확히 매핑된다. | Adapter DTO assertion |
| `CONF-PAGE-02` 다른 content type | 동일 제목의 non-Page child와 두 필수 Page 후보가 섞인 응답 제공 | `type=page`만 선택하며 Database/Folder/Whiteboard/Embed는 구조로 인정하지 않는다. | type filter assertion |
| `CONF-PAGE-03` 빈 구조 | 성공 HTTP 응답에 child 항목 없음 | Page 생성 없이 `DOCUMENT_STRUCTURE_NOT_FOUND`가 발생한다. | 오류 코드 및 생성 호출 0회 assertion |
| `CONF-PAGE-04` 누락 구조 | Meetings만 또는 Participants만 반환 | `DOCUMENT_STRUCTURE_NOT_FOUND`; 누락 Page 생성 호출은 없다. | 오류 코드 및 POST 호출 0회 assertion |
| `CONF-PAGE-05` 중복 제목 | 직속 `Meetings` 또는 `Participants` Page가 둘 이상 반환 | 임의의 Page를 선택하지 않고 `DOCUMENT_STRUCTURE_NOT_FOUND`가 발생한다. | 오류 코드 assertion |
| `CONF-PAGE-06` 여러 cursor 페이지 | 첫 응답에 `cursor` 후속 항목, 다음 응답에 나머지 필수 Page 제공 | 다음 cursor를 사용해 후속 페이지를 읽고 두 Page를 완전하게 반환한다. | 요청 수/parameter 및 결과 assertion |
| `CONF-HEALTH-01` 정상 Health | 인증 성공, Root child 목록 성공 | `configured=true`, `reachable=true`, `rootAccessible=true`; token/email 미포함 | ProviderHealth JSON assertion |
| `CONF-HEALTH-02` 권한 거부 | API가 401 또는 403 반환 | 공통 Provider 실패로 변환되고 Health의 접근 상태가 실패를 나타낸다. 응답 원문/자격증명은 노출되지 않는다. | 예외/Health assertion 및 captured log 검사 |
| `CONF-HTTP-01` Root 미발견/접근 거부 | API가 404 반환 | 세부 원인을 추측하지 않고 `DOCUMENT_FAILED`/`DOCUMENT_FAILURE`로 변환한다. | error code/category assertion |
| `CONF-HTTP-02` 제한 및 서버 오류 | API가 429 또는 5xx 반환 | 원문이 제거된 표준 Provider 실패로 변환되고 기존 retryable 정책이 보존된다. | 오류 타입 및 retryable assertion |
| `CONF-NET-01` 연결 timeout | fake transport timeout 발생 | `reachable=false` 등 표준 Health/Provider 오류로 변환되고 내부 예외 문자열은 외부에 노출되지 않는다. | Health/error assertion |

## 실행 절차

1. `backend/`에서 `./gradlew test --tests '*Confluence*'`를 실행해 Adapter 전용 테스트를 확인한다. 테스트 클래스 이름이 정해지지 않았으면 `./gradlew test`를 사용한다.
2. 공통 DocumentProvider 계약 suite와 전체 Backend 테스트를 `./gradlew test`로 실행한다.
3. `./gradlew clean build`를 실행한다.
4. 테스트 로그 및 캡처된 오류에서 fake token/email, Authorization header, provider raw body가 나타나지 않는지 검사한다.
5. `docs/evidence/TASK-002.03.md`에 명령과 결과, 비밀 없는 검증 증거를 기록한다.

## 수동 QA

- 테스트 Confluence Cloud 공간에서만 secret manager에 `CONFLUENCE_BASE_URL`, `CONFLUENCE_ACCOUNT_EMAIL`, `CONFLUENCE_AUTH_TOKEN`, `DOCUMENT_ROOT_ID`를 설정한다.
- 권한이 있는 root에서 Health가 구조 접근 가능으로 보고되는지, 두 필수 Page가 빠진 root에서는 구조 오류가 발생하는지 확인한다.
- 수동 확인 후 실제 token/email, tenant 식별 정보, 응답 원문을 로그나 evidence에 복사하지 않는다.

## 릴리스 확인

- Render Backend 설정에 Cloud site origin, 서비스 계정 email, API token을 각각 등록한다. Frontend environment에는 입력하지 않는다.
- 배포 환경에서 성공/권한 실패 상태를 확인하고 오류 응답/일반 로그가 자격 증명 또는 Vendor body를 노출하지 않는지 점검한다.
- 구현 작업 완료 후 비민감 검증 결과를 `docs/evidence/TASK-002.03.md`에서 확인한다.

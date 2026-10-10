# Provider 선택 및 통합 Health 테스트 계획

> 📌 v1.9.0 변경 안내: 이 문서는 완료된 TASK-002.04의 당시 구현/검증 이력이다. Backend env로 선택·credential을 고정하는 계약과 초기 구조 생성 제외 범위는 새 TASK-022에서 변경한다. 현재 구현 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)와 TASK-022 패키지다. 일반 Health/탐색은 계속 읽기 전용이며 명시적 초기화만 구조를 생성한다. 기존 DONE/검증 증거는 보존한다.

자동화 테스트는 JUnit/Gradle과 fake DocumentProvider/HTTP 경계를 사용한다. 실제 Notion/Confluence credentials나 network는 사용하지 않는다. UI 연결 안내는 SCR-011의 수동 확인이며 현재 구현 작업 범위가 아니다.

## 자동화 테스트

| ID | 준비/입력 | 기대 결과 | 증거 |
|---|---|---|---|
| `DOC-SELECT-01` Provider 미설정 | `DOCUMENT_PROVIDER` unset 또는 blank | 자동 fallback 없이 API-001의 `document.provider=null`, `configured=false`; API-019 Document 4개 status는 모두 false | Controller JSON assertion |
| `DOC-SELECT-02` Notion 선택 | `DOCUMENT_PROVIDER=NOTION`, valid `NOTION_TOKEN`, `DOCUMENT_ROOT_ID` | 선택 Provider가 NOTION, configured true, 다른 credentials는 선택 결과에 영향 없음 | Resolver/configuration assertion |
| `DOC-SELECT-03` Confluence 선택 | `DOCUMENT_PROVIDER=CONFLUENCE`, valid Cloud 설정 3종과 Root ID | 선택 Provider가 CONFLUENCE, configured true, Confluence가 NOTION 설정으로 fallback하지 않음 | Resolver/configuration assertion |
| `DOC-SELECT-04` Unknown selector | `DOCUMENT_PROVIDER`에 지원하지 않는 non-empty 값 제공 | Backend 설정 검증 오류가 발생하고 임의 Provider는 선택되지 않음 | Configuration binding assertion |
| `DOC-SELECT-05` 활성 자격 미설정/형식 오류 | valid provider enum과 필수 credential 누락 또는 base URL/email/token 형식 오류 제공 | provider ID는 유지, configured/reachable/rootAccessible은 false | App config 및 health JSON assertion |
| `DOC-API-001` Secret 격리 | valid 활성 Provider 설정 및 API-001 요청 | 공개 document object는 `{provider,configured}`만 반환; token, account email, root ID가 없음 | serialized JSON field/value assertion |
| `DOC-HEALTH-01` 공통 응답 shape | API-019 요청, contributor 일부 미등록 | HTTP 200, `document`, `email`, `notification`, `ai` key를 모두 포함. 미등록 영역은 configured/reachable=false | API response contract assertion |
| `DOC-HEALTH-02` 활성 Provider 정상 | fake Adapter 인증 성공 및 두 필수 Page 구조 존재 | document configured/reachable/rootAccessible은 true, provider enum은 선택값 | contributor status assertion |
| `DOC-HEALTH-03` 연결 실패 | fake Adapter timeout 또는 인증 실패 | document reachable=false, rootAccessible=false, API-019 HTTP 200; provider body/Secret 미포함 | HTTP response and captured log assertion |
| `DOC-HEALTH-04` Root/구조 실패 | fake Adapter root 404 또는 필수 Page 누락/중복 반환 | rootAccessible=false; 공통 구조 오류는 상태로 변환되고 다른 integration status는 유지 | status assertion |
| `DOC-HEALTH-05` 한 contributor 예외 | Document contributor가 안전하지 않은 예외 발생, 다른 영역 기본값 사용 | API-019 key 전체가 존재하며 다른 영역 상태를 유지하고 raw exception을 감춘다 | full JSON and log assertion |
| `DOC-HEALTH-06` 읽기 전용 검사 | API-019 요청 수행 | fake transport 요청은 인증/조회 GET만 포함하고 create/update 호출은 0회 | captured method assertion |
| `DOC-HEALTH-07` 자격 증명 비노출 | API-001/API-019 호출 후 응답 및 로그 수집 | token, Basic header, Root ID, Vendor error body가 JSON/log에 없다 | JSON serialization and captured log scan |

## 실행 절차

1. `backend/`에서 provider selection과 integration health의 JUnit suite를 실행한다. 클래스명이 아직 없으면 `./gradlew test`를 사용한다.
2. 전체 Backend 테스트를 `./gradlew test`로 실행한다.
3. `./gradlew clean build`를 실행한다.
4. 응답 JSON에서 필드 nullability/필수 key를 확인하고 logs/exceptions에서 fake secrets 및 Vendor raw body를 검색한다.
5. 결과와 비민감 증거를 `docs/evidence/TASK-002.04.md`에 기록한다.

## 수동 QA

- Backend를 `DOCUMENT_PROVIDER` 없이 실행하고 app config와 integration health가 미설정 상태를 반환하는지 확인한다.
- Settings 화면에서 `provider:null` 또는 `configured=false` 상태일 때 문서 연결 안내와 관리자 요청 다음 행동이 보이며 Secret 입력값은 노출되지 않는지 확인한다. 이 UI 구현은 후속 `TASK-015.01`에서 수행한다.
- NOTION/CONFLUENCE 각각으로 선택한 테스트 환경에서 상태가 해당 Provider만 반영하는지 확인한다. 운영 Secret은 evidence나 캡처에 남기지 않는다.

## 릴리스 확인

- Runtime `DOCUMENT_PROVIDER` 선택값과 선택된 Provider credentials를 검증한다. 누락은 미설정 상태, 미지원 non-empty enum은 설정 오류로 구분한다.
- API-019의 네 영역이 일부 Provider 구현 유무와 관계없이 모두 응답되는지 확인한다.
- 자격증명 오류/Root 오류가 사용자 응답과 일반 로그에 안전하게 요약됐는지 확인한다.

# Document Provider 선택과 통합 Health 응답

> 📌 v1.9.0 변경 안내: 이 문서는 완료된 TASK-002.04의 당시 구현/검증 이력이다. Backend env로 선택·credential을 고정하는 계약과 초기 구조 생성 제외 범위는 새 TASK-022에서 변경한다. 현재 구현 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)와 TASK-022 패키지다. 일반 Health/탐색은 계속 읽기 전용이며 명시적 초기화만 구조를 생성한다. 기존 DONE/검증 증거는 보존한다.

## 작업 식별 정보

- 작업: `TASK-002.04`
- 상위 작업 묶음: `TASK-002 Document Provider Foundation`
- 단계: `Phase 2 — Document / Participants`
- PRD 기준: `PRD-MA-001`, 버전 `1.6.2`, 기준일 `2026-10-05`
- 관련 요구사항: `FR-025`, `FR-026`, `API-001`, `API-019`
- 영역: `BE`
- 선행 작업: `TASK-002.02` Notion Adapter (#7), `TASK-002.03` Confluence Adapter (#8)
- 결정 기록 및 구현 Issue: [GitHub Issue #9](https://github.com/donghyunlee-dev/meeting-automation/issues/9)

## 결과

Backend는 `DOCUMENT_PROVIDER` 설정으로 Notion 또는 Confluence Adapter를 선택하고, API-001과 API-019에 비밀을 제외한 Document 설정/상태를 제공한다. API-019는 통합 Health의 공통 응답 구조를 먼저 고정해 네 연동 영역을 항상 반환하며, 후속 Provider 설계는 같은 응답에 상태 contributor를 추가한다.

## 범위

- Provider 선택은 Backend 설정에서만 이뤄진다. 허용값은 `NOTION`, `CONFLUENCE`다. 설정이 없거나 빈 값이면 자동 fallback하지 않고 선택 결과를 `null`로 표현한다. 공백이 아닌 미지원 값은 설정 오류로 처리한다.
- 선택 Provider가 없으면 API-001의 `document.provider`는 `null`, `document.configured`는 `false`다. API-019의 Document 상태는 `provider:null`, `configured:false`, `reachable:false`, `rootAccessible:false`다.
- 지원 Provider가 선택되어도 해당 Adapter의 필수 credentials 또는 `DOCUMENT_ROOT_ID`가 없거나 형식이 잘못되면 Provider ID를 유지하고 `configured:false`, `reachable:false`, `rootAccessible:false`를 반환한다. 미선택 시 다른 Provider로 자동 전환하지 않는다.
- `configured`는 선택된 Provider의 필수 설정/credentials가 존재하고 형식이 유효함을 뜻한다. `reachable`은 인증된 Health 요청이 Provider에 응답받았음을 뜻한다. `rootAccessible`은 설정 Root와 필수 직속 `Meetings`, `Participants` Page 구조를 모두 탐색할 수 있음을 뜻한다.
- `GET /api/v1/app-config`는 기존 public app config의 Document 부분을 활성 Provider ID와 `configured`로 채운다. Secret, credentials, Root ID는 반환하지 않는다.
- `GET /api/v1/integrations/health`는 HTTP 200에서 `document`, `email`, `notification`, `ai` 네 객체를 항상 반환한다. 각 객체의 Health contributor가 없는 영역은 `configured:false`, `reachable:false` 기본값으로 둔다. `notification.provider`는 기존 provider 설정을 반영한다. 후속 Email/Notification/AI 설계는 공통 assembler의 해당 contributor만 추가/교체한다.
- Document Health contributor는 활성 `DocumentStructureProvider`를 통해 연결과 구조를 확인한다. 외부 오류, 인증/Secret, Provider 응답 본문을 응답에 넣지 않는다. 구조 누락/중복과 외부 연결 실패는 API 오류 대신 상태 boolean으로 나타내고 다른 integration 상태를 중단시키지 않는다. CRUD를 갖춘 `DocumentProvider`는 같은 구조 Port를 확장한다.
- Health 요청은 읽기 전용이다. Page/문서/메시지/메일을 생성하거나 수정하지 않는다.
- Frontend 동작은 [SCR-011 Settings](../../../product/ui-design.md#scr-011-settings)을 따른다. `document.provider=null` 또는 `configured=false`이면 문서 저장 상태에 연결 필요를 표시하고 관리자에게 설정을 요청하는 다음 행동을 안내한다. 실제 Secret 입력 화면은 이 Backend 작업 범위가 아니다.

## 명시적 제외 범위

- Frontend component 및 `TASK-015.01` Settings API 연결 구현은 하지 않는다. 해당 작업은 이 응답을 화면에 연결한다.
- Notion/Confluence Adapter의 개별 API 호출 구현과 문서 CRUD는 각각 TASK-002.02/.03에 속한다.
- Email/Notification/AI provider별 Health probe는 구현하지 않는다. 공통 객체와 미등록 contributor의 false 기본값만 제공하며 각 Provider 설계가 나중에 해당 영역을 추가한다.
- provider credentials를 설정하는 공개 API, 화면 입력, Provider 자동 탐지/전환, Secret 응답은 추가하지 않는다.
- Health probe 결과의 지속 저장, 배치 polling, 별도 DB/cache, 새로운 외부 오류 코드는 도입하지 않는다.

## 완료 기준

- `DOCUMENT_PROVIDER` 누락/빈 값은 자동 Provider 선택 없이 API-001/019에 Document provider `null`과 미설정 상태를 반환한다. 지원하지 않는 non-empty enum은 설정 오류가 된다.
- `NOTION` 및 `CONFLUENCE` 선택 시 해당 Adapter만 활성화되고 비활성 Provider credentials로 fallback하지 않는다. 잘못되거나 누락된 활성 설정은 `configured=false`로 노출된다.
- API-019 응답은 항상 네 연동 객체를 포함하며 미등록 Health contributor는 `configured=false`, `reachable=false`다. Document 구조 상태는 해당 두 child Page의 탐색 결과를 반영한다.
- 정상·연결 실패·미설정·구조 누락/중복에서 endpoint는 안전한 상태와 표준 공개 JSON을 반환한다. 민감 credentials 및 Vendor 오류 본문은 노출되지 않는다.
- Health 조회는 어떤 외부 쓰기 요청도 하지 않으며 한 contributor 오류가 다른 상태 출력이나 HTTP 200 응답을 막지 않는다.
- `./gradlew test`, `./gradlew clean build`가 통과하고 Controller/Provider Health 테스트와 비민감 증거를 `docs/evidence/TASK-002.04.md`에 기록한다.

## 결정 및 전제

- 사용자 결정: `DOCUMENT_PROVIDER` 미설정 상태는 `provider:null`로 응답하며 UI가 적절한 Settings 화면에서 연결 안내를 한다.
- 사용자 결정: API-019의 전체 공통 구조를 먼저 구성하고, 각 Provider 설계에서 필요한 Health contributor를 추가한다.
- Integration Health에서 document, email, notification, ai 객체의 key는 항상 유지한다. 미등록 영역은 false 상태로 시작하고 후속 구현이 공통 계약을 바꾸지 않고 보충한다.
- `DOCUMENT_PROVIDER` 빈 값과 미지원 enum은 다르게 처리한다. 빈 값은 지원 가능한 앱 미설정 상태이고, 오탈자/지원하지 않는 값은 operator 설정 오류다.
- API 계약은 [API-001](../../../product/api-spec.md)과 [API-019](../../../product/api-spec.md#api-019-integration-health), health 의미는 [Integration Specification](../../../product/integrations.md#health-확인-의미)을 따른다.

## 연결된 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [테스트 계획](./test.md)

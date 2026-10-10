# Provider 선택 및 통합 Health 구현 계획

> 📌 v1.9.0 변경 안내: 이 문서는 완료된 TASK-002.04의 당시 구현/검증 이력이다. Backend env로 선택·credential을 고정하는 계약과 초기 구조 생성 제외 범위는 새 TASK-022에서 변경한다. 현재 구현 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)와 TASK-022 패키지다. 일반 Health/탐색은 계속 읽기 전용이며 명시적 초기화만 구조를 생성한다. 기존 DONE/검증 증거는 보존한다.

## 목표

Provider selector와 공통 통합 Health 응답을 구현해 API consumers가 선택된 Document 상태 또는 미설정 상태를 안전하게 확인하도록 한다.

- Issue 및 결정: [#9](https://github.com/donghyunlee-dev/meeting-automation/issues/9)
- 선행 작업: #7 Notion Adapter, #8 Confluence Adapter
- PRD 기준: `PRD-MA-001` v1.6.2, `2026-10-05`

## 관련 원본

- [PRD](../../../product/PRD.md), FR-025/026, API-001/019, TASK-002.04
- [API Specification](../../../product/api-spec.md), app config 및 integration health
- [Integration Specification](../../../product/integrations.md), Health 의미 및 Provider 설정
- [Settings UI Design](../../../product/ui-design.md#scr-011-settings), 미설정 연결 안내 consumer
- [Notion 계약](../TASK-002.02/spec.md), [Confluence 계약](../TASK-002.03/spec.md)
- [공통 DocumentProvider/DocumentStructureProvider 계약](../TASK-002.01/spec.md)

## 변경 대상과 소유 경계

| 경로/컴포넌트 | 책임 |
|---|---|
| Backend provider selection properties/resolver | `DOCUMENT_PROVIDER`를 optional enum으로 해석하고 지원 Provider Adapter를 반환 |
| `AppConfig` service/DTO | API-001 document provider와 `configured`를 public DTO로 조립 |
| `IntegrationHealthAggregator` | 네 integration health 객체를 항상 생성하고 contributor 결과를 공통 응답으로 조립 |
| `IntegrationHealthContributor` | Document/Email/Notification/AI 각 contributor가 provider-neutral 상태를 전달 |
| Document Health contributor | 선택된 `DocumentStructureProvider`의 설정, 연결, Root+필수 child 구조 상태를 결합 |
| App Config 및 Health Controller | API-001/019 JSON mapping과 HTTP 200 상태 응답 제공 |
| Controller/Service 테스트 | selector null/유효/잘못된 설정, all-keys 응답, 오류·미등록 contributor 검증 |
| `docs/evidence/TASK-002.04.md` | 명령 및 비민감 evidence |

API 응답 shape가 FE의 연결 안내 조건이므로 API contract와 status DTO를 먼저 테스트/고정하고, 그 뒤 Backend resolver와 aggregator를 구현한다. UI 변경은 독립 구현이 아니므로 이 작업에서 하지 않는다. 후속 task는 공통 contributor 계약에 provider별 Health를 추가한다.

## 구현 순서와 소유권

1. **BE — API DTO/Controller contract 테스트:** API-001의 `document.provider` nullable 및 API-019의 네 객체, 기본 false 상태를 fixture로 고정한다. 결과: 미선택/미등록 응답 테스트가 구현 전 실패한다.
2. **BE — Provider 선택 resolver:** `DOCUMENT_PROVIDER`가 없으면 Optional empty, 유효 enum이면 같은 Provider Adapter, 미지원 non-empty enum이면 설정 오류를 제공한다. 결과: fallback 선택은 발생하지 않는다.
3. **BE — configured 및 App Config mapping:** 활성 Provider별 required settings로 configured를 계산하고 API-001 public DTO에 provider/configured만 노출한다. 결과: credentials/Root ID는 JSON에 없다.
4. **BE — Health contributor와 공통 aggregator:** Document 상태와 email/notification/ai 기본 false 상태를 합쳐 네 영역을 항상 반환한다. 결과: 한 contributor가 없거나 실패해도 다른 상태와 HTTP 200 shape가 유지된다.
5. **BE — Document health probe:** 선택 Adapter의 `validateConnection()`과 구조 발견 결과로 `reachable`, `rootAccessible`을 산출한다. 결과: Root 또는 필수 child 누락/중복은 false이고 쓰기 부작용이 없다. 선택 대상은 우선 `DocumentStructureProvider`이며 CRUD 기능 완성 후 이를 확장한 `DocumentProvider`를 그대로 주입할 수 있다.
6. **BE — 예외 경계/회귀/evidence:** Provider 오류 원문을 제거하고 JUnit/Gradle 및 공통 계약 검증과 변경 검토를 수행한다. 결과: `./gradlew test`, `./gradlew clean build` 통과와 evidence가 남는다.

## 의존성

- `TASK-002.02` (#7): Notion structure/Health Adapter.
- `TASK-002.03` (#8): Confluence structure/Health Adapter 및 Cloud Basic 설정.
- API-001/019와 SCR-011 응답 consumer는 PRD에 고정된 shape를 따른다.
- 동적 contributor registry는 Backend DI를 사용한다. 후속 Provider 작업은 같은 interface와 표준 health DTO를 구현하고 응답 key를 추가하거나 변경하지 않는다.

## 검증 접근

- Selector matrix: 미설정/빈 값, `NOTION`, `CONFLUENCE`, 미지원 enum.
- Public response: API-001에 provider 및 configured만 포함하고 Secret/Root ID를 제외한다.
- Health response: 각 요청에서 네 key가 모두 존재하며 기본 contributor 상태를 false로 만든다.
- Document health: 인증 정상, 설정 누락, network/auth 오류, root 접근 실패, 구조 누락/중복을 테스트한다.
- Isolation: contributor 중 하나의 런타임 오류가 API-019 응답 나머지 객체나 HTTP status를 깨뜨리지 않는다.
- Read-only: 모든 fake HTTP 요청이 GET이고 Page create/update 요청이 없다.
- Regression: `./gradlew test`, `./gradlew clean build`; 이 Task에서 영속 저장 계층이나 Vendor SDK가 추가되지 않았는지 확인한다.

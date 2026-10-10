# DocumentProvider 공통 계약 및 계약 테스트 구현 계획

> 📌 v1.9.0 변경 안내: 이 문서는 완료된 TASK-002.01의 당시 구현/검증 이력이다. Backend env로 선택·credential을 고정하는 계약과 초기 구조 생성 제외 범위는 새 TASK-022에서 변경한다. 현재 구현 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)와 TASK-022 패키지다. 일반 Health/탐색은 계속 읽기 전용이며 명시적 초기화만 구조를 생성한다. 기존 DONE/검증 증거는 보존한다.

## 목표

Provider 선택이나 Vendor SDK를 참조하지 않는 Backend Port와 표준 DTO/error 계약을 제공하고 후속 Adapter가 공통 테스트를 재사용하도록 한다.

- GitHub Issue: [#6](https://github.com/donghyunlee-dev/meeting-automation/issues/6)
- 선행 Issue: #5 완료 후 구현 착수
- PRD 기준: `PRD-MA-001` v1.6.0, `2026-10-05`

## 관련 원본

- [PRD](../../../product/PRD.md), §8.2~8.3, §9.1, §10, §23.1, DEC-006/013/014, TASK-002.01
- [Integration Specification](../../../product/integrations.md), EXT-003 Port/Adapter, 구조 검사, metadata 규칙
- [Data Specification](../../../product/data-spec.md), Meeting metadata, Participant, 문서 표준 모델
- [API Specification](../../../product/api-spec.md), API-003~005, API-017~019, 문서 오류 코드
- 선행 계약: [TASK-001.05](../../phase-01-foundation/TASK-001.05/spec.md)

## 변경 대상과 소유 경계

| 경로/컴포넌트 | 책임 |
|---|---|
| `backend/src/main/java/.../document/DocumentProvider.java` | EXT-003 공통 Port 메서드 |
| 표준 document DTO | `ProviderHealth`, `DocumentStructure`, `MeetingSummary`, `MeetingDocument`, `MeetingDocumentRef`, `Participant` 및 생성/수정 명령 |
| Provider 오류 타입/매퍼 | 구조 누락과 일반 Provider 실패를 기존 코드/분류로 변환하고 원문 격리 |
| `backend/src/test/java/.../document/DocumentStructureProviderContractTest.java` | 구조/Health slice의 정상·오류를 검증하는 재사용 JUnit 5 계약 모음 |
| `backend/src/test/java/.../document/DocumentProviderContractTest.java` | 완전한 Port의 CRUD 및 구조 동작을 검증하는 재사용 JUnit 5 계약 모음 |
| test fixture | 결정적인 fake provider와 계약 위반 fixture. 네트워크/SKD 호출 없음 |
| `docs/evidence/TASK-002.01.md` | 테스트/빌드와 비민감 계약 검증 결과 |

실제 Java package는 `backend/`의 기존 group/package 관례를 따른다. Notion/Confluence 관련 의존성이나 provider-specific DTO는 이 작업에 두지 않는다.

## 구현 순서와 소유권

1. **BE — 선행 기반 확인:** #5 완료와 Backend package, Java 25/JUnit 5 테스트 실행 기반을 확인한다. 결과: 소스/test 경로와 실행 명령을 고정한다.
2. **BE — 실패 테스트 작성:** 계약 모음과 test fixture에서 올바른 Root/child mapping, 읽기·생성 반환값, 구조 누락 및 Provider 실패 매핑을 먼저 단언한다. 일부러 불완전한 fixture는 계약 assertion에서 실패한다. 결과: 실패 원인이 compile/config 오류가 아닌 계약 assertion임을 확인한다.
3. **BE — 최소 Port/DTO 구현:** 표준 필드와 EXT-003 signature를 구현한다. 결과: Vendor 타입이 없는 컴파일 가능한 interface와 DTO가 만들어진다.
4. **BE — 오류 규칙 구현:** `DOCUMENT_STRUCTURE_NOT_FOUND`, `DOCUMENT_FAILED`, `DOCUMENT_FAILURE`의 역할을 고정하고 안전한 메시지/예외 경계를 둔다. 결과: Provider 원문/Secret이 노출되지 않는다.
5. **BE — fixture 및 테스트 통과:** 계약 slice들을 결정적인 fake provider에 적용하고 정상/경계 사례를 통과시킨다. 결과: 계층 Adapter는 구조 모음만 확장하고 완전한 Adapter는 전체 모음을 확장할 수 있다.
6. **BE — 회귀/증거:** `./gradlew test`, `./gradlew clean build`와 dependency diff를 확인해 Evidence를 남긴다.

이 작업은 서버 내부 Port/DTO와 후속 Adapter에 재사용될 테스트 경계를 먼저 고정한다. UI 작업은 없고 공개 API Controller도 만들지 않으므로 Backend 계약과 오류 모델을 먼저 확정한다.

## 의존성

- `TASK-001.05` (#5): Backend 독립 빌드, Java/Gradle 실행 기반.
- JUnit 5는 기존 Spring Boot Test 기반을 사용한다. 계약 테스트를 위해 새 mocking framework나 Provider SDK를 추가하지 않는다.
- `TASK-002.02`와 `TASK-002.03`은 이 Port와 공유 계약 테스트의 후속 소비자다.
- `TASK-002.04`는 Provider 선택과 API health를 연결하되 여기 정의된 `ProviderHealth`를 그대로 사용한다.

## 검증 접근

- `backend/`에서 대상 계약 테스트 후 `./gradlew test` 실행.
- `./gradlew clean build`로 Java compile 및 전체 regression 확인.
- test fixture에서 정상 Root 구조, 누락 child, 읽기/생성 결과, Provider 오류 정규화를 확인한다.
- 의존성 트리/변경 목록에 Notion/Confluence SDK, DB/JPA/Redis가 추가되지 않았는지 확인한다.
- 테스트/로그에 Secret, Page raw JSON, Provider 원문 오류가 출력되지 않는지 확인하고 `docs/evidence/TASK-002.01.md`에 결과를 기록한다.

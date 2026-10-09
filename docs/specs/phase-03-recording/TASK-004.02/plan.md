# 구현 계획

## 의존성

- `TASK-001.04` (#4): 공통 오류 envelope와 예외 상세 마스킹
- PRD v1.7.0 `FR-002`, `API-001`, `API-002`; API Specification; Backend Setup static resource 위치
- Issue [#15](https://github.com/donghyunlee-dev/meeting-automation/issues/15)

## 변경 대상

Backend configuration properties/value objects, App Config Controller/Service/DTO, Template static resource reader/metadata, `src/main/resources/templates/default.md` 및 `project.md` 검사, Controller/service tests를 구현한다. 현재 Backend 모듈 scaffold 상태를 먼저 확인해 실제 경로를 확정한다.

## 소유권과 계약

- App Config는 설정 경계에서 public DTO로 변환하고 credentials property를 DTO에 포함하지 않는다.
- 미선택 Provider는 null을 보존한다. Frontend가 이 값으로 Settings 연결 안내를 구성하며, 이 Task는 화면 동작을 구현하지 않는다.
- Template 목록은 정적 파일만 읽고 Document Provider를 호출하지 않는다.
- 오류는 TASK-001.04 공통 envelope를 사용한다. 미지원 provider enum과 필수 Template 누락은 안전한 `INTERNAL_ERROR` 500이다.

## 구현 순서

1. 설정/Controller 테스트와 Template resource tests를 작성해 정상, 미설정, missing resource 계약을 실패 우선으로 고정한다.
2. 필요한 Backend 설정 properties를 바인딩하고 public response DTO를 매핑한다.
3. 환경값 테스트로 null provider, 정상/미설정 credentials, 잘못된 enum을 구현한다.
4. static Template catalog가 정확한 두 리소스를 읽고 metadata를 구성하도록 구현한다.
5. 공통 예외 처리와 Secret/stack trace 비노출을 확인한다.
6. `./gradlew test`, `./gradlew clean build`와 문서 응답 비교를 수행한다.

The repository's wrapper-validation workflow verifies the Gradle Wrapper JAR but does not execute backend tests. A dedicated backend validation workflow runs these same test and build commands on pull requests that change backend files. If the local executor cannot start Gradle, use the exact-head GitHub Actions result as the independent test/build evidence; do not report an unstarted local command as passed.

API가 `SCR-002` 입력 선택과 Settings 연결 안내의 안정된 공통 응답을 제공하므로 Backend API/DTO를 먼저 구현한다.

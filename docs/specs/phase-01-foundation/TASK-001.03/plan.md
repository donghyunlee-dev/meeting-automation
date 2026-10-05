# Backend 도구 체계 초기화 계획

## 목표

`backend/`에서 PRD 기준의 Spring Boot 애플리케이션을 재현 설치·테스트·빌드할 수 있는 최소 실행 기반을 마련한다.

- GitHub Issue: [#3](https://github.com/donghyunlee-dev/meeting-automation/issues/3)
- 선행 작업: `TASK-001.01`, #1 완료 후 구현 착수
- 후속 작업: `TASK-001.04`, `TASK-001.05`

## 관련 원본

- [PRD](../../../product/PRD.md), 3.1, 3.3, 3.5, 23.1절 및 `TASK-001.03`
- [Backend 설정](../../../setup/backend-setup.md), JDK, Gradle Wrapper, IDE 및 실행 기준
- [Architecture](../../../product/architecture.md), Backend 구성과 의존성 방향

## 소유 경계

| 경로 | 책임 |
|---|---|
| `backend/settings.gradle` | Gradle 프로젝트 식별자와 저장소 설정 |
| `backend/build.gradle` | Spring Boot plugin, Java 25 toolchain, 의존성, 테스트 설정 |
| `backend/gradle/wrapper/gradle-wrapper.properties` 및 Wrapper 실행 파일 | 고정된 Gradle 9.x 배포본과 재현 실행 |
| `backend/src/main/java/.../MeetingAutomationApplication.java` | Spring Boot 진입점 |
| `backend/src/main/resources/application.yml` | 로컬 기본 설정만 관리하고 Secret 값은 포함하지 않음 |
| `backend/src/test/java/.../MeetingAutomationApplicationTests.java` | Spring application context loading 검증 |

패키지 경로는 `backend`의 group/application 식별자와 일치시킨다. `frontend/`, 루트 공통 문서 및 `TASK-001.04`의 오류/Health 계약 파일은 수정하지 않는다.

## 구현 순서와 소유권

1. **BE — 선행 및 런타임 확인:** #1 완료 여부, JDK 25, Windows/Unix Wrapper 실행 조건을 확인한다. 결과: 저장소 루트와 Java 런타임 버전이 기록된다.
2. **BE — 테스트 실행 기반:** Gradle 프로젝트 선언, Wrapper, 테스트 의존성 및 테스트 소스 디렉터리를 준비한다. 애플리케이션 설정 클래스가 없는 상태에서 `@SpringBootTest`를 실행한다. 결과: 테스트가 수집되며 Spring Boot 설정 클래스를 찾지 못해 context 초기화 단계에서 실패한다. 빌드 설정/다운로드/컴파일 오류는 유효한 실패 증거가 아니다.
3. **BE — 최소 구현:** Java 25 toolchain, 호환 Spring Boot 4.1.x plugin, Web/Validation/Actuator 의존성 및 `@SpringBootApplication` 진입점을 추가한다. 결과: 동일 context loading 테스트가 통과한다.
4. **BE — 회귀 확인:** `./gradlew clean build`와 Wrapper 버전 검사를 실행한다. 결과: JDK 25, Gradle 9.x 및 context test/build 성공이 확인된다.
5. **BE — 증거:** 명령, 런타임 버전, Wrapper 버전, 테스트/build 결과 및 종속성 경계 점검을 `docs/evidence/TASK-001.03.md`에 기록한다.

이 작업은 Backend toolchain을 먼저 제공한다. 제품 UI 또는 API 동작이 아직 필요하지 않은 기반 작업이며, 공통 오류 응답/Health contract는 이 결과에 의존하는 `TASK-001.04`에서 테스트 우선으로 추가한다.

## 의존성과 호환성

- `TASK-001.01`의 저장소 골격과 `.gitignore`가 선행되어야 한다.
- Java 25로 실행할 Gradle Wrapper는 9.x이며 최소 9.1이다.
- Spring Boot는 4.1.x patch 라인 중 Gradle/JDK와 호환되는 버전을 고정한다.
- 외부 HTTP client는 이 작업에서 선택하지 않는다. Provider adapter 작업에서 아키텍처 결정에 따라 공통 기준을 적용한다.
- 데이터베이스 또는 persistence starter를 추가하지 않는다.

## 검증 접근

Wrapper 재현성은 `./gradlew --version`과 `./gradlew clean build`로 확인한다. Windows PowerShell에서는 `.\gradlew.bat --version`, `.\gradlew.bat clean build`를 사용한다. `./gradlew test`에서 `@SpringBootTest` context loading 검증을 확인한다. 산출물·cache ignore는 `git check-ignore`로 검사한다.

오류 JSON, 오류 코드, `traceId`, Actuator endpoint 공개 범위는 이 작업에서 정의하지 않고 `TASK-001.04`에 연결한다.

# Backend 도구 체계 초기화

## 작업 식별 정보

- 작업: `TASK-001.03`
- 상위 작업 묶음: `TASK-001 Monorepo / Technology Baseline`
- 단계: `Phase 1 — 프로젝트 기반 / 공통 구조`
- PRD 기준: `PRD-MA-001`, 버전 `1.3.0`, 기준일 `2026-10-05`
- 관련 요구사항: `DEC-004`, `DEC-005`, `NFR-009`
- 영역: `BE`
- 선행 작업: `TASK-001.01` 저장소 기본 구조 및 ignore 구성 (#1)
- 후속 작업: `TASK-001.04` 공통 오류 응답 및 Health 기반, `TASK-001.05` FE/BE 독립 빌드 및 환경 샘플
- GitHub Issue: [#3](https://github.com/donghyunlee-dev/meeting-automation/issues/3)

## 결과

`backend/`에서 Java 25, Spring Boot 4.1.x, Gradle 9.x 기반 Spring Boot 애플리케이션을 독립적으로 빌드하고 실행할 수 있는 최소 도구 체계를 만든다. Gradle Wrapper, 애플리케이션 진입점, Spring context loading 테스트를 제공한다.

## 범위

- Gradle Groovy DSL 기반의 `settings.gradle`, `build.gradle` 및 Gradle Wrapper를 구성한다.
- Java toolchain과 컴파일 기준을 Java 25로 고정한다.
- Spring Boot 4.1.x 호환 patch와 JDK 25 실행을 지원하는 Gradle 9.x Wrapper 버전을 사용한다.
- PRD가 정한 Spring Web, Validation, Actuator와 Spring Boot Test/JUnit 5 의존성을 구성한다.
- `@SpringBootApplication` 진입점과 `@SpringBootTest` context loading 테스트를 추가한다.
- `./gradlew build`로 컴파일, 테스트, 검증 가능한 산출물 생성을 확인한다. Windows 개발 환경에서는 동등한 `gradlew.bat` 명령을 제공한다.
- `backend/` 내부에서 독립 실행할 수 있도록 기본 application 설정을 둔다. Health의 응답 계약과 외부 노출 정책은 후속 `TASK-001.04`가 소유한다.

## 명시적 제외 범위

- 공통 오류 응답, 예외 매핑, `/api/v1/health` 또는 운영용 Health 계약을 구현하지 않는다. 이는 `TASK-001.04` 범위다.
- 업무 API, 도메인 모델, Provider Adapter, 인증, CORS 정책, 배포 설정을 구현하지 않는다.
- DB, JPA, Redis, Queue, Batch 의존성을 추가하지 않는다.
- 실제 Provider Secret, `.env` 값, 외부 연동 설정을 추가하지 않는다.
- `TASK-001.05`가 담당하는 FE/BE 통합 독립 빌드 및 환경 샘플 검증을 선행 구현하지 않는다.

## 완료 기준

- `backend/gradlew`와 `backend/gradlew.bat`가 Wrapper 설정에 따라 동일한 Gradle 9.x 배포본을 실행한다. JDK 25 실행 호환성을 충족한다.
- Gradle Java toolchain이 25이며 Spring Boot plugin은 4.1.x patch 라인이다.
- 의존성에 Spring Web, Spring Validation, Spring Boot Actuator, Spring Boot Test가 포함된다.
- `./gradlew test`에서 `@SpringBootTest` 기반의 애플리케이션 context loading 테스트가 통과한다.
- `./gradlew build`가 성공하고 Backend 단독 빌드가 Frontend 도구 체계에 의존하지 않는다.
- wrapper, 빌드 산출물 및 Gradle cache는 저장소 제외 규칙에 따라 추적되지 않고 Wrapper 설정·빌드 파일·소스·테스트는 추적 가능하다.
- DB/JPA/Redis/Queue/Batch와 Provider Secret이 추가되지 않는다.

## 결정 및 전제

- PRD 3.1, 3.3, 3.5절 및 Backend 설정 문서를 따른다: Java 25, Spring Boot 4.1.x, Gradle 9.x(최소 9.1), Spring Web, Validation, Actuator, JUnit 5.
- Gradle Groovy DSL은 Backend 설정 문서의 `build.gradle` 및 `settings.gradle` 구조를 따른다.
- 정확한 Gradle 9.x 및 Spring Boot 4.1.x patch는 작업 구현 시점에 상호 호환되는 버전으로 Wrapper와 dependency/plugin 관리 파일에 고정한다. major/minor 기준을 변경하지 않는다.
- `TASK-001.01` Issue #1이 열려 있다. 저장소 골격과 ignore 구성이 선행 완료된 뒤에 이 작업을 구현한다.
- 일반 Controller, Health 응답의 contract와 보안 노출 설정은 `TASK-001.04`에서 추가한다.

## 연결된 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [테스트 계획](./test.md)

## 인접 작업 계약

- 선행 작업: `TASK-001.01`이 `backend/` 경로와 Gradle 산출물 ignore를 마련한다.
- 인접 작업: `TASK-001.02` Frontend toolchain과 서로의 소유 경로를 수정하지 않고 독립 수행한다.
- 후속 작업: `TASK-001.04`는 이 애플리케이션 위에 표준 오류 응답과 Health 계약을 구현한다. `TASK-001.05`는 FE/BE 독립 빌드를 통합 확인한다.

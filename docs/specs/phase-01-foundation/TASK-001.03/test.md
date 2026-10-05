# Backend 도구 체계 초기화 테스트 계획

## 테스트 목표

Java 25, Spring Boot 4.1.x, Gradle 9.x 기반 Backend가 Wrapper를 통해 독립적으로 테스트·빌드되고 Spring application context가 정상 로드되는지 확인한다. 오류 응답 및 API Health contract 검증은 `TASK-001.04` 범위다.

- GitHub Issue: [#3](https://github.com/donghyunlee-dev/meeting-automation/issues/3)
- Backend 명령은 `backend/` 작업 디렉터리에서 실행한다.

## 자동 테스트

### 애플리케이션 context loading

1. Gradle Wrapper와 테스트 dependency를 준비하고 `@SpringBootTest`의 `contextLoads` 테스트를 애플리케이션 설정 클래스 없이 실행한다.
2. 기대 결과: 테스트가 정상 수집된 뒤 Spring Boot 설정 클래스를 찾지 못해 context 초기화가 실패한다. 컴파일, 네트워크 다운로드, Gradle 초기화 실패는 유효한 red 결과가 아니다.
3. `@SpringBootApplication` 진입점을 추가하고 같은 테스트를 재실행한다.
4. 기대 결과: context loading 테스트가 통과한다.

### 런타임 및 재현 가능한 Wrapper

```powershell
java -version
javac -version
./gradlew --version
```

Windows PowerShell에서는 `./gradlew` 대신 `.\gradlew.bat`를 사용한다.

기대 결과: Java/Javac는 25, Gradle은 Wrapper에 설정된 9.x이며 Java 25 실행을 지원하는 버전(최소 9.1)이다.

### 테스트와 전체 빌드

```powershell
./gradlew test
./gradlew clean build
```

기대 결과: context loading 테스트와 전체 Backend build가 종료 코드 0으로 끝나며 Frontend 설치 또는 작업 디렉터리를 요구하지 않는다.

## 구성 및 경계 검사

- Gradle dependency report에서 Spring Web, Validation, Actuator 및 Spring Boot Test가 확인된다.
- DB/JPA, Redis, Queue/message broker, Batch 및 Provider SDK 의존성이 없다.
- `backend/.gradle/`, `backend/build/`가 ignore되고 Wrapper/소스/테스트 파일은 추적 대상이다.
- 설정과 테스트 fixture에 실제 Secret이 없다.

## 수동 QA

`./gradlew bootRun`으로 기본 애플리케이션 시작 여부를 확인하고 정상 종료한다. 이 작업에서는 업무 endpoint나 별도 Health 응답의 동작/노출을 수동 검증하지 않는다. 해당 검증은 `TASK-001.04`에서 수행한다.

## 증거

`docs/evidence/TASK-001.03.md`에 JDK/Gradle 버전, Wrapper 설정, context test의 유효한 실패 이유와 통과 결과, 전체 build 결과, dependency/ignore/Secret 경계 검사를 기록한다. 로그에 환경 값이나 Secret을 포함하지 않는다.

## 완료 기준과 검사 연결

| 완료 기준 | 검사와 증거 |
|---|---|
| Java 25 및 Gradle 9.x Wrapper | 런타임 및 재현 가능한 Wrapper 버전 출력 |
| Spring Boot 4.1.x 및 기준 의존성 | Gradle plugin/dependency 설정과 dependency report |
| context loading | 동일 테스트의 context 설정 부재 실패 후 통과 |
| 독립 빌드 | `./gradlew clean build` 종료 코드 0 |
| 생성 산출물 제외 | `git check-ignore`와 변경 상태 검사 |
| DB/Secret 비도입 | dependency 및 변경 파일 점검 |

## 릴리스 전용 검사

배포, 실제 Provider 연결, FE/BE 통합, 오류 응답 계약은 이 작업에서 검증하지 않는다. FE/BE 독립 빌드 통합 검증은 `TASK-001.05`에서 수행한다.

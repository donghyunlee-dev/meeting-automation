# Backend 도구 체계 초기화 작업 목록

## 작업 식별 정보

- 작업: `TASK-001.03`
- 선행 조건: `TASK-001.01`, [#1](https://github.com/donghyunlee-dev/meeting-automation/issues/1) 완료
- GitHub Issue: [#3](https://github.com/donghyunlee-dev/meeting-automation/issues/3)

## 테스트 우선 구현 단계

### 실행 환경과 테스트 기반 준비

- [ ] #1 완료를 확인하고 `java -version`, `javac -version`으로 JDK 25를 확인한다. 결과: 환경 버전과 저장소 경로를 기록한다.
- [ ] Gradle Groovy 프로젝트 선언과 Wrapper를 테스트 실행에 필요한 최소 구성으로 준비한다. 결과: `./gradlew --version`이 고정 Gradle 9.x와 JVM 25를 출력한다.
- [ ] `@SpringBootTest` 기반 `contextLoads` 테스트와 필요한 테스트 의존성을 추가하고 테스트를 실행한다. 결과: 테스트가 수집되고 애플리케이션 설정 클래스 부재로 context loading이 실패한다. 빌드/컴파일/의존성 오류는 테스트 실패 증거로 삼지 않는다.

### 최소 애플리케이션 구현

- [ ] PRD 호환 Spring Boot 4.1.x plugin, Java 25 toolchain, Spring Web·Validation·Actuator 의존성을 구성한다. 결과: 의존성이 PRD 기준과 일치하고 DB/persistence/messaging 계열이 없다.
- [ ] `@SpringBootApplication` 진입점과 비밀 값 없는 기본 설정을 추가한다. 결과: 이전 context loading 테스트가 통과한다.
- [ ] Gradle Wrapper 무결성 설정 및 `.gitignore` 동작을 확인한다. 결과: Wrapper 파일은 추적되고 `.gradle/`, `build/` 산출물은 제외된다.

### 통과 확인 및 증거 기록

- [ ] `./gradlew clean build`를 실행한다. 결과: 컴파일, 테스트, build가 모두 성공한다.
- [ ] `./gradlew --version` 및 `./gradlew test`를 실행해 Wrapper/JVM 버전과 context loading 통과를 기록한다.
- [ ] Backend dependency tree와 변경 파일을 점검한다. 결과: DB/JPA/Redis/Queue/Batch 또는 실제 Secret이 없다.
- [ ] 실행 명령, 버전, 실패 후 통과, build 결과를 `docs/evidence/TASK-001.03.md`에 기록한다.

## 중단 조건

- PRD 지정 Java 25, Spring Boot 4.1.x, Gradle 9.x 조합이 공식 지원 범위에서 호환되지 않으면 Major/minor를 임의 변경하지 말고 호환 오류와 결정이 필요한 선택지를 Issue에 남긴다.
- 테스트가 실행 기반 오류로 실패하면 context 동작의 red 증거로 기록하지 말고 실행 기반을 먼저 보완한다.
- Frontend/Backend baseline 또는 `TASK-001.04` API 계약의 범위 변경이 필요하면 현재 Issue에서 결정 대기한다.

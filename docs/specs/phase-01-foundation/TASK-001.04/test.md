# 공통 오류 응답 및 Health 기반 테스트 계획

## 테스트 목표

API 명세에 정의된 오류 구조, 요청 추적 ID 전달, 예외 정보 비노출 및 Actuator Health의 최소 노출을 Backend 자동 테스트와 로컬 확인으로 검증한다.

- GitHub Issue: [#4](https://github.com/donghyunlee-dev/meeting-automation/issues/4)
- 자동 테스트 명령은 선행 `TASK-001.03`의 Gradle Wrapper를 사용한다.
- 실행 디렉터리: `backend/`

## 자동 테스트

### 검증 오류 envelope

- 설정: MockMvc 테스트에서 Bean Validation 위반이 발생하는 요청을 보낸다.
- 절차: 공통 오류 handler 구현 전 실패 테스트를 실행한 뒤 최소 구현 후 같은 테스트를 재실행한다.
- 기대 결과: HTTP 400, `error.code=VALIDATION_FAILED`, `message`, `category`, `retryable`, `traceId`, `details`가 존재한다. 입력한 필드 값은 응답에 포함되지 않는다.
- 증거: 대상 테스트의 초기 실패 원인과 최종 통과 결과.

### 요청 추적 ID

- 입력: 한 요청에는 `X-Request-Id: test-request-123`을 전달하고, 다른 요청에는 헤더를 생략한다.
- 기대 결과: 첫 오류 응답의 `traceId`가 전달한 값과 일치한다. 두 번째는 비어 있지 않은 서버 생성 값을 반환한다.
- 증거: assertion 결과만 기록하며 운영 Secret이나 실제 사용자 값을 기록하지 않는다.

### 예기치 않은 예외 안전 처리

- 설정: 테스트 전용 endpoint/controller 또는 테스트 구성을 통해 예외를 발생시킨다.
- 기대 결과: HTTP 500과 공통 오류 envelope를 반환한다. 예외 메시지, 예외 클래스, stack trace, 테스트 Secret marker가 응답 body에 없다.
- 증거: status 및 비노출 assertion 결과.

### Actuator Health

- 요청: `GET /actuator/health`.
- 기대 결과: HTTP 200, JSON `status`가 `UP`이며 component 상세와 Secret marker가 없다.
- 경계 확인: `GET /actuator` 또는 `GET /actuator/env` 등 Health 외 관리 endpoint가 웹에 공개되지 않는다.
- 증거: 자동 테스트 결과와 응답 필드 검사.

### 전체 회귀

```powershell
./gradlew test
./gradlew clean build
```

Windows PowerShell에서는 두 명령의 Wrapper 경로를 `./gradlew.bat`로 사용한다. 기대 결과: TASK-001.03 context loading을 포함해 모든 테스트와 빌드가 성공한다.

## 수동 QA

```powershell
./gradlew bootRun
Invoke-RestMethod http://localhost:8080/actuator/health
```

- 기대 결과: 로컬 Health 요청이 성공하고 상태가 `UP`이다.
- 개발자 도구 또는 `curl -i`로 Health 이외 Actuator endpoint가 노출되지 않는지 확인한다.
- 오류 응답에 실제 환경 값, 입력 민감값, 내부 예외 원문이 포함되지 않는지 테스트 응답으로 확인한다.
- 서버 종료 후 확인 결과를 `docs/evidence/TASK-001.04.md`에 기록한다.

## 릴리스 전용 검사

배포 환경의 외부 접근 정책, 인증, 실제 모니터링 연계, Provider 연결 상태는 이 작업의 수용 검사에서 제외한다. 운영 Health 접근 경계는 배포 설정 작업과 보안 정책에 따른다.

## 완료 기준과 검사 연결

| 완료 기준 | 검사와 증거 |
|---|---|
| 오류 envelope 여섯 필드 | 검증 오류 및 예외 안전 처리 자동 테스트 |
| 검증 오류 HTTP 400 및 코드 | 검증 오류 envelope 테스트 |
| traceId 전달/생성 | 요청 추적 ID 테스트 |
| 내부 오류/Secret 비노출 | 예기치 않은 예외 안전 처리 테스트 |
| Health 정상 및 상세 비노출 | Actuator Health 테스트와 수동 QA |
| 회귀 안정성 | `./gradlew test`, `./gradlew clean build` |

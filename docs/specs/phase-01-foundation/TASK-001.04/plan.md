# 공통 오류 응답 및 Health 기반 구현 계획

## 목표

Spring MVC 검증 오류와 처리되지 않은 예외를 안정된 API 오류 envelope로 변환하고, Actuator Health를 상세 정보 없이 제공한다.

- GitHub Issue: [#4](https://github.com/donghyunlee-dev/meeting-automation/issues/4)
- 선행 작업: `TASK-001.03`, #3 완료 후 구현 착수
- 후속 작업: `TASK-001.05`, 이후 API 구현 작업

## 관련 원본

- [PRD](../../../product/PRD.md), 기술 기준, 오류 모델, TASK-001.04 및 §23.1
- [API Specification](../../../product/api-spec.md), 공통 HTTP 규칙과 오류 envelope
- [Architecture](../../../product/architecture.md), 요청 추적 및 보안/관측성
- [Backend Setup](../../../setup/backend-setup.md), 로컬 Health endpoint 확인
- 선행 작업 계약: `TASK-001.03`은 Spring Boot 진입점, Web/Validation/Actuator 의존성, application 설정과 Spring Boot Test 기반을 제공한다.

## 소유 경계

| 컴포넌트 | 책임 |
|---|---|
| API 오류 DTO | 공통 오류 필드와 JSON 직렬화 |
| 전역 MVC 예외 처리기 | Bean Validation 오류와 예상하지 못한 예외를 HTTP 응답으로 변환 |
| 요청 추적 ID 연결 | 입력 `X-Request-Id` 또는 서버 생성 ID를 오류 응답에 연결 |
| Actuator 설정 | Health endpoint만 웹에 노출하고 상세 정보를 숨김 |
| MVC/Actuator 계약 테스트 | 응답 구조, status, 비노출 정책 검증 |

실제 패키지명은 `TASK-001.03`에서 정한 application group 및 기존 디렉터리 구조에 맞춘다. 업무별 예외 코드, Provider health, 인증/CORS 및 운영 알림은 수정하지 않는다.

## 구현 순서와 소유권

1. **BE — 선행 기반 확인:** #3이 완료되었는지 확인하고 Spring Boot Web, Validation, Actuator 및 현재 오류 처리 방식을 확인한다. 결과: 적용 가능한 MVC 테스트 방식과 package 경계가 확정된다.
2. **BE — 실패 테스트 작성:** 오류 응답이 필요한 검증 실패 테스트와 예기치 않은 예외 테스트를 먼저 작성한다. 결과: 테스트가 수집되고 각 응답 계약 부재/위반으로 실패한다.
3. **BE — 최소 오류 구현:** 오류 DTO, 요청 추적 ID 전달, 검증 실패 매핑, 안전한 fallback 예외 매핑을 추가한다. 결과: 앞서 작성한 계약 테스트가 통과한다.
4. **BE — Health 설정과 테스트:** `/actuator/health` 정상 응답 및 Health 외 Actuator endpoint 비노출 테스트를 추가/실행하고, 설정은 health 상세를 숨기도록 제한한다. 결과: 정상 상태만 노출된다.
5. **BE — 회귀 및 증거:** `./gradlew test`, `./gradlew clean build`와 계약 확인을 실행하고 `docs/evidence/TASK-001.04.md`에 결과를 남긴다.

Health는 API보다 먼저 설정 가능하지만, 공통 error DTO와 예외 변환은 요청 컨트롤러가 준비되지 않은 상태에서도 `MockMvc` 테스트에서 검증 가능하므로 테스트가 인터페이스를 먼저 고정한다. FE 작업은 없으며, 기존 TASK-001.03 기반을 확장한다.

## 의존성

- `TASK-001.03` 완료: Spring Boot 애플리케이션과 Web/Validation/Actuator/test 의존성.
- 업무 API에서 사용하는 검증 예외는 프레임워크 타입을 직접 반환하지 않고 이 공통 handler를 경유한다.
- Provider health API `API-019` 및 업무별 오류 분류 작업과 계약을 혼합하지 않는다.

## 검증 접근

- Spring MVC 테스트에서 검증 실패 응답의 HTTP 400, JSON 필드, 오류 코드, 추적 ID, 입력값 비노출을 확인한다.
- 테스트 전용 예외 발생 경로로 fallback 응답의 HTTP 500 및 내부 예외 비노출을 확인한다.
- 요청에 `X-Request-Id`가 있거나 없는 두 경우를 검사한다.
- Actuator Health endpoint에 대해 `status=UP`과 상세/Secret 비포함을 확인하고 다른 Actuator 경로는 노출되지 않는지 확인한다.
- Backend 작업 디렉터리에서 `./gradlew test`, `./gradlew clean build`를 실행한다. Windows PowerShell에서는 `./gradlew`를 `./gradlew.bat`로 바꾼다.

# 공통 오류 응답 및 Health 기반 작업 목록

## 작업 식별 정보

- 작업: `TASK-001.04`
- 선행 조건: `TASK-001.03`, #3 완료
- GitHub Issue: [#4](https://github.com/donghyunlee-dev/meeting-automation/issues/4)

## 테스트 우선 구현 단계

### 테스트 기반과 계약 확인

- [ ] 선행 Issue #3의 완료와 Backend 테스트 기반을 확인한다. 결과: Spring Boot Web/Validation/Actuator와 테스트 실행이 가능하다.
- [ ] `plan.md`의 공통 오류 envelope와 Health 범위를 테스트 입력/응답으로 구체화한다. 결과: API 명세 외 신규 업무 오류 코드가 없다.

### 실패 테스트 작성

- [ ] Bean Validation 실패 요청의 HTTP 400, `VALIDATION_FAILED`, 공통 오류 여섯 필드, 민감 입력 비노출을 검증하는 테스트를 먼저 작성한다.
- [ ] `X-Request-Id` 지정/누락 경우의 `traceId`를 검증한다.
- [ ] 테스트 전용 예외 발생 시 HTTP 500과 안전한 오류 응답을 검증하고 내부 예외 메시지/stack trace 비노출을 확인한다.
- [ ] `/actuator/health`의 정상 응답 및 다른 Actuator 웹 경로 비노출을 검증한다.
- [ ] `./gradlew test`로 테스트가 수집되고 아직 구현되지 않은 응답 계약 때문에 실패하는지 확인한다. 설정·컴파일 실패는 유효한 red 결과로 기록하지 않는다.

### 최소 구현

- [ ] 공통 오류 DTO와 예외 변환 handler를 추가한다. 결과: 검증 오류가 API 명세 필드로 직렬화된다.
- [ ] 요청 ID 전달 또는 생성 규칙을 적용한다. 결과: 모든 오류 응답에 빈 값이 아닌 `traceId`가 존재한다.
- [ ] 처리되지 않은 예외 fallback을 추가한다. 결과: HTTP 500 응답에서 내부 원문이 제거된다.
- [ ] Actuator 노출 및 상세 설정을 Health 전용으로 제한한다. 결과: Health에는 상태만 보이고 다른 관리 경로는 공개되지 않는다.
- [ ] 실패하던 테스트를 다시 실행한다. 결과: 각 계약 테스트가 통과한다.

### 통과 확인 및 증거 기록

- [ ] 대상 MVC/Actuator 테스트와 전체 `./gradlew test`가 통과한다.
- [ ] `./gradlew clean build`가 성공한다.
- [ ] 오류 JSON 필드와 실제 Health 응답에서 Secret, 내부 예외 원문, 상세 구성 정보가 없는지 확인한다.
- [ ] 명령, 결과 및 비민감 응답 증거를 `docs/evidence/TASK-001.04.md`에 기록한다.

## 중단 조건

- [ ] 공통 오류 envelope와 기존 설정이 충돌하면 API Specification/PRD 근거를 함께 기록하고 임의로 계약을 변경하지 않는다.
- [ ] Health 공개 범위를 넓혀야 한다는 요구가 나오면 배포 접근 정책 결정 없이 노출 설정을 확장하지 않는다.

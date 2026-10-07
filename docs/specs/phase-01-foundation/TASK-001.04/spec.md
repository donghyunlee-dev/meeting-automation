# 공통 오류 응답 및 Health 기반

## 작업 식별 정보

- 작업: `TASK-001.04`
- 상위 작업 묶음: `TASK-001 Monorepo / Technology Baseline`
- 단계: `Phase 1 — 프로젝트 기반 / 공통 구조`
- PRD 기준: `PRD-MA-001`, 버전 `1.4.0`, 기준일 `2026-10-05`
- 관련 요구사항: API 공통 규칙, `ERR-001~`, `NFR-009`
- 영역: `BE`
- 선행 작업: `TASK-001.03` Backend 도구 체계 초기화 (#3)
- GitHub Issue: [#4](https://github.com/donghyunlee-dev/meeting-automation/issues/4)

## 결과

모든 Backend API 예외를 API 명세의 공통 오류 envelope로 반환하고, 최소한의 Actuator Health endpoint를 계약 테스트로 검증한다. 응답에는 요청 추적 ID와 안전한 오류 정보만 포함하며 예외 원문, stack trace, Secret, Provider 응답 본문은 포함하지 않는다.

## 범위

- API 공통 오류 응답 모델을 구현한다: `code`, `message`, `category`, `retryable`, `traceId`, `details`.
- 입력 검증 실패를 HTTP 400 및 `VALIDATION_FAILED`로 변환한다. 필드별 오류 정보는 `details`에 담되 사용자가 입력한 민감 값을 복사하지 않는다.
- 처리되지 않은 예외는 HTTP 500의 안전한 일반 오류로 변환하고 내부 예외 메시지를 응답에 노출하지 않는다.
- `X-Request-Id`가 있으면 `traceId`로 전달하고 없으면 서버가 생성한 추적 ID를 사용한다.
- `/actuator/health`의 정상 응답을 제공하고 health 응답에 상세 구성요소나 환경 값을 포함하지 않는다. 별도 actuator endpoint를 추가 노출하지 않는다.
- 오류 응답 계약, 입력 검증 변환, 예기치 않은 예외의 비노출, 추적 ID 전달 및 Health 응답을 Backend 테스트로 검증한다.

## 명시적 제외 범위

- 업무별 오류 코드/재시도 정책과 `PROCESSING_FAILURE`, `DOCUMENT_FAILURE`, `EMAIL_FAILURE`, `NOTIFICATION_FAILURE` 운영 분류 전체를 구현하지 않는다. 각 기능 작업이 소유한다.
- Provider 연결 상태인 `GET /api/v1/integrations/health`를 구현하지 않는다. 이는 API-019 및 Provider 작업 범위다.
- 인증, CORS, 배포 플랫폼의 접근 제어, 공개 Health 접근 정책을 변경하지 않는다.
- Health 외 Actuator endpoint를 노출하거나 Health 상세 정보를 반환하지 않는다.
- 로그/Slack 오류 보고 체계, 업무 API, 저장소를 추가하지 않는다.

## 완료 기준

- API 오류 응답은 HTTP status와 함께 명세된 여섯 필드를 모두 가진다.
- 검증 실패는 HTTP 400과 `VALIDATION_FAILED` 코드로 반환되고 원 입력 값이 응답에 포함되지 않는다.
- `X-Request-Id`가 제공된 요청의 오류 `traceId`는 해당 값을 반영하고, 헤더가 없을 때는 비어 있지 않은 서버 생성 ID가 반환된다.
- 처리되지 않은 예외는 HTTP 500으로 반환되며 예외 메시지, 클래스명, stack trace 또는 Secret이 응답에 나타나지 않는다.
- `/actuator/health`는 HTTP 200 및 `status: UP`을 반환하고 상세 항목/Secret을 노출하지 않는다.
- Health 이외의 Actuator 웹 endpoint는 노출되지 않는다.
- 기존 TASK-001.03 context loading 및 빌드 검증이 계속 통과한다.

## 결정 및 전제

- 오류 JSON 구조와 기본 코드 매핑은 [API Specification](../../../product/api-spec.md)의 공통 HTTP 규칙을 따른다. 작업은 공통 변환 기반만 제공하고 API 명세에 없는 업무 코드를 새로 정의하지 않는다.
- 요청 추적은 [Architecture](../../../product/architecture.md)의 `X-Request-Id` 우선, 누락 시 생성 규칙을 따른다.
- Health 경로는 Backend 설정 가이드가 확인하도록 안내하는 Actuator 기본 `/actuator/health`를 사용한다. Health 상세 노출 범위를 넓히지 않는다.
- `TASK-001.03` 선행 조건은 PR #81 및 merge commit `e0913db5dfed7db870578877fa0c6876a47e9b98`에서 완료됐다. TASK-001.04 구현을 진행할 수 있다.

## 연결된 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [테스트 계획](./test.md)

## 인접 작업 계약

- 선행 작업: `TASK-001.03`이 Spring Boot 진입점, Web, Validation, Actuator 및 Spring Boot Test 기반을 제공한다.
- 후속 작업: `TASK-001.05`가 FE/BE 독립 빌드와 환경 샘플을 검증한다. 이후 API 작업은 공통 오류 envelope와 추적 ID 규칙을 재사용한다.
- `TASK-016.01`은 이 작업의 공통 오류 변환 기반 위에 네 운영 분류를 적용한다.

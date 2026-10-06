# 구현 계획

## 의존성

- TASK-001.04 공통 exception envelope, trace ID, 안전한 fallback (#4)
- TASK-006.04 Processing stage/error/retryable context (#30)
- API Specification의 기존 error code/status/envelope와 Integrations의 네 운영 분류
- PRD v1.7.0 (2026-10-05), DEC-018, FR-027~029
- GitHub Issue: [#57](https://github.com/donghyunlee-dev/meeting-automation/issues/57)

## 변경 대상

- Error domain: 네 업무 failure type, public category mapping, safe message 및 typed FailureContext.
- Application stage: 업무 유형을 문자열 추측 없이 결정적으로 정하고 오류 context를 전달한다.
- API exception mapper: 기존 HTTP/code mapping과 INTERNAL_ERROR fallback을 유지한다.
- Processing/API-010 mapper: 기존 safe stage/error code/retryable만 노출한다.
- Delivery mapper: adapter errorCode/retryable를 API-010/API-016까지 보존한다.
- Structured logger: 허용된 correlation/error field만 기록한다.
- Tests: code/status/category, stage classification, retryability, safe fallback, redaction.

## 구현 순서

공통 오류 기반과 업무 Provider semantics가 선행됐으므로 기존 계약 characterization 후 mapper와 call sites를 연결한다.

1. 기존 HTTP/code/error fields 및 X-Request-Id 전달을 고정하는 characterization tests를 작성한다.
2. 네 업무 분류와 public category를 구분하는 매핑, retryability, redaction tests를 작성한다.
3. typed FailureContext 및 mapping implementation을 공통 handler에 연결한다.
4. TASK-006.04 stage failure 및 Document/Email/Notification 결과를 context로 연결한다.
5. API-010/Delivery projection과 structured logs의 correlation/redaction을 통합 검증한다.
6. Backend tests/build를 실행하고 evidence를 남긴다.

## 후속 계약

TASK-016.02는 incidentType/sessionId/stage/traceId/errorCode/retryable/safeMessage/occurredAt만 Admin Slack payload로 소비한다. TASK-016.03은 public code/category를 화면 복구 문구에 사용한다. 후속 TASK는 업무 분류를 추가하지 않는다.

정확한 Gradle 명령과 사례는 구현 시 저장소 설정 및 [검증 계획](./test.md)을 따른다.

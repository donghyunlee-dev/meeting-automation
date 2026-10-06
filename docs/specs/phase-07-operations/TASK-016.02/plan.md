# 구현 계획

## 의존성

- TASK-016.01 FailureContext / four incident types (#57)
- TASK-012.01 Slack Meeting webhook boundary (#48)
- DEC-019, FR-029, EXT-005, PRD v1.7.0 (2026-10-05)
- GitHub Issue [#58](https://github.com/donghyunlee-dev/meeting-automation/issues/58)

## 변경 대상

- AdminIncidentPort/Notifier: safe FailureContext 수신 및 best-effort result.
- Slack Admin adapter: SLACK_ADMIN_WEBHOOK_URL, JSON text payload, HTTP result mapping.
- Failure boundaries: 네 업무 failure에서 context를 한 번 dispatch하고 notifier failure는 원 failure에 덮어쓰지 않는다.
- Configuration: Admin webhook을 Meeting webhook과 분리하고 absent/invalid 상태를 안전하게 처리한다.
- Logging: safe incident type 및 trace/session/stage/code만 기록한다.
- Tests: payload allowlist, 네 분류, config/HTTP, single attempt, no recursion, failure preservation, redaction.

## 구현 순서

Admin webhook은 Meeting Notification과 분리해야 하므로 payload/adapter contract부터 검증한 후 failure boundaries에 연결한다.

1. Payload mapper tests: incident types, optional session/stage, strict allowlist, plain text escaping.
2. Fake HTTP tests: missing/invalid config, 200 ok, refusal, 5xx, timeout/reset, 정확히 1회 요청.
3. Admin 전용 config/client/port/adapter 구현.
4. 네 업무 failure boundary에서 notifier를 한 번 호출하고 전송 실패가 원 failure result를 바꾸지 않는지 확인.
5. Recursive reporting 방지, no retry, log/request/response redaction을 통합 검증.
6. Backend tests/build와 evidence 기록.

## 검증

실제 운영 webhook으로 test message를 보내지 않는다. Fake HTTP fixture로 전송 payload와 HTTP behavior를 검증한다. 상세 사례는 [검증 계획](./test.md)을 따른다.

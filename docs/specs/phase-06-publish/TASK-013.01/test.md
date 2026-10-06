# 검증 계획

기준: `TASK-013.01`, PRD v1.7.0 (2026-10-05), `FR-019`, `FR-027`, `API-010`, `API-016`, Issue [#50](https://github.com/donghyunlee-dev/meeting-automation/issues/50).

## 자동화 검증

구현 시 Java 21/Gradle JUnit 5 및 Backend wrapper에 정의된 명령을 사용한다. 현재 설계 workspace에는 Backend 소스/build wrapper가 없으므로 구현 단계에서 실제 명령을 확인해 Issue에 기록한다. Provider 요청은 fake로 대체하며 실 Email/Slack 전송을 하지 않는다.

| ID | 준비 및 입력 | 기대 결과 | 증거 |
|---|---|---|---|
| RETRY-01 | FAILED Delivery `retryable=true`, 유효 Session/key | HTTP 202 `{data:{deliveryId,status:"PENDING"}}`; 지정 Provider 재호출 1회 | API/application test |
| RETRY-02 | Delivery status SENT | 409 `DELIVERY_NOT_RETRYABLE`, Provider 호출 0회 | State guard test |
| RETRY-03 | FAILED, `retryable=false` | 409 `DELIVERY_NOT_RETRYABLE`, Provider 호출 0회 | Retryability guard test |
| RETRY-04 | Delivery PENDING 또는 SENDING | 409 `DELIVERY_RETRY_IN_PROGRESS`, 추가 호출 0회 | In-progress guard test |
| RETRY-05 | 존재하지 않는 Session | 404 `SESSION_NOT_FOUND` 공통 error envelope | Controller error test |
| RETRY-06 | 존재하지 않거나 다른 Session에 속한 Delivery ID | 404 `DELIVERY_NOT_FOUND`, Provider 호출 0회 | Ownership test |
| RETRY-07 | `Idempotency-Key` 누락/공백 | 400 `VALIDATION_FAILED`, 상태 변경/호출 0회 | Header validation test |
| RETRY-08 | 같은 Session/key/delivery 요청 반복 | 최초 202 body 재사용, attempt와 Provider 호출은 한 번만 증가 | Idempotency replay test |
| RETRY-09 | 같은 Session/key에 다른 deliveryId 요청 | 409 `IDEMPOTENCY_KEY_CONFLICT`, 새 호출 없음 | Idempotency conflict test |
| RETRY-10 | FAILED delivery에 다른 key 두 개가 동시에 요청됨 | 단일 FAILED→PENDING 승인, Provider 호출 1회, 나머지 409 `DELIVERY_RETRY_IN_PROGRESS` | Concurrent transition test |
| RETRY-11 | Provider invocation 전후 attemptCount 관찰 | 실제 invocation 시작 시 정확히 1 증가하고 API-010 값과 일치 | Attempt count test |
| RETRY-12 | 교정된 Participant 이메일로 Email Delivery 재시도 | 해당 participant 주소만 새로 조회/전송; 다른 수신자/Delivery 불변 | Email retry isolation test |
| RETRY-13 | Slack Notification Delivery 재시도 | 회의 webhook만 사용; Admin webhook과 다른 channel 호출 없음 | Notification retry isolation test |
| RETRY-14 | Gmail/Slack 명시 거절 또는 retryable rate limit 후 사용자가 재시도 | 새 attempt 1회 시작, 최종 Provider 결과를 API-010에 기록 | Correctable failure recovery test |
| RETRY-15 | Gmail/Slack timeout, response loss, ambiguous send 5xx | retryable false, API-016 거절, 자동/수동 재전송 0회 | Ambiguous outcome safety test |
| RETRY-16 | API-010 응답/로그/history 검사 | 주소, webhook URL, OAuth/Slack secret, 원 Provider response body 없음 | Redaction serialization test |
| RETRY-17 | Session/process memory 재시작 후 API-016 요청 | Session not found 응답, 과거 Provider를 다시 호출하지 않음 | Memory lifecycle test |
| RETRY-18 | 다른 Email 실패와 성공, Notification retry 혼합 | 선택한 Delivery만 attempt 증가; 기존 SENT와 나머지 row는 유지 | Mixed delivery isolation test |

## 수동/환경 QA

- Gmail/Slack 공급자 대신 mock server를 사용해 API-016 성공·중복·거절 응답을 호출한다.
- `retryable=true` 실패를 설정/주소 수정 후 사용자가 한 번 명시 재시도해 해당 Delivery만 결과가 갱신되는지 확인한다.
- `retryable=false` ambiguous 결과에서는 재시도 버튼/API가 차단되고 기존 결과가 유지되는지 확인한다.
- API-010을 polling해 202 접수 이후 `PENDING`/`SENDING`/최종 상태와 attemptCount가 일치하는지 확인한다.

## 완료 증거

API contract, 상태 경쟁, idempotency replay, 채널 격리, 안전한 오류/민감정보 가림 테스트 결과와 정확한 실행 명령을 Issue #50에 기록한다. 실제 Gmail/Slack 메시지를 보내지 않는다.

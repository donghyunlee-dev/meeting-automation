# 구현 계획

기준: PRD v1.7.0 (2026-10-05), `FR-019`, `FR-027`, `API-010`, `API-016`. Issue [#50](https://github.com/donghyunlee-dev/meeting-automation/issues/50).

## 의존성과 소유권

- Email recipient Delivery: TASK-011.02, Issue [#47](https://github.com/donghyunlee-dev/meeting-automation/issues/47)
- Notification Delivery: TASK-012.02, Issue [#49](https://github.com/donghyunlee-dev/meeting-automation/issues/49)
- Provider 오류 의미: TASK-011.01 및 TASK-012.01
- Backend는 retry endpoint, idempotency, 상태 전이, Provider 재호출, API-010 projection을 소유한다.
- Frontend 버튼/확인/결과 안내는 TASK-013.02에 남긴다. 여기서는 API만 검증한다.

## 변경 경계

- API controller는 Session/Delivery ID, `Idempotency-Key`, 상태를 검증해 Application use case에 위임한다.
- Application은 Session 내 Delivery를 찾아 `FAILED && retryable`을 조건부로 `PENDING`으로 바꾸고 단일 Provider command를 시작한다.
- Delivery store는 API016 상태 변경과 Publish 결과 갱신이 동시에 오더라도 한 상태 전이만 통과하도록 원자성을 보장한다.
- Email Adapter 호출 전 Participant ID로 현재 이메일을 다시 조회한다. 이메일 주소는 Delivery/history/API 응답에 저장하지 않는다.
- Notification Adapter는 현재 meeting webhook 설정을 사용한다. Admin webhook에는 접근하지 않는다.
- API-010 mapper가 `status`, `attemptCount`, `lastAttemptAt`, `errorCode`, `retryable`을 선택적으로 직렬화한다.

## 구현 순서

Retry endpoint는 기존 Delivery 상태를 재사용하고 중복 Provider 부작용을 막아야 하므로 상태 전이/API 테스트를 구현보다 먼저 둔다.

1. API-010 Delivery model/store, Publish attempt 실행 위치, 공통 Idempotency registry 및 Provider 재호출 진입점을 조사한다. 결과: 기존 상태 수명과 동시성 lock/조건부 갱신 위치를 기록한다.
2. API controller/application 테스트를 먼저 작성한다. FAILED/retryable 허용, SENT/nonretryable 거절, 없는 대상, 동일 키 replay/충돌, 동시 요청을 고정한다.
3. Delivery 상태 전이를 FAILED→PENDING→SENDING→SENT/FAILED로 구현한다. 결과: 승인된 한 번의 요청만 전송 attempt를 시작한다.
4. Email/Notification channel dispatcher가 지정된 delivery만 재호출하도록 연결한다. 결과: 수신자 하나의 재시도가 다른 수신자/채널을 건드리지 않는다.
5. API-016 response/error envelope 및 API-010 조회를 연결한다. 결과: 202는 PENDING 접수만 뜻하고 현재 상태는 API-010에서 읽는다.
6. Pre-send correctable rejection, explicit 429와 ambiguous send 5xx/timeout의 `retryable` 분류 테스트를 Provider contract에 맞춘다.
7. privacy/secret redaction 및 process-memory 경계를 검증한다. 결과: 주소, OAuth/Slack secret, 원문 오류가 Delivery에 남지 않는다.
8. Backend Java 21/Gradle 검증 명령과 결과를 Issue #50에 기록한다. 저장소 구현 경로에서 실제 명령을 확인하고 임의 명령을 만들지 않는다.

## 오류 및 상태 응답

- 없는 Session: 404 `SESSION_NOT_FOUND`; 없는/다른 Session 소속 Delivery: 404 `DELIVERY_NOT_FOUND`.
- 요청 body/header 오류: 400 `VALIDATION_FAILED`; 같은 key/다른 Delivery: 409 `IDEMPOTENCY_KEY_CONFLICT`.
- 허용되지 않는 Delivery: 409 `DELIVERY_NOT_RETRYABLE`; 이미 PENDING/SENDING: 409 `DELIVERY_RETRY_IN_PROGRESS`.
- Provider 호출 오류는 API-016 자체 오류가 아니라 API-010에 기록되는 해당 채널 FAILED Delivery다.
- 동일 key replay는 최초 접수 202만 반환한다. 최신 처리 결과는 API-010으로 조회한다.

## 검증 접근

JUnit 5에서 고정 clock/동시 요청 barrier와 fake Email/Notification Provider를 사용해 delivery 단위 호출과 상태 전이를 확인한다. Controller test에서 공통 `{data}`/`{error}` envelope와 API-010 결과를 검사한다. 실제 Gmail/Slack 전송은 자동화 테스트에 포함하지 않는다.

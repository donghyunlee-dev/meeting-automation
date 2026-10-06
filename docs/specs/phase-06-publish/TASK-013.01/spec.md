# Delivery 결과 조회와 단건 재시도 API

## 목표

API-010의 공통 Delivery 결과를 기준으로 API-016 단건 재시도를 제공한다. 사용자가 실패 Delivery 하나를 명시해 재시도하고, 성공/진행 중/결과 불명 전달은 다시 실행하지 못하게 한다. PRD v1.7.0 (2026-10-05), `FR-019`, `FR-027`, `API-010`, `API-016`을 구체화한다. Issue [#50](https://github.com/donghyunlee-dev/meeting-automation/issues/50). 재시도 정책 결정은 [Issue #50 결정 댓글](https://github.com/donghyunlee-dev/meeting-automation/issues/50#issuecomment-6011264791)에 기록했다.

## 범위

- `POST /api/v1/meeting-sessions/{sessionId}/deliveries/{deliveryId}/retry`
- 필수 `Idempotency-Key`, Delivery 상태 원자 전이 및 중복 요청 방지
- Email/Notification 모두 같은 API 계약으로 재시도
- API-010 공통 envelope/Delivery 응답으로 처리 결과 조회
- 확인된 무전송 오류, rate limit 및 불명확한 결과의 `retryable` 의미 정렬

## 비범위

- Slack/Gmail Provider HTTP 요청/오류 변환은 각각 TASK-012.01/TASK-011.01 소유다.
- SCR-008의 결과 표시와 재시도 버튼/확인 UX는 TASK-013.02 소유다.
- 자동 재시도, 묶음 재시도, 새 Delivery 생성, 성공한 전달의 재전송은 제공하지 않는다.
- 애플리케이션 DB/queue, process restart 후 Session/Delivery 복구는 범위 밖이다.

## API 계약

```http
POST /api/v1/meeting-sessions/{sessionId}/deliveries/{deliveryId}/retry
Idempotency-Key: <new-key>
```

성공은 HTTP 202 `{data:{deliveryId,status:"PENDING"}}`다. `attemptCount`와 최종 결과는 API-010으로 조회한다. API-016은 `If-Match`를 받지 않는다. Delivery 자체의 원자적 상태 전이가 동시성 제어를 담당한다.

재시도 허용 조건은 해당 Session에 속한 Delivery가 `FAILED`이며 `retryable=true`인 경우뿐이다. `SENT`, `retryable=false`, 또는 `PENDING`/`SENDING`은 각각 `DELIVERY_NOT_RETRYABLE` 또는 `DELIVERY_RETRY_IN_PROGRESS` 409로 거절한다. Session은 있으나 경로에 속한 Delivery가 없으면 `DELIVERY_NOT_FOUND` 404, Session이 없으면 기존 `SESSION_NOT_FOUND` 404다. 잘못된 ID/key는 `VALIDATION_FAILED` 400이다.

같은 Session의 같은 Idempotency-Key와 같은 `deliveryId` 요청은 최초 202 접수 응답을 재사용하고 재호출하지 않는다. 같은 키로 다른 `deliveryId`를 보내면 `IDEMPOTENCY_KEY_CONFLICT` 409다. 동일 Delivery에 서로 다른 key가 동시에 도착하면 FAILED→PENDING 조건부 갱신에서 한 요청만 승인하며 나머지는 `DELIVERY_RETRY_IN_PROGRESS`로 거절한다. 요청 접수 후 API-010은 `PENDING`/`SENDING`/최종 결과를 반환한다. 중복된 동일 키의 202 replay가 최종 상태를 뜻하지 않으므로 Client는 API-010을 조회한다.

Session, Delivery, 멱등성 원장은 기존 memory-only 수명을 따른다. process 재시작 후 Session이 없어지면 API-016은 `SESSION_NOT_FOUND`이고 새 전송을 임의로 시작하지 않는다. Delivery ID는 경로 Session 소속을 반드시 검사한다.

## 재시도 및 `retryable` 의미

`retryable`은 **사용자가 API-016으로 명시 요청할 수 있는지**를 뜻한다. 자동 재시도 표지가 아니다. 수락되지 않았음이 확인된 다음의 오류는 원인 수정 후 `true`다.

- OAuth/Email 설정 또는 권한 거절, 수정 가능한 수신자 주소 오류
- Slack webhook 설정/권한 거절
- Provider의 명시적 quota/rate limit 거절
- Gmail 전송을 시작하기 전 실패한 OAuth token refresh

Gmail `messages.send` HTTP 5xx, Slack webhook HTTP 5xx, 연결 timeout/reset, 응답 유실처럼 외부 Provider가 요청을 수락했는지 판단할 수 없는 경우는 `false`다. 전송 중인/성공한 Delivery도 재시도하지 않는다. 모든 재시도는 새로운 사용자 동작과 새 Idempotency-Key가 필요하며 Adapter 내부 loop는 없다.

Email 재시도 때는 저장된 `recipientParticipantId`로 최신 Participant 이메일을 다시 조회한다. 주소를 보정했어도 Session roster와 원래 선택 범위는 바꾸지 않는다. Notification은 현재 `SLACK_MEETING_WEBHOOK_URL` 설정을 사용한다. API 응답/Delivery에는 이메일 주소, webhook URL, Provider 원문을 넣지 않는다.

## 완료 기준

- `FAILED && retryable=true`인 지정 Delivery만 202로 재시도되고 다른 Delivery는 호출되지 않는다.
- 동일 key replay, key payload 충돌, 서로 다른 key의 동시 요청에서 이중 전송이 없다.
- SENT/nonretryable/in-progress/missing Session 또는 Delivery가 명세된 공통 오류 envelope를 반환한다.
- Provider가 명시 거절한 설정/권한/rate limit 오류는 수정 후 재시도 가능하고 모호한 부작용 결과는 불가하다.
- 시도 횟수는 Provider 호출이 시작될 때 한 번 증가하고 API-010에서 확인된다.
- Email recipient 주소, OAuth/Slack Secret, 원 Provider 오류 본문이 응답/로그/history에 없다.

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

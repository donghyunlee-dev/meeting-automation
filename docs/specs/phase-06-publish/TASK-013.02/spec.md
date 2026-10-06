# Complete 결과와 Delivery 재시도 화면

## 목표

SCR-008에서 저장된 문서, 수신자별 Email, Slack Notification 결과를 독립적으로 확인하고, API-016이 허용한 단일 Delivery만 사용자가 명시 재시도하게 한다. PRD v1.7.0 (2026-10-05), `SCR-008`, `API-010`, `API-016`, `FR-019`, `FR-027`을 구체화한다. 선행 설계는 [TASK-013.01](../TASK-013.01/spec.md), Email 결과 연결은 [TASK-011.02](../TASK-011.02/spec.md), Slack 결과 연결은 [TASK-012.02](../TASK-012.02/spec.md)다. Issue [#51](https://github.com/donghyunlee-dev/meeting-automation/issues/51).

## 범위와 비범위

- API-010 Session snapshot과 generic `deliveries[]`를 SCR-008에 표시
- Document link, 수신자별 Email 결과, Notification 결과를 구분
- `FAILED && retryable=true`인 각 Delivery에만 API-016 재시도 action 제공
- 재시도 접수, 진행, 완료/실패 갱신 및 응답 유실 조정
- 접근성, 모바일 화면, 개인정보 보호

Document 저장 실패/재시작은 TASK-010.03이 담당한다. API-016의 validation/idempotency/atomic state transition은 TASK-013.01이 담당한다. Adapter 내부/시간 기반 자동 재시도, 여러 Delivery 일괄 재시도, 성공/결과 불명 전달 재전송은 제공하지 않는다.

## 진입과 화면 결과

SCR-008은 API-010에 저장된 `document` 참조가 있을 때 보여준다. Document 저장 실패 또는 참조 부재 시 이 화면에서 Email/Slack 재시작을 제공하지 않고 TASK-010.03의 Publish 실패/복구 경로를 유지한다. Document 링크는 유효한 `http`/`https` `documentUrl`이 있을 때만 연다. 링크가 없거나 잘못됐어도 저장 완료와 Delivery 결과는 보여주되 링크는 제공하지 않는다.

`deliveries[]` 각 행은 서로 독립적으로 표시한다. `EMAIL`은 수신자별로 보이고 `recipientParticipantId`를 화면에서 확보한 이름과 연결한다. 이름이 없으면 ID를 fallback label로 사용한다. 이메일 주소는 결과 화면에 표시하지 않는다. `NOTIFICATION`은 V1의 `Slack` 행으로 표시하며 `recipientParticipantId`나 Slack 채널 주소를 만들지 않는다. 전송하지 않은 채널 row가 없으면 가상의 성공/건너뜀 상태를 만들지 않는다.

요약 문구는 API-010의 문서/Delivery 원본으로만 계산한다.

- 하나 이상의 Delivery가 `PENDING`/`SENDING`이면 `전달 진행 중`을 표시한다.
- 미완료가 없고 모든 존재하는 Delivery가 `SENT`이면 `완료`를 표시한다.
- `FAILED`가 있고 다른 Delivery가 성공/진행 중이면 `일부 전달 실패`를 표시한다.
- 존재하는 모든 Delivery가 `FAILED`이면 `전달 실패`를 표시한다.
- `deliveries:[]`이면 문서 저장 완료만 표시한다. 모든 채널이 성공/선택 해제되었다고 추론하지 않는다.

`SENT`는 Gmail/Slack이 요청을 접수했음을 나타내며 inbox 도착, 읽음, Slack 구성원 열람을 뜻하지 않는다. Document 결과는 각 채널 결과와 독립 행으로 유지한다. Email 또는 Notification 실패로 저장된 Document를 실패로 바꾸지 않는다.

## 재시도 동작

- 재시도 action은 해당 행이 `FAILED && retryable=true`일 때만 보인다. `SENT`, `PENDING`, `SENDING`, `retryable=false`에는 숨긴다.
- 각 클릭은 해당 `deliveryId`를 경로에 넣은 API-016 POST와 새 `Idempotency-Key`를 만든다. in-flight 요청 동안 그 행의 버튼만 잠그며 다른 Delivery는 조작할 수 있다.
- 202 `{data:{deliveryId,status:"PENDING"}}`를 받으면 행을 진행 중으로 바꾸고 API-010 polling 결과로 최종 상태/attemptCount를 갱신한다.
- HTTP timeout/응답 유실 때는 같은 클릭에 사용한 key를 보존하고 API-010을 다시 조회한다. POST를 resolve해야 하면 같은 key를 재사용해 idempotent replay/최초 접수 중 하나로 조정한다. 이 흐름은 새 전달 시도를 만들지 않는다. 새 key는 API-010에서 이전 시도가 종료되어 FAILED가 확인되고 사용자가 다시 클릭한 경우에만 발급한다.
- 409 `DELIVERY_NOT_RETRYABLE`/`DELIVERY_RETRY_IN_PROGRESS`는 API-010을 다시 읽어 버튼과 상태를 갱신한다. 안전한 오류 문구를 표시하고 새 key로 자동 재호출하지 않는다.
- `retryable=false` 실패는 재시도 버튼 없이 오류 code에 맞는 안전한 안내를 보여준다. Provider 수락 여부가 불명확한 경우에는 중복 전달 방지를 위해 다시 보내지 않으며 목적지 수동 확인을 안내한다.

## 개인정보와 접근성

- 결과 카드/API-010 row에 전체 이메일 주소, Slack webhook URL/channel, OAuth/Secret, Provider 원문 응답/오류를 표시하거나 로그로 남기지 않는다.
- Email 이름 lookup 실패는 Delivery 상태 표시를 막지 않고 participant ID label로 대체한다.
- 상태는 텍스트와 screen reader live region으로 전달하며 색상만으로 성공/실패를 구분하지 않는다. Polling 업데이트가 focus를 이동시키지 않는다.
- 각 Retry 버튼의 accessible name은 `deliveryId`/수신자 이름 또는 Notification 목적을 식별한다. 재시도 상태에서만 해당 row button을 비활성화한다.
- 360px 모바일 폭과 keyboard-only 조작에서 결과/링크/Retry를 이용할 수 있다.

## 완료 기준

- Document/각 Email/Slack 결과가 개별 행으로 표시되고 혼합 결과가 독립적으로 유지된다.
- 요약 상태가 API-010 데이터에 맞고 누락 row를 성공으로 추정하지 않는다.
- `retryable=true` FAILED 행만 API-016으로 재시도되며 `retryable=false`/성공/진행 중 상태는 호출되지 않는다.
- 요청 유실/202/409 뒤 API-010 결과로 갱신하고 같은 사용자 동작에서 중복 Delivery가 생기지 않는다.
- Document link, 이메일/Slack 표시, 접근성, 민감정보 가림 조건이 검증된다.

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

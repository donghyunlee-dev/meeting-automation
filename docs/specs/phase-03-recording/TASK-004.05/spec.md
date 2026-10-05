# New Meeting API 연결 설계

## 목표

New Meeting에서 검증한 폼을 API-006에 제출하고 생성된 Session 정보를 보존해 Recording 화면으로 이동한다. PRD v1.7.0 (2026-10-05), `FR-002`, `SCR-002`, `API-006`, `TASK-004.05`를 구체화한다. Issue [#18](https://github.com/donghyunlee-dev/meeting-automation/issues/18).

## 범위

- `{title,templateId,participantIds,timezone}` form callback 소비
- Browser `recoveryKey`, `Idempotency-Key` 생성 및 같은 시도 재사용
- POST API-006, submit progress/duplicate guard/error handling
- 201 Session ID/version/uploadPolicy를 in-memory app state에 저장
- Recording route로 이동

## 비범위

API-006 Backend 구현, 녹음/MediaRecorder/Audio upload, Session 복구 endpoint 및 process restart 복구는 포함하지 않는다.

## 제출 계약

유효 form payload에 browser-generated 비어 있지 않은 `recoveryKey`와 `Idempotency-Key`를 추가해 `POST /api/v1/meeting-sessions`로 보낸다. 하나의 논리 제출 시도는 불변 snapshot `{title,templateId,participantIds,timezone,recoveryKey,idempotencyKey}`를 가진다. 전송 중 버튼은 disabled이며 request는 한 번만 진행한다. 응답 불명 네트워크 오류 뒤 사용자 재시도는 동일 snapshot과 두 key를 그대로 사용한다. 사용자 수정으로 payload가 바뀌면 새 논리 시도로 간주해 새 key pair를 생성한다. 자동 backoff/retry는 수행하지 않는다.

201 응답 `{sessionId,version,status,uploadPolicy}`는 Session Context에 저장하고 `/meetings/{sessionId}/recording`으로 이동한다. `recoveryKey`나 Idempotency-Key는 Provider나 URL에 노출하지 않는다. Session context는 Browser runtime memory이며 page reload/process restart 복구를 보장하지 않는다.

400 검증, 409 key conflict, 500 내부 오류, 502 roster Provider 오류는 API 공통 envelope의 안전한 `message`를 사용해 표시한다. 실패 시 사용자가 입력한 폼 draft를 보존하고 수정 또는 같은 시도 재전송을 제공한다. Provider raw body/stack/secret은 표시하지 않는다. Document `provider:null`은 회의 Session 생성 요청을 막지 않는다.

## 수용 기준

- callback form payload에 API-006 필드와 생성한 두 key를 더해 POST한다.
- 진행 중 중복 클릭은 추가 request를 만들지 않는다.
- 같은 제출 재시도는 동일 snapshot/key를 유지하며 변경 payload는 새 시도다.
- 성공 응답 전체를 in-memory context에 둔 다음 지정 Recording route로 이동한다.
- 오류/재시도 뒤 form 값이 보존되고 API 오류가 안전하게 보인다.
- API client/component/router tests로 성공, 오류, 재시도/중복 차단을 증명한다.

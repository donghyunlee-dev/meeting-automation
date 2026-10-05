# 참가자 생성 API 설계

## 목표

회의 작성자가 사용할 Participant roster record를 Document Provider에 추가하는 Backend API를 제공한다. PRD v1.7.0 (2026-10-05), `FR-024`, `API-004`, `TASK-003.02`를 구현 가능한 계약으로 구체화한다. 관련 이슈는 [#11](https://github.com/donghyunlee-dev/meeting-automation/issues/11)이다.

## 범위

- `POST /api/v1/participants` Controller와 application Service
- Request `{name,email}`, 성공 `201` 및 `{id,name,email}` 응답
- 공백 제거/필수값 및 email 형식 검증
- `DocumentProvider.createParticipant(command)` 호출 및 Provider 오류 변환
- `Idempotency-Key` 동일 요청 재전송 및 payload 충돌 처리

## 비범위

목록/수정 API, UI, 삭제, 별도 DB, roster 이메일 중복 판정은 포함하지 않는다. 입력 email이 실제 전달 가능한 주소인지 확인하거나 사람/계정에 연결하지 않는다.

## 계약과 동작

이름/email을 trim한 뒤 저장하고 응답한다. name은 비어 있지 않아야 하고 email은 단일 주소 형식이어야 한다. 유효한 요청은 TASK-003.01 저장 규칙을 따라 Participants child page를 만든다: title은 name, 본문 `Email: <address>` 항목은 email, provider page ID는 표준 id다.

동일 Idempotency-Key와 동일한 정규화 payload는 최초 생성 결과를 재사용한다. 같은 키로 다른 payload가 오면 409 `IDEMPOTENCY_KEY_CONFLICT`다. 서로 다른 키로 같은 요청을 보내는 경우 별도 생성 요청으로 처리한다.

필수 Participants 구조 누락은 422 `DOCUMENT_STRUCTURE_NOT_FOUND`; Provider 생성 실패는 502 `DOCUMENT_FAILED`, 운영 분류 `DOCUMENT_FAILURE`로 변환한다. Retryable은 오류가 일시적일 때만 true다. 원문과 인증정보는 응답/로그에 넣지 않는다.

## 수용 기준

- 유효 입력은 Provider roster 항목 하나를 만들고 `201` `{data:{id,name,email}}`를 반환한다.
- 누락/공백 name 및 잘못된 email은 Provider 호출 전에 400 `VALIDATION_FAILED`로 거절한다.
- 동일 키·동일 요청 재전송은 새 페이지를 만들지 않고 기존 생성 결과를 반환한다.
- 동일 키·다른 payload는 409 `IDEMPOTENCY_KEY_CONFLICT`다.
- 구조 오류와 Provider 오류가 지정한 안전 표준 오류로 매핑된다.
- 테스트는 idempotency 동시/재전송 시 중복 생성이 없는 것과 Provider 원문 비노출을 확인한다.

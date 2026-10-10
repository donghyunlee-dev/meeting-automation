# 검증 계획

> 📌 v1.9.0 변경 계약: TASK-022.06 완료 후 재개한다. API-006에 documentConnectionVersion과 전역 READY/전환 lock guard를 적용하고 기존 201·멱등성·검증과 함께 미설정/전환 중/stale connection 409를 검증한다. PR #95는 보존하고 새 master 반영 후 테스트·리뷰·QA를 다시 수행한다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

## 자동화 테스트

| ID | 준비/입력 | 기대 결과 | 증거 |
|---|---|---|---|
| API-006-01 | 유효 title/template/roster/timezone/recoveryKey와 신규 Idempotency-Key | HTTP 201, Session ID, version 1, CREATED, upload policy | Controller/Service test |
| API-006-02 | title 누락/공백, template 누락/미지원 | 400 `VALIDATION_FAILED`, Session 미생성 | Validation test |
| API-006-03 | participantIds 누락/빈 배열/중복/미존재 ID | 400 `VALIDATION_FAILED`, Session 미생성 | Service test |
| API-006-04 | timezone/recoveryKey 누락 또는 공백 | 400 `VALIDATION_FAILED` | Validation test |
| API-006-05 | `Idempotency-Key` 누락/공백 | 400 `VALIDATION_FAILED`, Session 미생성 | Header validation test |
| API-006-06 | Participant roster 조회 실패 | 502 `PARTICIPANT_LIST_FAILED`, 안전 오류 | Error mapping test |
| API-006-07 | required Template resource/config 오류 | 500 `INTERNAL_ERROR`, 내부 원문 없음 | Error mapping test |
| API-006-08 | 같은 Idempotency-Key/같은 payload 재전송 | 기존 동일 Session/response, store 생성 1건 | Idempotency test |
| API-006-09 | 같은 Key/다른 payload | 409 `IDEMPOTENCY_KEY_CONFLICT`, 추가 생성 없음 | Idempotency test |
| API-006-10 | server policy 확인 | chunk duration 15, max bytes 5242880, MIME webm/mp4 | Response DTO test |
| API-006-11 | Session 조회 | meeting 정보와 CREATED/version 1 확인 | Store test |
| API-006-12 | Provider 원문/Secret sentinel 오류 | 응답과 일반 로그에서 값 미노출 | Security/error test |

구현 후 `./gradlew test`, `./gradlew clean build`를 실행한다. 설계 중에는 실행하지 않는다.

## 수동 QA

- 유효 요청이 201 및 Session ID를 반환하는지 확인한다.
- 참가자 하나가 roster에 없거나 ID가 중복이면 저장 전에 요청이 거부되는지 확인한다.
- 동일 idempotency key 요청을 재전송해 동일 응답이 오는지 확인한다.
- 서버 재시작 후 이전 Session ID 조회는 휘발되어 404임을 확인한다.

## 릴리스 확인

Upload policy가 설정 기준을 반환하고 Server process 외부 저장소를 Session 복구 수단으로 가장하지 않는지 확인한다. 실제 녹음/업로드는 후속 task에서 검증한다.

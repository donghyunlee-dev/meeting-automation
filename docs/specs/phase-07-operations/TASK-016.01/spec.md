# 오류 분류 및 공통 매핑 설계

## 작업 식별 정보

- 작업: TASK-016.01
- 상위 작업: TASK-016 Error / Admin Slack
- 단계: Phase 7 — History / Settings / Operations
- PRD 기준: PRD-MA-001 v1.7.0, 2026-10-05
- 관련 기준: DEC-018, FR-027~029, API 공통 오류 envelope
- 영역: Backend
- 선행 작업: TASK-001.04 (#4), TASK-006.04 (#30)
- GitHub Issue: [#57](https://github.com/donghyunlee-dev/meeting-automation/issues/57)

## 결과

API 오류는 기존 public code/category/HTTP mapping으로 유지하고, 업무 실패는 PROCESSING_FAILURE, DOCUMENT_FAILURE, EMAIL_FAILURE, NOTIFICATION_FAILURE 네 운영 분류로 정규화한다. 정규화 context는 traceId/sessionId/stage/errorCode/retryable을 보유하되 본문·Secret·Provider 원문은 제외한다. Admin Slack 전송은 TASK-016.02 소유다.

## 분류 계약

| 공개 오류 계열 | category | 예시 code | 운영 incidentType |
|---|---|---|---|
| 입력 검증 | VALIDATION | VALIDATION_FAILED | 없음 |
| 자원 없음 | NOT_FOUND | SESSION_NOT_FOUND, PARTICIPANT_NOT_FOUND, MEETING_NOT_FOUND | 실패가 아니므로 없음 |
| 상태/멱등성 충돌 | CONFLICT | SESSION_STATE_CONFLICT, SESSION_VERSION_CONFLICT, IDEMPOTENCY_KEY_CONFLICT | 없음 |
| 처리 실패 | PROCESSING_FAILURE | PROCESSING_FAILED | PROCESSING_FAILURE |
| 문서 Provider 실패 | DOCUMENT_FAILURE | DOCUMENT_FAILED, PARTICIPANT_LIST_FAILED | DOCUMENT_FAILURE |
| Email 전달 실패 | EMAIL_FAILURE | EMAIL_FAILED | EMAIL_FAILURE |
| Notification 전달 실패 | NOTIFICATION_FAILURE | NOTIFICATION_FAILED | NOTIFICATION_FAILURE |
| 미분류 내부 오류 | INTERNAL | INTERNAL_ERROR | 근거 있는 업무 context가 있을 때만 업무 분류 |

HTTP status와 code는 API Specification을 따른다. 새로운 public business error code는 만들지 않는다. 업무 범위는 Audio assembly/STT/diarization/Minutes, Notion/Confluence, Email, Slack 등 Notification이다.

FailureContext에는 incidentType, optional sessionId/stage, traceId, errorCode, retryable, occurredAt, 고정 safeMessage만 둔다. stage 값은 AUDIO_ASSEMBLY, TRANSCRIPTION, DIARIZATION, MINUTES_GENERATION, DOCUMENT_SAVE, EMAIL_DELIVERY, NOTIFICATION으로 제한한다. Validation/not found/conflict는 incident context로 만들지 않는다.

분류는 예외 문자열 추측이 아니라 application stage와 typed safe error code를 사용한다. 미분류 예외는 기존 INTERNAL_ERROR, category INTERNAL, retryable=false와 일반 문구로 변환한다.

## 재시도와 보안

- retryable은 안전한 명시적 재요청 가능 여부이며 서버 자동 반복을 뜻하지 않는다.
- Email/Notification retry는 API-016이 Delivery별 값을 사용한다. 성공, ambiguous timeout/response loss는 retryable=false다.
- Processing/Document는 idempotency 또는 기존 작업/문서를 확인할 수 있어 중복 부작용이 없을 때만 true다. 불확실하면 false다.
- public message/details에는 원 exception, SDK message, HTTP body, stack trace, 원 입력, Audio, Transcript, Minutes, email, credentials를 복사하지 않는다.
- 구조화 로그에는 traceId, sessionId(있으면), stage, incidentType, errorCode, retryable, occurredAt만 남긴다.

## 비범위

- Admin Slack 전송/속도 제한은 TASK-016.02다.
- 사용자 오류 화면은 TASK-016.03이다.
- Adapter별 provider error detection은 각 Provider task다.
- 추가 public error codes, 자동 retry, DB/incident store, logging backend 도입은 범위 밖이다.

## 완료 기준

- 기존 API code/category/HTTP contract가 유지된다.
- 네 업무 incidentType만 사용하고 validation/not-found/conflict는 운영 실패로 오분류하지 않는다.
- 업무별 safe error context와 retryable 값이 일관되며 ambiguous side effect는 false다.
- 오류 응답과 로그에서 민감 본문/Secret이 빠지고 trace/session/stage로 추적 가능하다.
- TASK-016.02와 API-010/Delivery consumers가 동일한 context를 사용한다.
- 완료 기준은 검증 계획과 연결된다.

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

# 검증 계획

## 자동화 테스트

Backend JUnit/API/structured-log capture tests와 fake adapters를 사용한다. 실제 Provider나 Slack incident 전송은 하지 않는다.

| ID | 조건 | 기대 결과 | 증거 |
|---|---|---|---|
| ERR-VALIDATION | validation exception 및 민감 입력 | 400 VALIDATION_FAILED, category VALIDATION, 원 입력 없음 | API exception test |
| ERR-NOTFOUND | Session/Participant/Meeting 미존재 | 기존 404 code, category NOT_FOUND, incident 미생성 | mapper test |
| ERR-CONFLICT | state/version/idempotency 충돌 | 기존 409/412 code, category CONFLICT, incident 미생성 | API tests |
| ERR-INTERNAL | secret가 담긴 unknown exception | 500 INTERNAL_ERROR, INTERNAL, retryable false, 안전한 고정 문구 | handler test |
| ERR-PROCESSING | assembly/STT/diarization/minutes 실패 | PROCESSING_FAILURE, 정확한 stage/code/traceId | stage tests |
| ERR-DOCUMENT | Provider read/create 실패 | DOCUMENT_FAILURE 및 기존 공개 code | adapter/application test |
| ERR-EMAIL | 확정 거절 및 성공 delivery | 실패만 EMAIL_FAILURE; 성공은 incident가 아님 | Delivery contract test |
| ERR-NOTIFICATION | webhook 실패 및 성공 | 실패만 NOTIFICATION_FAILURE; 성공은 incident가 아님 | Delivery contract test |
| ERR-RETRY | 명시 거절, 보호된 재시도, ambiguous timeout | source policy 보존, ambiguous false, 자동 반복 0회 | adapter/service test |
| ERR-API010 | processing/delivery 실패 projection | 기존 API-010 safe code/category/retryable/stage shape 유지 | serialization test |
| ERR-CORRELATION | request ID 존재/부재 및 Session pipeline error | traceId 전달/생성, sessionId/stage 구조 로그에 포함 | log capture test |
| ERR-REDACTION | provider body, token, email, transcript/minutes가 예외에 포함 | 응답/details/log 어디에도 원문 없음 | security assertions |
| ERR-TAXONOMY | 업무 오류 및 validation/not-found/conflict | 허용된 업무 incidentType은 네 종류이며 비업무 오류는 제외 | parameterized test |

## 수동 QA

- 비생산 fixture로 각 failure class의 HTTP 응답과 API-010 projection을 비교한다.
- 구조화 로그에서 traceId/sessionId/stage/errorCode로 연관 추적되며 본문/Secret이 없는지 확인한다.
- 부작용 없는 fake로 응답 불명 timeout이 retryable=false인지 확인한다.

## 릴리스 확인

- ./gradlew test 및 ./gradlew clean build를 실행하고 docs/evidence/TASK-016.01.md에 결과를 기록한다.
- API compatibility, API-016 explicit retry와 TASK-016.02 input contract를 확인한다.

# Review 확정 API 검증 계획

## 자동화 테스트

| ID | 요청/Session 조건 | 기대 결과 | 증거 |
|---|---|---|---|
| CONF-API-01 | REVIEW, `CONFIRM` allowed, current If-Match, unique key, all speakers mapped, valid Minutes | HTTP 200 `{status:CONFIRMED,version+1}`; atomic transition 1회 | Controller/application integration test |
| CONF-API-02 | body confirm missing/null/false/string 또는 malformed JSON | HTTP 400 `VALIDATION_FAILED`; status/version/data unchanged | request validation test |
| CONF-API-03 | 누락/잘못된 If-Match 또는 Idempotency-Key | HTTP 400 `VALIDATION_FAILED`; Session unchanged | required header test |
| CONF-API-04 | unknown sessionId | HTTP 404 `SESSION_NOT_FOUND`; dependency 호출 없음 | route/use case test |
| CONF-API-05 | status가 REVIEW 아님 또는 `CONFIRM` action 미허용 | HTTP 409 `SESSION_STATE_CONFLICT`; Session unchanged | state/action guard test |
| CONF-API-06 | current version과 다른 If-Match | HTTP 412 `SESSION_VERSION_CONFLICT`; 최신 Review data 보존 | optimistic concurrency test |
| CONF-API-07 | 일부 또는 전부 `participantId:null`인 detected speakers | HTTP 422 `REVIEW_VALIDATION_FAILED`; 각 missing speaker path에 `SPEAKER_UNMAPPED`; no mutation | mapping completeness test |
| CONF-API-08 | speaker mapping id가 Session meeting roster 밖 | HTTP 422 issue `PARTICIPANT_OUTSIDE_SESSION_ROSTER`; Session unchanged, DocumentProvider 호출 0회 | session roster reference test |
| CONF-API-09 | Review snapshot에 speaker 0명 | mapping 조건 통과, 다른 조건 valid면 200 confirm | empty speaker boundary test |
| CONF-API-10 | Minutes required field missing/type invalid | HTTP 422; 모든 직접 invalid paths/code 포함; no partial transition | full schema aggregation test |
| CONF-API-11 | 빈 summary/section arrays/actionItems | schema상 유효, 나머지 조건 충족 시 200 | empty content acceptance test |
| CONF-API-12 | blank list item/action task, bad date, roster-external owner | HTTP 422 issue codes `BLANK_VALUE`, `INVALID_DATE`, `PARTICIPANT_OUTSIDE_SESSION_ROSTER`; 모든 이슈 반환 | Minutes semantics test |
| CONF-API-13 | null owner/date | 유효한 optional values; 나머지 조건 충족 시 200 | null optional boundary test |
| CONF-API-14 | speaker + 여러 Minutes validation failures 동시 존재 | 하나의 422에 모든 `{path,code}` issue; deterministic path order; common envelope fields 유지 | multi-issue contract test |
| CONF-API-15 | issue 응답에 name/email/Minutes/Transcript sentinel 입력 | `details.issues`에는 path/code만 있고 message/log에 sentinel 없음 | validation redaction test |
| CONF-API-16 | 성공한 key/session/confirm/version을 CONFIRMED 뒤 재전송 | 최초 200 및 최초 version replay; version 추가 증가 0 | success idempotency replay test |
| CONF-API-17 | 동일 key를 다른 session/body/version으로 재사용 | HTTP 409 `IDEMPOTENCY_KEY_CONFLICT`; Session unchanged | idempotency fingerprint test |
| CONF-API-18 | 같은 version에서 서로 다른 key의 confirm 동시 요청 | 한 건만 CONFIRMED/version +1, 나머지는 state/version conflict; transition 한 번 | CAS concurrency test |
| CONF-API-19 | API-014 success/failure dependency interaction | DocumentProvider/Publish/Email/notification 호출 0회; success만 status를 변경 | no-side-effect verification |
| CONF-API-20 | Error category/trace/error details serialization | incomplete Review는 422 `REVIEW_VALIDATION_FAILED`, `category=VALIDATION`, `retryable=false`, traceId 및 issues 반환 | error envelope contract test |

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행한다. 설계 단계에서는 실행하지 않는다.

## 수동 API QA

- valid REVIEW fixture의 detected Speaker 전부를 Session roster participant에 mapping하고 API-014를 보내 CONFIRMED/version 증가를 확인한다.
- speaker null mapping과 동시에 blank action task/date 오류가 있는 fixture를 보내 422 `details.issues`에서 모든 path/code가 제공되고 Session은 REVIEW로 그대로인지 확인한다.
- 같은 valid key/request를 confirm success 뒤 재전송해 version이 재증가하지 않는지 확인한다. 다른 request/session으로 key를 재사용해 409를 확인한다.
- 잘못된 header/body, stale version, confirmed 상태를 분리해 400/412/409 오류와 상태 보존을 확인한다.
- API logs/response에서 Transcript/Minutes/participant name/email/provider detail/Secret 값이 없는지 확인한다.
- API-014에서 DocumentProvider call이 없고 document 생성은 API-015에서 시작되는지 확인한다.

## 릴리스 확인

- `details.issues`는 공통 error envelope의 업무별 optional extension이며 error `message`/`details`에 사용자 콘텐츠를 복사하지 않는다.
- Session에 저장된 roster ID만 기준으로 사용하고 Provider 조회는 수행하지 않는다.
- document create/share/notification은 이 endpoint에서 실행되지 않는다.
- production publish는 포함하지 않는다. automated/manual evidence와 실제 명령 결과를 Issue #43에 남긴다.

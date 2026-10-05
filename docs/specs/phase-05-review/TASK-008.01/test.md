# Minutes 편집 API 검증 계획

관련 Issue: [#37](https://github.com/donghyunlee-dev/meeting-automation/issues/37).

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| API-012-01 | REVIEW Session, current If-Match, valid complete StructuredMinutes | HTTP 200, `{data:{version+1,minutes}}`, 모든 field 저장 | Controller/application integration test |
| API-012-02 | empty Transcript에서 생성된 빈 summary/arrays | valid update/no-op 처리, empty values 보존 | empty draft test |
| API-012-03 | 필수 field 누락, null/unknown field 또는 잘못된 JSON type | HTTP 400 `VALIDATION_FAILED`, 이전 Minutes 유지 | DTO/schema validation test |
| API-012-04 | discussion/decision/follow-up 배열 item 빈 문자열/공백 | HTTP 400 `VALIDATION_FAILED`, Session 불변 | text validation test |
| API-012-05 | action item task 빈 문자열/공백 또는 필수 key 누락 | HTTP 400 `VALIDATION_FAILED`, 저장 없음 | action item validation test |
| API-012-06 | ownerParticipantId가 Session roster 밖 | HTTP 404 `PARTICIPANT_NOT_FOUND`, 저장 없음 | roster reference test |
| API-012-07 | invalid calendar date, 형식 오류, dueDate null | 잘못된 date는 400, null은 유효 | date boundary test |
| API-012-08 | `templateId`/`templateVersion` 누락 또는 현재값과 다름 | HTTP 400 `VALIDATION_FAILED`; API-012가 Template를 바꾸지 않음 | template immutability test |
| API-012-09 | Session 없음 | HTTP 404 `SESSION_NOT_FOUND` | controller/use case test |
| API-012-10 | state가 REVIEW 아님 또는 `UPDATE_MINUTES` 미허용 | HTTP 409 `SESSION_STATE_CONFLICT`, 저장 없음 | state/action guard test |
| API-012-11 | If-Match 누락/형식 오류 | HTTP 400 `VALIDATION_FAILED`, 저장 없음 | header validation test |
| API-012-12 | If-Match가 최신 Session version과 다름 | HTTP 412 `SESSION_VERSION_CONFLICT`, 최신 Minutes 보존 | optimistic concurrency test |
| API-012-13 | 두 다른 body가 같은 version으로 동시에 요청 | 하나만 저장/version +1, 나머지 412 | CAS race test |
| API-012-14 | current Minutes와 동일한 complete body 및 matching version | HTTP 200 현재 version/Minutes, version과 저장 데이터 불변 | no-op update test |
| API-012-15 | 성공 후 API-010 read-after-write | 같은 version의 전체 StructuredMinutes 반환, Transcript/Speaker unchanged | API integration test |
| API-012-16 | Minutes/Transcript/Email/Secret sentinel validation/provider-independent error | 응답/로그에 sentinel 미노출 | privacy/log redaction test |
| API-012-17 | ownerParticipantId가 빈 문자열 | HTTP 400 `VALIDATION_FAILED`, 저장 없음 | owner field validation test |

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행하고 결과를 기록한다. 설계 단계에서는 테스트를 실행하지 않는다.

## 통합/수동 QA

- API-010 Review response의 현재 Minutes 전체를 읽어 API-012에 수정본을 보내고 API-010으로 재조회한다.
- 빈 Transcript draft, 빈 배열 및 null owner/date를 보존하면서 공백 task와 잘못된 date가 저장을 거부하는지 확인한다.
- template id/version은 API-012에서 유지되고 Template 변경은 API-013을 별도로 요구하는지 확인한다.
- stale version 및 동시 PUT에서 마지막 저장값이 부분 덮어쓰기되지 않는지 확인한다.
- 저장 전후 Transcript text/segment IDs/Speaker mapping과 Session의 나머지 field가 동일한지 확인한다.

## 릴리스 확인

- API-012 성공 후 API-010 response와 `allowedActions`가 최신 version으로 일치하는지 확인한다.
- 로그 및 오류 응답에 Minutes/Transcript 원문, Participant email, Secret이 포함되지 않는지 확인한다.

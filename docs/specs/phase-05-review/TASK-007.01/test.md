# Speaker mapping API 검증 계획

관련 Issue: [#34](https://github.com/donghyunlee-dev/meeting-automation/issues/34).

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| API-011-01 | REVIEW Session, current If-Match, 전체 유효 mapping set | HTTP 200, `{data}` response, version +1, Speaker 순서의 ID/name mapping 반환 | Controller/application integration test |
| API-011-02 | 일부 Speaker `participantId:null` | 명시적 미매핑으로 저장/반환, 다른 Speaker mapping 유지 | mapping domain test |
| API-011-03 | 감지 Speaker 0명, `mappings:[]` | HTTP 200, 빈 mappings와 version 규칙 적용 | empty set test |
| API-011-04 | 감지 Speaker가 있으나 `mappings:[]` 또는 일부 Speaker 누락 | HTTP 400 `VALIDATION_FAILED`, Session 불변 | full replacement validation test |
| API-011-05 | 존재하지 않는 speakerId 또는 중복 speakerId | HTTP 400 `VALIDATION_FAILED`, Session 불변 | speaker reference validation test |
| API-011-06 | participantId가 Session creator-selected roster 밖 또는 roster lookup에 없음 | HTTP 404 `PARTICIPANT_NOT_FOUND`, Session 불변 | roster reference test |
| API-011-07 | Participant 목록 Provider 오류 또는 구조 오류 | `PARTICIPANT_LIST_FAILED`/`DOCUMENT_STRUCTURE_NOT_FOUND`, 부분 저장 없음 | Provider exception mapping test |
| API-011-08 | Session 없음 | HTTP 404 `SESSION_NOT_FOUND` | use case/controller test |
| API-011-09 | Session 상태가 REVIEW가 아니거나 allowed action 없음 | HTTP 409 `SESSION_STATE_CONFLICT`, Session 불변 | state guard test |
| API-011-10 | If-Match 누락/형식 오류 | HTTP 400 `VALIDATION_FAILED`, Session 불변 | header validation test |
| API-011-11 | If-Match가 현재 version과 다름 | HTTP 412 `SESSION_VERSION_CONFLICT`, 최신 값 덮어쓰기 없음 | optimistic concurrency test |
| API-011-12 | 서로 다른 두 mapping mutation이 동일 version으로 경합 | 최대 한 요청만 성공, 다른 요청은 412, 부분 혼합 mapping 없음 | concurrent CAS test |
| API-011-13 | 한 speaker에 매핑 변경 후 API-010 재조회 | Speaker participantId와 모든 해당 segment의 speakerId reference가 동일 snapshot/version에 있고 Transcript text/time은 유지됨 | API-010 read-after-write integration test |
| API-011-14 | Provider/Transcript/Email/Secret sentinel 입력 또는 오류 | response/error/log에 민감 sentinel 없음 | privacy/log redaction test |
| API-011-15 | 서로 다른 speakerId가 같은 유효 participantId로 mapping | 두 mapping 모두 유효하게 저장되고 반환 | mapping cardinality test |

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행하고 결과를 기록한다. 설계 단계에서는 테스트를 실행하지 않는다.

## 통합/수동 QA

- 기존 REVIEW Session에서 전체 mapping set을 저장하고 API-010 재조회에서 Speaker mapping과 해당 Transcript segment 표시 결과가 일치하는지 확인한다.
- 선택 해제한 Speaker가 null로 남고 나머지 mapping은 유지되는지 확인한다.
- stale version, Review 외 Session, 없는 Participant reference에서 기존 mapping이 변경되지 않는지 확인한다.
- Provider 목록 조회가 실패할 때 safe error만 반환되고 일부 Speaker mapping이 저장되지 않는지 확인한다.
- Browser/UI에서 최종 dropdown 연결/전체 submit UX는 TASK-007.02 및 TASK-007.03 통합 QA에서 수행한다.

## 릴리스 확인

- API-011 성공 뒤 API-010의 Session version 및 `allowedActions`가 일관되게 갱신되는지 확인한다.
- 운영 로그 및 사용자 오류에 Participant email, Transcript, Provider 원문, Secret이 기록되지 않는지 확인한다.

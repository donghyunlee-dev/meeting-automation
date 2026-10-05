# Review 확정 검증 API

## 목표

`POST /api/v1/meeting-sessions/{sessionId}/confirm`에서 사용자의 확정 요청과 Review snapshot을 검증한다. 완전한 Session만 `CONFIRMED`로 전이하며, 미완료 Review는 common error envelope의 field issue 전체 목록으로 반환한다. PRD v1.7.0 (2026-10-05), 사용자 결정 A, `FR-016`, `API-014`, `TASK-010.01`을 구체화한다. Issue [#43](https://github.com/donghyunlee-dev/meeting-automation/issues/43).

## 범위

- confirm body, `If-Match`, `Idempotency-Key`, Session state/action validation
- 모든 감지 Speaker mapping의 완전성 및 meeting creator가 Session에 지정한 roster ID 참조 검증
- TASK-008.01/API-012와 동일한 StructuredMinutes full schema validation
- 모든 의미상 미완료 조건을 stable `path`/`code`로 수집하는 422 response
- 성공 Session status/version/`allowedActions`의 원자 전이
- 동일 요청 성공 replay와 idempotency key payload 충돌 방지
- 오류/log에서 입력 데이터와 회의 콘텐츠 redaction

## 비범위

Document Provider lookup/API-015 Publish (`TASK-010.02`), UI validation result 표현 (`TASK-010.03`), Session lifecycle 생성, Minutes/Speaker mapping 수정, document 저장/전달은 포함하지 않는다.

## API-014 요청/응답

```http
POST /api/v1/meeting-sessions/{sessionId}/confirm
If-Match: "<current-version>"
Idempotency-Key: <opaque-key>
Content-Type: application/json

{"confirm":true}
```

성공은 HTTP 200 `{data:{status:"CONFIRMED",version:<new-version>}}`다. Session 상태와 version을 한 atomic mutation으로 변경하고 version을 1 증가시킨다. `allowedActions`는 후속 mutation이 불가능하도록 재계산된다. Document Provider/외부 시스템을 호출하지 않는다.

## 요청/상태 오류

- body는 boolean `confirm:true`만 허용한다. false/missing/null/다른 type, malformed JSON, 누락 또는 잘못된 `If-Match`/`Idempotency-Key`는 HTTP 400 `VALIDATION_FAILED`다. 공통 envelope의 `details`에는 민감 입력 값을 제외한 field-level request issue를 둔다.
- Session이 없으면 404 `SESSION_NOT_FOUND`다.
- status가 `REVIEW`가 아니거나 `CONFIRM` action이 허용되지 않으면 409 `SESSION_STATE_CONFLICT`다.
- `If-Match`가 현재 Session version과 다르면 412 `SESSION_VERSION_CONFLICT`다. 거절은 status/version/Minutes를 바꾸지 않는다.
- Session을 찾은 뒤 accepted idempotency record가 key/session/request/If-Match fingerprint와 정확히 일치하면 상태 검사보다 먼저 최초 성공 response를 그대로 replay한다. 같은 key를 다른 session/body/version으로 재사용하면 409 `IDEMPOTENCY_KEY_CONFLICT`다. 불완전 Review의 422 응답은 state mutation/확정 결과로 저장하지 않는다.
- 같은 key를 통한 성공 replay는 version을 재증가하거나 validate/provider work를 다시 실행하지 않는다. 성공 완료 뒤 새 key로 다시 confirm하면 409 `SESSION_STATE_CONFLICT`다.

## Review 완료 검사

모든 business validation은 하나의 immutable Review snapshot/version을 기준으로 하고, 발견한 의미상 issue를 모두 모은다. 하나라도 있으면 어떤 mutation도 하지 않고 HTTP 422 `REVIEW_VALIDATION_FAILED`를 반환한다.

- Speaker는 Transcript의 감지 집합 전체를 뜻한다. 각 `speakers[i].participantId`가 null이 아니고 Session `meeting.participantIds` 안에 있어야 한다. Speaker 수가 0이면 mapping 검사는 통과한다. 동일 Participant가 여러 Speaker에 매핑되는 것은 허용한다.
- 검증 기준은 회의 생성자가 Meeting Session에 저장한 `participantIds` reference뿐이다. API-014는 외부 Provider를 조회하지 않는다.
- `minutes`에는 TASK-008.01의 필수 일곱 field `templateId`, `templateVersion`, `summary`, `discussionPoints`, `decisions`, `actionItems`, `followUps`가 있어야 한다. id/version/summary는 문자열, section은 올바른 배열이어야 한다. 불완전/잘못된 persisted 구조는 issue로 반환한다.
- empty summary, 빈 section arrays, 빈 `actionItems`, `ownerParticipantId:null`, `dueDate:null`은 유효하다.
- discussion/decision/follow-up 항목과 `actionItems[i].task`는 trim 기준 공백이 아니어야 한다. `dueDate`는 null 또는 calendar-valid `YYYY-MM-DD`여야 한다.
- non-null `ownerParticipantId`는 Session `meeting.participantIds`에 있어야 한다. 외부 Document Provider를 조회하지 않는다.
- 검사는 Session 저장값만 사용하고 누락 roster/field를 자동 수정하거나 추정하지 않는다.

## 422 오류 계약

기존 common envelope를 유지하고 업무 확장인 `details.issues`를 필요할 때만 더한다.

```json
{
  "error": {
    "code": "REVIEW_VALIDATION_FAILED",
    "message": "회의록 검토가 완료되지 않았습니다.",
    "category": "VALIDATION",
    "retryable": false,
    "traceId": "tr_xxx",
    "details": {
      "issues": [
        {"path":"speakers[0].participantId","code":"SPEAKER_UNMAPPED"},
        {"path":"minutes.actionItems[0].task","code":"REQUIRED"}
      ]
    }
  }
}
```

- Issue item은 `{path,code}`이며 입력 값/회의 콘텐츠/Provider 상세는 넣지 않는다.
- `path`는 API-010 Review DTO root 기준 dot/bracket path로, 사용자 데이터 값 대신 field/index/opaque `speakerId`만 포함한다.
- 오류 code는 안정적인 enum: `SPEAKER_UNMAPPED`, `PARTICIPANT_OUTSIDE_SESSION_ROSTER`, `REQUIRED`, `INVALID_TYPE`, `BLANK_VALUE`, `INVALID_DATE`.
- 같은 snapshot이면 오류 목록은 Speaker 순서 → Minutes 필드/배열 순서로 결정적으로 정렬한다. 해당 항목이 비어 있거나 type이 틀려 파생 검증을 수행할 수 없는 경우 중복 issue를 만들지 않고 직접 원인만 반환한다.
- request/header 문제는 `VALIDATION_FAILED` 400이며 business issue response와 혼합하지 않는다. 422는 최종 오류 목록이므로 일부만 반환하거나 첫 오류에서 멈추지 않는다.

## 원자성 및 privacy

- API-014는 API-010 Session snapshot에서 검증 후 동일 version compare-and-set 경계에서 `status=CONFIRMED`, `version=current+1`, 성공 idempotency result를 함께 commit한다.
- validation issue, stale version, state conflict, storage failure가 있으면 Session의 REVIEW status/version/Speaker/Transcript/Minutes와 allowed actions를 부분 갱신하지 않는다.
- 성공 200은 PRD success envelope를 사용한다. API-010에서 후속 상태를 읽는 것은 다른 Task의 책임이다.
- trace/session ID, 결과 코드와 issue path/code만 구조화 로그에 허용한다. Minutes/Transcript/action item 값, roster names/emails, request body, exception 원문, Secret은 error/log에 포함하지 않는다.

## 수용 기준

- 현재 REVIEW/CONFIRM action 및 matching version의 유효 요청만 CONFIRMED로 전이하고 version을 한 번 증가시킨다.
- 모든 감지 Speaker가 Session roster ID에 매핑되고 StructuredMinutes 전체 schema가 유효해야 성공한다.
- 불완전 mapping과 모든 Minutes issue가 deterministic `details.issues` 배열에 함께 들어 있는 422 `REVIEW_VALIDATION_FAILED`를 반환한다.
- empty summary/arrays, null optional owner/date와 0 detected speakers를 유효하게 처리한다.
- malformed request/header는 400, session missing은 404, state/action conflict는 409, stale version은 412; 모든 거절은 Session snapshot을 보존한다.
- 성공 idempotent replay는 동일 원 response/version을 반환하며 transition을 재실행하지 않는다. payload/key 충돌은 409다.
- Participant roster 확인은 Session reference만 사용하고 외부 Provider 호출이 0회다.
- API-014는 Publish side effect가 없으며 성공 이후 document creation은 별도 API-015다.
- 오류/log에 사용자 입력, Minutes/Transcript, participant name/email, Provider response 및 Secret이 없다.

## 의존성

- TASK-008.01 Issue #37: StructuredMinutes field/type/date/owner contract
- TASK-008.03 Issue #39: versioned API-010 Review snapshot 및 atomic save/requery 관례
- TASK-007.01 Issue #34: 전체 감지 Speaker mapping 및 Session roster ID 조건
- TASK-006.06 Issue #32: API-010 `allowedActions`와 Session/status snapshot
- TASK-008.01 Issue #37: Confirm이 재검증하는 StructuredMinutes schema
- Issue #43 결정 A: incomplete Review의 HTTP/error/details 계약

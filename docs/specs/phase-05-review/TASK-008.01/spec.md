# Minutes 편집 API

## 목표

API-012 `PUT /api/v1/meeting-sessions/{sessionId}/minutes`로 사용자가 Review에서 편집한 Structured Minutes 전체를 검증하고 Session에 저장한다. PRD v1.7.0 (2026-10-05), `FR-012`, `FR-015`, `API-012`, `DEC-011`, `TASK-008.01`을 구체화한다. Issue [#37](https://github.com/donghyunlee-dev/meeting-automation/issues/37). 선행 계약은 API-010 Review snapshot TASK-006.06 Issue [#32](https://github.com/donghyunlee-dev/meeting-automation/issues/32)다.

## 범위

- Review Session, `UPDATE_MINUTES` allowed action 및 `If-Match` version 검증
- 전체 Structured Minutes request schema 및 field 검증
- Participant owner ID와 Session 생성 시 선택 roster 참조 검증
- Template identity 불변성, atomic Minutes/version 갱신
- 성공 response와 API-010 read-after-write 일관성
- common error envelope, 동시 update 방지 및 Transcript/Secret 보호

## 비범위

Minutes 편집 UI 및 Transcript Drawer(`TASK-008.02`), UI/API 저장 통합(`TASK-008.03`), Template 재생성(`API-013`/`TASK-009.*`), Transcript/Speaker mapping 수정, Session 상태 변경, Provider/LLM 호출은 포함하지 않는다.

## 요청·응답 계약

```http
PUT /api/v1/meeting-sessions/{sessionId}/minutes
If-Match: "7"
Content-Type: application/json
```

```json
{
  "templateId":"default.md",
  "templateVersion":"1.0.0",
  "summary":"수정한 요약",
  "discussionPoints":["논의 항목"],
  "decisions":["결정 사항"],
  "actionItems":[{"ownerParticipantId":"pt_001","task":"API 검토","dueDate":"2026-10-10"}],
  "followUps":["후속 확인"]
}
```

- Body는 완전한 Structured Minutes replacement다. 필수 field 7개를 모두 전달하고 null/알 수 없는 field/누락 field를 허용하지 않는다. 빈 배열은 유효하다.
- `templateId`와 `templateVersion`은 현재 Session에 저장된 값과 정확히 같아야 한다. 변경 시 400 `VALIDATION_FAILED`로 거절한다. Template 변경은 API-013 명시적 재생성에서만 한다.
- `summary`는 문자열이며 빈 문자열도 허용한다(빈 Transcript 초안 호환). `discussionPoints`, `decisions`, `followUps`는 string 배열이며 각 item은 trim 기준 non-empty여야 한다. 검증 후 유효한 사용자 원문은 임의로 재작성하지 않는다.
- 각 `actionItems` 원소는 `ownerParticipantId`, `task`, `dueDate`만 가진다. `task`는 trim 기준 non-empty string, owner는 Session의 `meeting.participantIds` 중 하나 또는 null, `dueDate`는 유효한 달력 날짜 `YYYY-MM-DD` 또는 null이다.
- Owner ID가 Session 선택 roster에 없으면 404 `PARTICIPANT_NOT_FOUND`. 이 검증은 Session에 담긴 roster 참조만 사용하고 Provider/참석 여부 조회를 하지 않는다.
- Session이 없으면 404 `SESSION_NOT_FOUND`. 현재 Session이 `REVIEW`가 아니거나 `UPDATE_MINUTES`가 허용되지 않으면 409 `SESSION_STATE_CONFLICT`.
- `If-Match`가 누락/잘못된 형식이면 400 `VALIDATION_FAILED`; version이 다르면 412 `SESSION_VERSION_CONFLICT`이며 최신 Minutes를 덮어쓰지 않는다.
- 전체 body 검증 후에만 Minutes를 하나의 Session mutation으로 교체한다. 실질적 content가 바뀌면 version을 1 증가시키고 `{data:{version,minutes}}`를 반환한다. 동일 content면 저장소를 변경하지 않고 현재 version과 저장된 Minutes로 200 응답한다.
- 동시 mutation은 version compare-and-set로 직렬화한다. 한 요청이 성공한 뒤 이전 version을 쓴 다른 요청은 412다. 어떤 validation/provider-independent error도 일부 section을 저장하지 않는다.
- API-010 재조회는 증가 version과 저장된 전체 Structured Minutes를 같은 snapshot으로 제공한다. Transcript, Speaker mapping, `allowedActions` 외 Session field는 이 API에서 수정하지 않는다.
- 오류/로그에 Minutes/Transcript 원문, Participant email, provider body 또는 Secret을 기록하지 않는다. safe error와 trace/session ID만 기록한다.

## 수용 기준

- 유효한 full Structured Minutes update가 Review Session에 원자 저장되고 새 version과 저장본을 반환한다.
- empty draft 및 empty arrays를 처리하고 Template id/version 변경은 거절한다.
- 잘못된 field/type/task/date/roster owner, 없는 Session, Review 외 state, stale version은 이전 Minutes를 보존한다.
- 같은 content의 PUT은 content/version을 바꾸지 않고 현재 snapshot을 반환한다.
- 동시 update는 compare-and-set로 하나만 변경을 반영하고 다른 stale update는 412다.
- API-010 read-after-write가 같은 saved Minutes/version을 반환한다.
- 오류, 응답, 로그에 Minutes/Transcript 원문·email·Secret이 노출되지 않는다.

## 결정 및 전제

API-012의 PUT과 “Structured Minutes 전달” 규칙을 전체 replacement로 해석한다. `templateId`/`templateVersion`은 Minutes의 provenance이며 DEC-011에 따라 API-012에서 바꾸지 않는다. Participant owner 검증은 Session의 생성자 선택 roster ID를 기준으로 한다. API-013 재생성은 별도 명령이므로 이 Task에서 Template/LLM 처리를 시작하지 않는다.

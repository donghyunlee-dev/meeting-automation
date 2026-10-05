# Speaker mapping PUT API

## 목표

API-011 `PUT /api/v1/meeting-sessions/{sessionId}/speaker-mappings`를 구현해 Review 단계에서 사용자가 고른 Participant를 감지된 Speaker에 저장한다. 하나의 mapping은 해당 Speaker의 모든 Transcript segment에 적용한다. PRD v1.7.0 (2026-10-05), `DEC-009`, `DEC-010`, `FR-010`, `API-011`, `TASK-007.01`을 구체화한다. Issue [#34](https://github.com/donghyunlee-dev/meeting-automation/issues/34). 선행 계약은 TASK-006.06 Issue [#32](https://github.com/donghyunlee-dev/meeting-automation/issues/32)과 TASK-003.01 Issue [#10](https://github.com/donghyunlee-dev/meeting-automation/issues/10)이다.

## 범위

- Session ID, `REVIEW` 처리 단계, `UPDATE_SPEAKER_MAPPING` 허용 동작, 필수 `If-Match` version 검증
- 감지된 전체 Speaker에 대한 mapping set 입력 검증과 roster Participant 참조 검증
- Participant 선택 해제(`participantId:null`)를 포함한 Session Speaker mapping 변경
- mapping set과 Session version의 원자적 갱신
- 변경 뒤 새 version 및 `{speakerId,participantId,participantName}` 전체 mapping 응답
- 표준 공통 오류 envelope, 404/409/412/validation/provider 오류 경계 및 민감정보 비노출

## 비범위

Review UI/Dropdown 및 Frontend 저장 연결(`TASK-007.02`, `TASK-007.03`), diarization, 개별 Transcript segment 수정, Speaker/Participant 생성·삭제, Session/Review 전이, 자동 화자 실명 인식은 포함하지 않는다.

## 요청·응답 계약

```http
PUT /api/v1/meeting-sessions/{sessionId}/speaker-mappings
If-Match: "3"
Content-Type: application/json
```

```json
{
  "mappings": [
    {"speakerId":"speaker_a","participantId":"pt_001"},
    {"speakerId":"speaker_b","participantId":null}
  ]
}
```

- `mappings`는 Review snapshot의 전체 감지 Speaker 집합을 대체하는 mapping set이다. 모든 감지 Speaker ID를 정확히 한 번 포함한다. 화자 수가 0인 Session은 빈 배열을 허용한다.
- 각 `speakerId`는 현재 Session에서 감지된 ID여야 한다. 중복·미지정·알 수 없는 Speaker ID 또는 배열 누락/중복은 HTTP 400 `VALIDATION_FAILED`다.
- `participantId`는 명시적으로 `null`이거나, Session 생성자가 선택한 `meeting.participantIds` 중 하나여야 한다. Session 선택 roster 바깥 ID 또는 provider roster에서 찾을 수 없는 ID는 HTTP 404 `PARTICIPANT_NOT_FOUND`다. Participant 이름은 TASK-003.01의 `DocumentProvider.listParticipants()`가 반환한 표준 roster에서 확인한다. provider 목록 오류/구조 오류는 기존 `PARTICIPANT_LIST_FAILED` 또는 `DOCUMENT_STRUCTURE_NOT_FOUND` 계약으로 변환한다. 이 검증은 roster 참조 무결성만 확인한다.
- Session이 없으면 404 `SESSION_NOT_FOUND`. 현재 상태가 `REVIEW`가 아니거나 `UPDATE_SPEAKER_MAPPING`을 허용하지 않으면 409 `SESSION_STATE_CONFLICT`.
- `If-Match`가 없거나 형식이 잘못되면 400 `VALIDATION_FAILED`. 요청 version이 현재 Session version과 다르면 412 `SESSION_VERSION_CONFLICT`; 저장 변경은 없다.
- 전체 입력과 roster 참조 검증에 성공하고 version compare-and-set가 일치할 때만 mapping 전체를 한 번에 저장하고 version을 1 증가시킨다. provider/validation/version 저장 오류가 나면 이전 mapping set 및 version을 보존한다.
- 응답은 `{data:{version,mappings:[{speakerId,participantId,participantName}]}}`를 사용한다. `participantId:null`인 항목의 `participantName`은 `null`이다. 목록은 Transcript의 Speaker 순서를 따른다.
- API-010은 동일한 새 Session version에서 Speaker의 `participantId`와 Transcript segment의 `speakerId` 참조를 반환한다. Frontend는 Speaker ID join으로 매핑 이름을 모든 segment에 표시한다. Transcript 원문을 복제하거나 수정하지 않는다.
- 이름, 이메일, Transcript, provider 원문 및 Secret은 error/log에 포함하지 않는다. 로그에는 `traceId`, `sessionId`, safe error code와 outcome만 기록한다.

## 수용 기준

- 허용된 Review Session의 전체 mapping set이 유효할 때 새 version으로 원자 저장된다.
- 응답은 모든 detected Speaker의 participant ID/name을 반환하고 null 매핑을 보존한다.
- Speaker mapping 한 건은 Transcript segment를 변경하지 않고 해당 speaker의 모든 segment 표시에서 동일 Participant로 resolve된다.
- 없는 Session, Review 외 상태, stale version, 잘못된 Speaker/Participant 참조와 Provider 목록 실패는 지정 오류 envelope로 반환되며 부분 저장이 없다.
- 빈 Speaker set과 빈 mappings는 성공하고, 비어 있지 않은 Speaker set의 빈/불완전 mapping set은 거부된다.
- 같은 Participant ID의 사용은 여러 Speaker에 허용되며, Speaker별로 매핑 대상은 최대 하나다.
- 응답, 예외, 로그에 Transcript/provider 원문, email, Secret이 노출되지 않는다.

## 결정 및 전제

PRD의 HTTP `PUT` 및 전체 mapping request 예시를 따라 `mappings`는 전체 replacement set으로 해석한다. 확인된 Participant 정보의 정본은 TASK-003.01 roster다. API는 자동 인식/참석 판정을 하지 않으며, mapping 결과는 생성자가 회의에 선택한 roster ID 참조에 한정한다. 상태/버전의 허용 여부는 API-010 `allowedActions` 표시 힌트와 별개로 Backend가 매 mutation에서 재검증한다.

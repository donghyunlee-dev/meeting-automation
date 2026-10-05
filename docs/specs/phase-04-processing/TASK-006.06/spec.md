# Processing 상태 및 Review 데이터 조회 API

## 목표

API-010 `GET /api/v1/meeting-sessions/{sessionId}`로 Backend memory에 있는 Session의 현재 처리 상태와 Review에 필요한 완료 데이터를 표준 `{data}` 응답으로 조회한다. PRD v1.7.0 (2026-10-05), `SCR-005`, `API-010`, `TASK-006.06`을 구체화한다. Issue [#32](https://github.com/donghyunlee-dev/meeting-automation/issues/32).

## 범위

- route `sessionId`로 Session Store read-only query
- common response envelope의 `sessionId`, `version`, `status`, `processing`
- REVIEW 완료 시 `speakers`, `transcript`, `minutes`, `allowedActions` 반환
- 처리 중/실패 응답, 없는 Session 404 표준 오류
- Session 단일 snapshot에서 일관된 version과 fields 직렬화
- sensitive content/API error redaction 및 query read-only 보장

## 비범위

Session 생성/수정/상태 전이, pipeline job/processing, failure retry command, Provider query, UI polling/route 전환(`TASK-006.07`), Meeting list/history API는 포함하지 않는다.

## 응답 계약

```http
GET /api/v1/meeting-sessions/{sessionId}
Accept: application/json
```

모든 성공은 `{ "data": { sessionId, version, status, processing, ... } }` envelope를 사용한다. 필수 common fields는 `sessionId`, 현재 snapshot `version`, `status`, `processing:{stage,progressPercent}`다.

- `PROCESSING` 및 다른 REVIEW 이전 상태에는 `speakers:[]`, `transcript:[]`, `minutes:null`, `allowedActions:[]`을 반환해 partial review data를 노출하지 않는다. `processing.stage`/`progressPercent`는 API-009/006.04/006.05가 기록한 현재 snapshot을 사용한다.
- `PROCESSING_FAILED`에는 실패 stage와 마지막 완료 progress를 유지하고 `speakers:[]`, `transcript:[]`, `minutes:null`, `allowedActions:[]`을 반환한다. 내부/provider 예외 원문은 제공하지 않는다. Frontend는 status+stage로 안전한 일반 실패 안내를 구성한다.
- `REVIEW`에서만 완료된 `speakers`, 시간순 `transcript`, Structured `minutes`, `allowedActions`를 한 Session version snapshot으로 반환한다. Speaker는 표준 `{speakerId,label,participantId}`; segment는 `{segmentId,speakerId,startMs,endMs,text}`; Minutes는 `data-spec.md` StructuredMinutes schema를 따른다.
- REVIEW `allowedActions`는 현재 API-011~014에 해당하는 유효 action만 포함한다. 초기 Review는 `UPDATE_SPEAKER_MAPPING`, `UPDATE_MINUTES`, `REGENERATE_MINUTES`, `CONFIRM`; Session mutation/전이에 따라 매 query에서 재계산한다. Review 전/최종 상태에는 빈 배열이다.
- Session이 없거나 Backend 재시작 후 memory에서 사라졌으면 HTTP 404 `SESSION_NOT_FOUND` common error envelope.
- query는 status/version/processing progress를 변경하지 않는다. Transcript/Minutes/Secret/Provider 원문을 로그에 쓰지 않는다.

## 수용 기준

- 처리 중 API-009 초기 stage/progress와 이후 각 pipeline stage/progress를 같은 Session snapshot으로 반환한다.
- 실패 Session은 `PROCESSING_FAILED` 및 실패 stage를 보여주고 부분 review data/내부 오류 원문을 노출하지 않는다.
- Review 완료 Session은 matching version/status와 전체 Speakers/Transcript/Minutes/allowedActions를 반환한다.
- Speaker reference와 Transcript segment는 동일 snapshot에서 일관되고 Minutes schema가 Data Spec과 일치한다.
- 없는 Session은 404 `SESSION_NOT_FOUND`다.
- 반복 GET은 같은 저장 데이터를 반환하고 Session version, state, `allowedActions`를 변경하지 않는다.

## 결정 및 전제

API-010 기존 response schema와 공통 `{data}` envelope를 우선 사용한다. error 원문을 새 field로 확장하지 않고 status/stage 기반 상태를 반환해 API response의 secret/privacy 경계를 유지한다. Review data는 모든 생성 단계가 완료된 단일 Session commit 이후 공개한다.

> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# Processing 상태 및 Review 데이터 조회 API

> **계약 갱신:** 이 패키지가 작성될 당시 `PROCESSING_FAILED`의 `allowedActions`는 빈 배열이었다. 사용자 승인으로 추가된 변환 retry/download/finalize action과 Audio 보존 metadata는 [TASK-017.02](../../phase-07-security/TASK-017.02/spec.md) Issue #61 및 후속 UI task가 해당 기준을 대체한다. 현재 task는 기존 API-010의 안전한 `errorCode`, `retryable`, `audioAvailable` 필드를 유지하고 Audio 복구 기능이 없으므로 `audioAvailable:false`, 빈 `allowedActions`를 반환한다. 실패 응답의 안전한 오류 경계와 partial Review data 비노출은 계속 유효하다.

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

모든 성공은 `{ "data": { sessionId, version, status, processing, ... } }` envelope를 사용한다. 필수 common fields는 `sessionId`, 현재 snapshot `version`, `status`, `processing:{stage,progressPercent}`다. `processing` 객체는 모든 상태에서 존재하며, `CREATED`/`UPLOADING`처럼 처리가 시작되지 않은 상태의 `stage`는 `null`, progress는 `0`이다. 이렇게 해야 아직 stage가 없는 Session도 공통 응답 형태를 유지한다.

- `PROCESSING` 및 다른 REVIEW 이전 상태에는 `speakers:[]`, `transcript:[]`, `minutes:null`, `allowedActions:[]`을 반환해 partial review data를 노출하지 않는다. `processing.stage`/`progressPercent`는 API-009/006.04/006.05가 기록한 현재 snapshot을 사용한다.
- `PROCESSING_FAILED`에는 실패 stage와 마지막 완료 progress, 저장된 safe `errorCode`, `retryable`, `audioAvailable:false`를 `processing`에 포함하고 `speakers:[]`, `transcript:[]`, `minutes:null`, `allowedActions:[]`을 반환한다. 현재는 Audio download/retry 기능과 expiry가 없으므로 `audioExpiresAt`은 생략하거나 null이다. TASK-017.02/#61이 Audio lifecycle을 구현할 때 가용성, expiry 및 복구 action을 갱신한다. 내부/provider 예외 원문은 제공하지 않는다.
- `REVIEW`에서만 완료된 `speakers`, 시간순 `transcript`, Structured `minutes`, `allowedActions`를 한 Session version snapshot으로 반환한다. Speaker는 표준 `{speakerId,label,participantId}`이며, Speaker mapping 전 `participantId`는 `null`이다. segment는 `{segmentId,speakerId,startMs,endMs,text}`; Minutes는 `data-spec.md` StructuredMinutes schema를 따른다.
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

API-010 기존 response schema와 공통 `{data}` envelope를 우선 사용한다. `processing` 객체는 항상 포함하고 시작 전에는 null stage와 0 progress로 나타낸다. 현재 `Speaker` 모델에는 participant mapping이 없으므로 Review 응답의 `participantId`는 null로 나타낸다. 실패 응답은 Session에 저장된 safe error code/retryability와 현재 지원하지 않는 Audio 복구 상태를 표시하되 provider 원문을 확장하지 않는다. TASK-017.02/#61이 Audio lifecycle/expiry와 recovery action을 구현한다. Review data는 모든 생성 단계가 완료된 단일 Session commit 이후 공개한다.

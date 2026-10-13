# Audio Processing 시작 API

## 목표

API-009 `POST /api/v1/meeting-sessions/{sessionId}/process`를 구현해 모든 Audio Chunk가 수신된 Session의 처리를 멱등하게 시작한다. PRD v1.7.0 (2026-10-05), `FR-006`, `API-009`, `TASK-005.07`을 구체화한다. Issue [#25](https://github.com/donghyunlee-dev/meeting-automation/issues/25).

## 범위

- Session 및 현재 허용된 처리 시작 조건 검증
- API-007의 첫 durable Chunk 저장 후 `CREATED → UPLOADING` 전이와 처리 이후 업로드 차단
- `expectedChunks`, `durationMs`, `mimeType`, `Idempotency-Key` 검증
- 저장된 Chunk sequence 전체성/연속성, 개수/크기 및 duration 상한 검증
- 비동기 Processing 작업 생성과 동일 요청 멱등 재응답
- 공통 오류 envelope 및 API-010에서 조회 가능한 Session/processing 기본값

## 비범위

Audio 조립, STT/diarization/Minutes 실행(`TASK-006.*`), API-008 status 구현, API-007의 기존 raw upload 검증·저장 계약 변경, Frontend 종료·업로드 통합(`TASK-005.06/005.08`), 재시작 이후 memory-only Session/작업 복구는 포함하지 않는다. API-007은 첫 Chunk 저장 시 상태 전이를 추가하고 `CREATED`/`UPLOADING` 외 상태의 업로드를 거절한다.

## HTTP 계약

```http
POST /api/v1/meeting-sessions/{sessionId}/process
Idempotency-Key: <non-empty request identifier>
Content-Type: application/json
```

```json
{"expectedChunks":180,"durationMs":2700000,"mimeType":"audio/webm"}
```

필드 조건: `expectedChunks`는 1 이상의 정수, `durationMs`는 1부터 API-001 전역 `recording.maxMeetingDurationMinutes × 60,000` 이하, `mimeType`은 Session `uploadPolicy.acceptedMimeTypes` 중 하나다. `Idempotency-Key`는 필수다. API-006의 Session `uploadPolicy`에는 최대 회의 시간이 없으므로 API-009는 Session별 duration snapshot을 추가하지 않는다.

Backend는 저장소의 실제 sequence가 정확히 `0..expectedChunks-1`인지 확인한다. 누락·추가·중복·개수 불일치는 HTTP 409 `AUDIO_CHUNKS_INCOMPLETE`이며 processing 작업은 생성하지 않는다. `durationMs`와 sequence count를 상관 검증하지 않는다. API-009 계약은 duration 상한과 저장된 sequence 연속성을 각각 요구하며, 두 값의 허용 오차는 정의하지 않는다. 본 Task는 sequence 완전성 및 total duration 상한을 보장한다.

처리를 시작하는 새 요청은 Session 상태가 `UPLOADING`일 때만 허용한다. API-007의 첫 Chunk가 durable하게 저장된 뒤 `CREATED → UPLOADING`으로 전이하며, 이후 Chunk 업로드는 상태를 유지한다. 입력 검증 또는 저장 실패는 상태를 바꾸지 않는다. 처리가 시작된 Session의 새 Chunk 업로드와 새 처리 시작 요청은 409 `SESSION_STATE_CONFLICT`다. 업로드 저장과 처리 시작의 sequence 검증/상태 전이는 Session별로 직렬화한다. 따라서 업로드는 처리 시작 전에 완전히 반영되어 검증되거나, 처리 시작 뒤에는 거절되어야 하며, 처리 시작 후에 늦게 저장되는 Chunk가 없어야 한다. Idempotency-Key 조회/비교는 상태 검사 전에 한다. 이미 접수된 동일 key/payload 재시도는 기존 처리 결과를 반환하고, 기존 key의 다른 payload는 `IDEMPOTENCY_KEY_CONFLICT`다.

- Session이 없으면 404 `SESSION_NOT_FOUND`.
- 잘못된 필드, 빈/누락 key, 허용되지 않은 MIME, duration/chunk count 범위 오류는 400 `VALIDATION_FAILED`.
- Chunk sequence가 불완전하면 409 `AUDIO_CHUNKS_INCOMPLETE`.
- 같은 Session/key/payload 재요청은 같은 processing 작업을 반환하고 작업을 중복 enqueue하지 않는다. 같은 key의 다른 payload는 409 `IDEMPOTENCY_KEY_CONFLICT`.
- 새 처리 요청의 Session 상태가 `UPLOADING`이 아니면 409 `SESSION_STATE_CONFLICT`; 기존 Idempotency-Key/payload 재시도는 기존 작업을 반환한다.
- 새 작업은 HTTP 202와 `{sessionId,status:"PROCESSING",stage:"AUDIO_ASSEMBLY"}`를 반환한다. Session과 idempotency/task record는 기존 architecture 원칙에 따라 memory-only다.

## 수용 기준

- 유효하고 연속된 전체 Chunk와 유효한 request는 단일 비동기 작업으로 202 응답한다.
- 빈 업로드, 누락·추가 sequence, expected count 불일치는 409이며 작업을 만들지 않는다.
- duration이 0/전역 `maxMeetingDurationMinutes` 초과, 비허용 MIME, 잘못된 request/key는 400이다. duration과 chunk count 간 별도 상관 검증은 하지 않는다.
- 같은 key/payload 재호출은 기존 작업/response를 재사용하고 key/payload 충돌은 409다.
- 저장소 또는 queue 오류는 공통 오류로 안전하게 반환하고 Audio/Secret/내부 예외를 노출하지 않는다.
- API 시작 전 Frontend pending이 없다는 가정과 무관하게 Backend가 실제 수신 목록을 재검증한다.
- 첫 Chunk가 성공 저장된 경우에만 Session이 `CREATED → UPLOADING`으로 전이하며, 실패한 업로드는 상태를 바꾸지 않는다.
- `CREATED` 상태의 새 API-009 요청은 409이며, 동일 처리 요청 재시도는 `PROCESSING` 상태에서도 기존 작업을 반환한다.
- API-007은 `CREATED`/`UPLOADING` 상태에서만 Chunk를 받고, `PROCESSING` 상태에서는 업로드를 거절한다.
- 업로드와 처리 시작이 경쟁하면 Session별 직렬화로 sequence 검증/처리 전이/저장 순서를 보장하며, `PROCESSING` 전이 이후 durable하게 추가되는 Chunk가 없어야 한다.

## 결정 및 전제

전처리 pipeline 실행은 별도 TASK-006에서 수행한다. API-009는 요청 검증 후 처리 작업/기본 `AUDIO_ASSEMBLY` stage만 개시하며 전체 pipeline 완료를 기다리지 않는다. duration 제한은 API-001 전역 `MAX_MEETING_DURATION_MINUTES` 설정을 사용하고, duration과 sequence count 사이의 미정의 tolerance를 만들지 않는다. Backend는 녹음 중 상태를 별도 API로 저장하지 않는다. 첫 Chunk의 성공 저장이 서버 측 업로드 시작 신호이며 `CREATED → UPLOADING` 전이를 일으킨다. API-009는 `UPLOADING`에서만 새 처리 작업을 시작한다. 동일 key/payload 재시도는 Session이 `PROCESSING`이어도 기존 작업을 반환한다.

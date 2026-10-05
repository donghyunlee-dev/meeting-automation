# Audio Processing 시작 API

## 목표

API-009 `POST /api/v1/meeting-sessions/{sessionId}/process`를 구현해 모든 Audio Chunk가 수신된 Session의 처리를 멱등하게 시작한다. PRD v1.7.0 (2026-10-05), `FR-006`, `API-009`, `TASK-005.07`을 구체화한다. Issue [#25](https://github.com/donghyunlee-dev/meeting-automation/issues/25).

## 범위

- Session 및 현재 허용된 처리 시작 조건 검증
- `expectedChunks`, `durationMs`, `mimeType`, `Idempotency-Key` 검증
- 저장된 Chunk sequence 전체성/연속성, 개수/크기 및 duration 상한 검증
- 비동기 Processing 작업 생성과 동일 요청 멱등 재응답
- 공통 오류 envelope 및 API-010에서 조회 가능한 Session/processing 기본값

## 비범위

Audio 조립, STT/diarization/Minutes 실행(`TASK-006.*`), API-007/008 upload/status 구현, Frontend 종료·업로드 통합(`TASK-005.06/005.08`), 재시작 이후 memory-only Session/작업 복구는 포함하지 않는다.

## HTTP 계약

```http
POST /api/v1/meeting-sessions/{sessionId}/process
Idempotency-Key: <non-empty request identifier>
Content-Type: application/json
```

```json
{"expectedChunks":180,"durationMs":2700000,"mimeType":"audio/webm"}
```

필드 조건: `expectedChunks`는 1 이상의 정수, `durationMs`는 1부터 Session `uploadPolicy.maxMeetingDurationMinutes × 60,000` 이하, `mimeType`은 Session `acceptedMimeTypes` 중 하나다. `Idempotency-Key`는 필수다.

Backend는 저장소의 실제 sequence가 정확히 `0..expectedChunks-1`인지 확인한다. 누락·추가·중복·개수 불일치는 HTTP 409 `AUDIO_CHUNKS_INCOMPLETE`이며 processing 작업은 생성하지 않는다. `durationMs`를 기반으로 policy chunk duration과 비교해 설명 가능한 허용 범위를 가진 실제 sequence count가 성립하는지 검증하며, API-009 명세가 정하지 않은 엄격한 실제 녹음 시간 대조는 요구하지 않는다. 본 Task는 sequence 완전성 및 total duration 상한을 보장한다.

- Session이 없으면 404 `SESSION_NOT_FOUND`.
- 잘못된 필드, 빈/누락 key, 허용되지 않은 MIME, duration/chunk count 범위 오류는 400 `VALIDATION_FAILED`.
- Chunk sequence가 불완전하면 409 `AUDIO_CHUNKS_INCOMPLETE`.
- 같은 Session/key/payload 재요청은 같은 processing 작업을 반환하고 작업을 중복 enqueue하지 않는다. 같은 key의 다른 payload는 409 `IDEMPOTENCY_KEY_CONFLICT`.
- Session 상태가 처리를 시작할 수 없는 상태이면 409 `SESSION_STATE_CONFLICT`.
- 새 작업은 HTTP 202와 `{sessionId,status:"PROCESSING",stage:"AUDIO_ASSEMBLY"}`를 반환한다. Session과 idempotency/task record는 기존 architecture 원칙에 따라 memory-only다.

## 수용 기준

- 유효하고 연속된 전체 Chunk와 유효한 request는 단일 비동기 작업으로 202 응답한다.
- 빈 업로드, 누락·추가 sequence, expected count 불일치는 409이며 작업을 만들지 않는다.
- duration이 0/60분 초과, 비허용 MIME, 잘못된 request/key는 400이다.
- 같은 key/payload 재호출은 기존 작업/response를 재사용하고 key/payload 충돌은 409다.
- 저장소 또는 queue 오류는 공통 오류로 안전하게 반환하고 Audio/Secret/내부 예외를 노출하지 않는다.
- API 시작 전 Frontend pending이 없다는 가정과 무관하게 Backend가 실제 수신 목록을 재검증한다.

## 결정 및 전제

전처리 pipeline 실행은 별도 TASK-006에서 수행한다. API-009는 요청 검증 후 처리 작업/기본 `AUDIO_ASSEMBLY` stage만 개시하며 전체 pipeline 완료를 기다리지 않는다. Session/status 전이는 architecture 상태도 및 application boundary에서 한 번만 수행한다.

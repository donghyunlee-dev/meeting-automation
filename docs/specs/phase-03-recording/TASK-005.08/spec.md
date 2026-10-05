# 녹음 종료 후 Processing 시작 연결

## 목표

SCR-004 종료 확인을 recorder 종료, final Chunk 저장, 누락 업로드 완료 및 API-009 처리 시작에 연결한다. 유효한 202 응답이 확인된 뒤 Processing 화면으로 이동한다. PRD v1.7.0 (2026-10-05), `FR-006`, `SCR-004`, `API-009`, `TASK-005.08`을 구체화한다. Issue [#26](https://github.com/donghyunlee-dev/meeting-automation/issues/26).

## 범위

- 종료 확인 action 1회 처리 및 TASK-005.02 recorder stop 완료 대기
- final `dataavailable` 저장 완료를 포함해 TASK-005.06 전체 pending 업로드 완료 대기
- `expectedChunks`, 최종 `durationMs`, 선택 `mimeType`으로 API-009 request 작성
- 같은 종료 Session의 중복 클릭/응답 유실에 stable Idempotency-Key 재사용
- 202 확인 후 `/meetings/{sessionId}/processing` 이동
- 업로드 실패/API 오류 중복 방지, 안전한 재시도 안내

## 비범위

MediaRecorder/Chunk 저장/PUT 재시도(`TASK-005.02/.03/.06`), API-009 Backend 검증(`TASK-005.07`), Audio assembly/AI 처리(`TASK-006`), Processing 진행 polling/display(`TASK-006.07`), Backend Session 영속화는 포함하지 않는다.

## 종료 오케스트레이션

1. SCR-004에서 종료를 확정하면 확인 버튼을 잠그고 controller의 `onConfirmEnd`를 한 번 호출한다.
2. Recorder stop 완료 callback을 기다린다. TASK-005.02가 마지막 `dataavailable`을 보낸 뒤 `stop` 완료를 통지하며 TASK-005.03 append queue는 final Chunk 영속화까지 완료해야 한다.
3. TASK-005.06 uploader를 flush/reconcile한다. API-008에 remote-confirm됐거나 API-007 200 ACK 후 local pending이 0일 때만 진행한다. 업로드 실패/미전송 Chunk가 있으면 API-009를 호출하지 않는다.
4. request payload는 `expectedChunks = nextSequence`, recorder final `durationMs`, Session upload policy에서 사용한 `mimeType`이다. 중간 paused duration은 recorder가 제공하는 누적 녹음 시간에서 제외한다.
5. Session별 고정 `Idempotency-Key`를 종료 제출 시 생성해 API-009가 결과를 확정할 때까지 보존하고, 동일 payload retry에 재사용한다. 빠른 중복 클릭은 한 request promise에 합친다.
6. HTTP 202와 response `sessionId` 일치가 확인되면 `/meetings/{sessionId}/processing`으로 이동한다. 네트워크 timeout으로 응답이 불확실하면 같은 payload/key로 재호출해 기존 작업을 받는다.
7. API-009 `AUDIO_CHUNKS_INCOMPLETE`이면 uploader reconcile을 다시 실행하고 누락이 없어졌을 때 사용자가 `처리 시작 재시도`할 수 있게 한다. 4xx validation/state 오류는 자동 재시도하지 않고 안전한 설명과 지원 가능한 action을 표시한다.

종료 이후 녹음은 다시 시작하지 않는다. 업로드 pending은 TASK-005.06의 수동 재시도로 완료할 수 있고 API-009가 실패해도 종료된 Blob/Session 데이터를 지우지 않는다. Processing route 이동 전 미전송 Chunk가 남지 않아야 한다.

## 수용 기준

- 종료 확정, stop completion, final Chunk append, 모든 upload ACK, API-009, Processing navigation 순서가 보장된다.
- 종료 confirmation 및 API 호출은 중복 입력에도 한 번만 진행되며 retry 시 같은 Idempotency-Key/payload를 사용한다.
- final Chunk 저장/upload 실패 또는 pending 존재 시 API-009를 호출하지 않고 복구 action/진행 결과를 표시한다.
- expectedChunks/duration/mimeType은 실제 Session 녹음과 일치한다.
- 202와 route Session ID가 일치할 때만 Processing으로 이동한다.
- 네트워크 불확실/5xx는 같은 key로 retry 가능하고, 4xx 오류는 반복 자동 호출하지 않는다.
- 어떤 실패 경로도 사용자의 IndexedDB pending Audio를 자동 삭제하지 않는다.

## 결정 및 전제

종료 action과 업로드 flush는 기존 tasks의 책임을 재사용하고 이 Task는 orchestration boundary다. Processing 화면 자체와 API-010 polling은 다음 Processing UI task에 둔다.

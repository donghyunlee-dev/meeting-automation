# Audio Chunk 임시 저장과 순번 관리

## 목표

TASK-005.02가 생성한 MediaRecorder Blob을 Session별로 IndexedDB에 임시 저장하고, 기록 순번을 원자적으로 부여하며 브라우저 재시작 뒤 미전송 Chunk를 복구할 수 있게 한다. PRD v1.7.0 (2026-10-05), `FR-005`, `DEC-020`, `TASK-005.03`을 구체화한다. Issue [#21](https://github.com/donghyunlee-dev/meeting-automation/issues/21).

## 범위

- IndexedDB 기반의 브라우저 로컬 임시 저장소와 schema/version 관리
- Session ID 및 0부터 시작하는 sequence를 이용한 Chunk 식별
- Blob과 MIME, 크기, 기록 시각, 전송 상태 및 Session 메타데이터 저장
- 동시/연속 Blob callback에도 sequence 중복 없이 원자적 추가
- 재시작 시 Session별 미전송 Chunk 및 다음 sequence 복구
- 후속 업로드 작업이 사용할 조회·ACK 삭제·보존 후보 API
- 저장소 용량 부족/IndexedDB 실패를 호출부에 명시적으로 전달

## 비범위

HTTP 업로드·재시도·서버 ACK 대조는 `TASK-005.06`, API 수신 검증·서버 저장은 `TASK-005.04/005.05` 소유다. 사용자가 중단된 녹음을 어떤 화면에서 재개할지 안내하는 UI도 본 Task에서는 구현하지 않고 복구 후보 조회 계약만 제공한다. Backend 재시작 후 memory-only Session을 되살리거나 Audio를 장기 보관하지 않는다.

## 저장 계약

- IndexedDB는 같은 origin의 브라우저 재시작에도 유지되는 임시 Blob 저장소로 쓴다. `localStorage`에는 Audio Blob을 넣지 않는다.
- `chunks` object store의 복합 key는 `[sessionId, sequence]`이며 unique index `(sessionId, chunkId)`를 둔다. `sessions` store에는 Session 식별자, MIME, 다음 sequence, 총 녹음 시간, 마지막 변경 시각 등 복구에 필요한 비밀 없는 메타데이터만 둔다.
- append는 IndexedDB read-write transaction 하나에서 `(sessionId, chunkId)` 중복을 먼저 확인한다. 이미 저장된 ID면 기존 sequence를 반환한다. 새 ID면 `nextSequence`를 읽고 Chunk와 증가한 metadata를 함께 commit한다. transaction abort 시 성공을 반환하지 않고 sequence도 소비되지 않은 것으로 처리한다.
- TASK-005.02가 준 `{chunkId,blob,mimeType,recordedAtMs}`를 저장하고 sequence를 부여한다. `chunkId`는 동일 이벤트 저장 재시도에서 유지되는 식별자다. Blob 크기는 API-006 `uploadPolicy.maxChunkBytes` 이하인지 확인하며 0바이트 Blob은 저장하지 않는다. 위반 데이터는 명시적 오류로 반환한다.
- 처음 쓰는 Session에 대한 metadata는 명시적으로 초기화한다. 동일 callback 재진입은 기존 결과를 돌려주고 sequence를 추가 배정하지 않는다. 단순히 같은 바이트인 서로 다른 시간 구간은 서로 다른 Chunk로 보존한다.
- 복구 query는 Session별 sequence 오름차순 Chunk와 `nextSequence`를 반환한다. ACK된 Chunk만 `TASK-005.06`이 호출하는 acknowledge/delete API로 제거하고, 남은 데이터가 없을 때 Session metadata도 정리한다.
- 브라우저 재시작 복구는 동일 origin의 IndexedDB와 기존 Session ID가 유효한 동안만 보장한다. Backend 재시작으로 Session이 사라졌거나 브라우저 저장공간이 사용자의 브라우저 정책에 의해 삭제된 경우 복구를 보장하지 않는다. 후속 업로드에서 유효 Session이 아님을 확인하면 Chunk를 자동 폐기하지 않고 명시적인 복구 불가 결과로 남겨 호출부가 안내할 수 있게 한다.
- quota 초과 및 IndexedDB open/transaction 실패 시 저장 실패를 명시하고 성공 ACK를 반환하지 않는다. 호출부는 실패한 오디오를 잃지 않도록 새 녹음을 안전하게 멈추거나 사용자 조치를 요구한다.

## 수용 기준

- Chunk는 Session별 0-based 연속 sequence로 저장되고 같은 sequence 중복/transaction abort가 생기지 않는다.
- 저장 성공 응답은 Blob과 metadata가 같은 transaction으로 영속화된 뒤에만 반환된다.
- 브라우저 reload/restart 후 미전송 Chunk와 next sequence를 순서대로 복구한다.
- ACK된 sequence만 삭제되며 삭제 전 실패/재호출은 다른 Chunk를 손상시키지 않는다.
- 0바이트, 최대 크기 초과, quota 초과 및 IndexedDB 오류는 구별 가능한 안전 오류가 된다.
- 남은 미전송 Chunk가 없으면 해당 Session metadata를 정리하고 Audio를 목적 기간보다 오래 보유하지 않는다.
- Backend Session 재시작 후 복구 불가 상태를 정상 복구 성공으로 표시하지 않는다.

## 결정

브라우저 재시작을 견디는 저장 방식은 IndexedDB로 정한다. Session별 데이터는 ACK 전까지 보존하고 ACK 후 즉시 제거한다. 실제 브라우저의 강제 종료 및 저장공간 quota 동작은 자동화 저장소 테스트와 별도 브라우저 QA로 나눈다.

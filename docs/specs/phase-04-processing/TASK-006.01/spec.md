# Audio Chunk 조립과 임시 파일 정리

## 목표

API-009가 수락한 Processing 작업에서 저장된 Chunk를 sequence 순서로 조립하고 검증된 assembled Audio를 후속 STT 단계에 전달한다. PRD v1.7.0 (2026-10-05), `DEC-020`, `FR-007`, `NFR-014`, `API-009`, `TASK-006.01`을 구체화한다. Issue [#27](https://github.com/donghyunlee-dev/meeting-automation/issues/27).

## 범위

- Session/job의 `expectedChunks`, `durationMs`, `mimeType` 기준 확인
- `0..expectedChunks-1` Chunk를 하나씩 읽고 순서대로 임시 assembled Audio에 stream append
- 저장된 length/checksum 검증 및 실제 assembled 길이 검증
- 성공 output을 원자적으로 publish하고 후속 TASK-006.02에 임시 참조 전달
- 조립 성공 후 source Chunk temp file 정리
- 실패 시 partial assembled file 제거, 원본 Chunk를 보존해 같은 job의 assembly retry 가능
- `sessionId`, `traceId`, stage, outcome을 포함하되 Audio/경로/Secret은 제외하는 구조화 로그

## 비범위

STT/diarization/Minutes 호출, API-009의 요청 검증 및 job 생성, 임시 Audio 최종 보유기간/terminal pipeline cleanup(`TASK-017.02`), 영구 저장소, 공개 다운로드 API는 포함하지 않는다.

## 조립 계약

- 입력은 처리 job에 고정된 `sessionId`, `expectedChunks`, `durationMs`, `mimeType`이다. Session upload policy의 60분 상한과 허용 MIME은 API-009에서 이미 확인됐지만 worker도 job 입력을 신뢰 경계로 재확인한다.
- expected chunk는 정확히 `sequence=0..expectedChunks-1` 한 개씩 있어야 한다. 하나라도 없거나 추가/중복/metadata 손상 발견 시 조립을 실패시키고 후속 STT를 호출하지 않는다.
- 모든 Chunk MIME은 job `mimeType`과 같아야 한다. API-007에서 검증한 저장 length와 SHA-256을 읽으면서 다시 계산해 저장 손상/불일치를 감지한다.
- source chunk는 파일 하나씩 순서대로 읽어 output stream에 복사한다. 전체 Audio를 heap에 올리지 않는다. 합산 byte 수는 Chunk count와 Session `maxChunkBytes`로 계산 가능한 상한을 넘지 않아야 한다.
- assembled Audio는 `TEMP_AUDIO_DIR` 하위 서버 생성된 Session/job 작업 디렉터리에 `.{jobId}.part`로 생성한다. 모든 sequence 복사와 검증이 끝난 뒤 같은 파일시스템에서 최종 임시 이름으로 atomic move하고 job metadata에 참조를 publish한다. 사용자가 전달한 Session ID를 파일 경로에 직접 붙이지 않는다.
- 성공 commit 뒤 원본 Chunk 임시 파일을 제거한다. assembled Audio는 후속 STT consumer가 사용하는 동안 존재해야 하므로 즉시 삭제하지 않고 `TASK-017.02` terminal cleanup에 넘긴다.
- 조립 도중 어떤 오류든 `.part`/부분 산출물 및 임시 작업 파일만 삭제한다. source Chunk는 보존해 같은 job 재시도에 사용한다. 최종 output publish 후 삭제 단계가 실패하면 duplicate assembly를 만들지 않도록 기존 job output을 유지하고 cleanup failure를 기록해 정리 작업으로 넘긴다.
- 재시도에서 이미 검증된 job output이 존재하면 checksum/size metadata를 검증해 기존 참조를 반환하고 재조립/중복 output을 만들지 않는다.
- 처리 결과와 로그에는 `sessionId`, `traceId`, stage, 오류 코드 및 안전한 수치만 남긴다. Audio bytes, transcript, Secret, 절대 경로는 포함하지 않는다.

## 수용 기준

- 정상 Chunk는 sequence 순서로 조립되어 후속 consumer에게 올바른 MIME/byte metadata와 임시 참조가 전달된다.
- 누락/추가/손상/혼합 MIME Chunk는 안전하게 실패하고 STT에 전달되지 않는다.
- 실패 시 partial assembled temp 파일은 삭제되고 원본 Chunk가 재시도용으로 남는다.
- 성공 시 output은 완전 검증/atomic publish 후에만 보이고 source Chunk는 제거된다.
- assembled Audio는 후속 STT 소비 및 terminal cleanup 전까지 보존되고 성공/실패 terminal 경로 정리는 `TASK-017.02` 계약을 따른다.
- 메모리 사용은 전체 Audio 크기에 비례하지 않으며 로그에 Audio/경로/Secret이 노출되지 않는다.
- 동일 job 재실행은 하나의 assembled output 참조를 재사용한다.

## 결정 및 전제

조립 성공은 처리 완료가 아니다. 따라서 성공 뒤 STT 입력인 assembled Audio를 지우지 않으며 `TASK-017.02`가 terminal pipeline success/failure/interruption cleanup을 담당한다. 조립 실패 때만 partial output을 지우고 입력 Chunk를 보존한다.

# Audio Chunk 업로드 API

## 목표

API-007 `PUT /api/v1/meeting-sessions/{sessionId}/audio/chunks/{sequence}`를 구현해 유효한 Audio Chunk를 임시 저장하고 동일 바이트 재전송에 안전한 ACK를 반환한다. PRD v1.7.0 (2026-10-05), `FR-005`, `API-007`, `TASK-005.04`를 구체화한다. Issue [#22](https://github.com/donghyunlee-dev/meeting-automation/issues/22).

## 범위

- Session 존재 확인 및 route `sessionId`/`sequence` 검증
- raw binary body, Content-Type, `X-Audio-SHA256`, `X-Audio-Byte-Length`, `Idempotency-Key` 검증
- Session의 upload policy에서 허용 MIME/maxChunkBytes 적용
- 실제 body length와 SHA-256 검증
- 임시 chunk 저장, 동일 sequence/동일 bytes 재전송 ACK, 상이 bytes 충돌
- 표준 성공·오류 envelope와 저장소 단위/Controller 검증

## 비범위

API-008 수신 상태 조회, API-009 processing 시작, 전체 Session lifecycle 변경, sequence 누락 검증, Audio 조립 및 처리 후 삭제(`TASK-006.01`), Frontend 순차 업로드/재시도(`TASK-005.06`), 영구 저장은 포함하지 않는다.

## HTTP 계약

```http
PUT /api/v1/meeting-sessions/{sessionId}/audio/chunks/{sequence}
Content-Type: audio/webm | audio/mp4
X-Audio-SHA256: <lowercase hex SHA-256 of raw body>
X-Audio-Byte-Length: <decimal byte count>
Idempotency-Key: <non-empty request identifier>
```

Body는 raw binary다. `sequence`는 0 이상의 정수이며 signed 32-bit 범위까지 허용한다. 성공은 HTTP 200 `{ "data": { "sequence": n, "received": true } }`다.

- Session이 없거나 memory store에서 사라졌으면 404 `SESSION_NOT_FOUND`.
- 음수/비정수/범위 초과 sequence, 빈 body, 허용되지 않은 MIME, 헤더 누락/형식 오류, byte length 또는 SHA-256 불일치, chunk 상한 초과는 400 `AUDIO_CHUNK_INVALID`.
- 같은 `(sessionId,sequence)`가 이미 있고 MIME/길이/SHA-256이 같으면 저장을 중복 수행하지 않고 동일 200 ACK를 반환한다. 새 idempotency key라도 같은 bytes면 멱등 ACK한다.
- 같은 `(sessionId,sequence)`의 기존 bytes가 다르면 409 `AUDIO_CHUNK_CONFLICT`; 기존 파일은 그대로 둔다.
- 다른 sequence의 요청이 동시에 와도 각각 독립 저장한다. 중복 race는 저장소의 원자적 create/unique sequence 보호로 하나의 결과만 commit하고 나머지는 저장된 checksum과 대조한다.
- 저장장치 오류는 공통 500 `INTERNAL_ERROR`로 안전하게 변환한다. 내부 경로, 원시 예외, Audio bytes/hash는 응답이나 일반 로그에 노출하지 않는다.

검증은 저장 전에 진행한다. 서버는 최대 상한을 넘는 요청 body를 무제한 메모리 버퍼링하지 않고 제한된 크기로 읽으며, 선언 Content-Length만 신뢰하지 않는다. 업로드 상한은 API-006 Session `uploadPolicy.maxChunkBytes`의 서버 값을 사용한다. 저장 위치는 설정 `TEMP_AUDIO_DIR` 아래 서버 생성 경로를 사용하고 route 값으로 경로를 직접 구성하지 않는다. 임시 파일은 sequence 단위로 원자적으로 게시해 부분 업로드가 완성 Chunk로 조회되지 않게 한다.

## 수용 기준

- 정상 raw binary upload는 Session policy 범위 안에서 임시 저장되고 API-007 success envelope를 돌려준다.
- Session 누락은 404이며 잘못된 sequence/body/header/MIME/length/checksum/size는 400이다.
- 같은 sequence와 같은 실제 bytes는 최초 저장 이후에도 idempotent ACK이며 저장 파일은 하나다.
- 같은 sequence에 다른 bytes는 409이고 기존 저장 데이터는 보존된다.
- 동시 동일 요청 race에서도 완성 Chunk 하나만 남고 일관된 ACK/conflict 결과가 나온다.
- 임시 파일 경로는 사용자 입력으로 조작할 수 없고 partial write는 완료 Chunk로 노출되지 않는다.
- 오류 응답/로그는 raw Audio, 내부 경로, 예외 원문을 포함하지 않는다.

## 결정 및 전제

Idempotency-Key는 API 공통 header contract를 만족하도록 필수이며, 중복 판정의 본체는 sequence에 저장된 실제 SHA-256/길이/MIME이다. 동일 bytes면 key 재사용 여부와 무관하게 API-007이 선언한 멱등 ACK를 따른다. 임시 파일 retention/정리 세부 정책은 DEC-020에 따라 처리 완료/실패 cleanup 작업에서 정의하며 이 API는 영구 보관하지 않는다.

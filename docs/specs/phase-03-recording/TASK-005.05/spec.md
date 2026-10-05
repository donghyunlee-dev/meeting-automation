# Audio 업로드 현황 조회 API

## 목표

API-008 `GET /api/v1/meeting-sessions/{sessionId}/audio`를 구현해 Session에 실제 저장된 Chunk sequence 전체와 Chunk 수/총 byte 수를 반환한다. PRD v1.7.0 (2026-10-05), `FR-005`, `API-008`, `TASK-005.05`를 구체화한다. Issue [#23](https://github.com/donghyunlee-dev/meeting-automation/issues/23).

## 범위

- Session 존재 확인
- 임시 저장소에서 해당 Session의 완료된 Chunk metadata 집계
- 정렬된 `receivedSequences`, `receivedChunks`, `totalBytes` 반환
- 비어 있는 업로드와 저장소 오류의 표준 envelope 처리
- API-007 임시 저장 adapter와 일관된 파일 조회 경계

## 비범위

Chunk 업로드/삭제, 누락 sequence 계산, expected chunk 수 보관, Frontend 재전송 결정, API-009 처리 시작, Audio 조립 및 lifecycle 상태 변경은 포함하지 않는다. 클라이언트가 예상 sequence 범위를 알고 있을 때 반환된 실제 수신 목록과 비교해 누락을 계산한다.

## HTTP 계약

```http
GET /api/v1/meeting-sessions/{sessionId}/audio
Accept: application/json
```

Session이 존재하면 HTTP 200 `{ "data": { "receivedSequences": [0,1,2], "receivedChunks": 3, "totalBytes": 1234567 } }`를 반환한다. `receivedSequences`는 완료·검증된 Chunk만 포함하며 오름차순 unique 정수다. `receivedChunks`는 배열 길이와 같고 `totalBytes`는 동일 Chunk들의 실제 byte length 합계다.

- Chunk가 하나도 없으면 HTTP 200 `{ "data": { "receivedSequences": [], "receivedChunks": 0, "totalBytes": 0 } }`.
- 존재하지 않거나 memory store에서 사라진 Session은 404 `SESSION_NOT_FOUND`.
- 저장소 읽기/검증 오류는 공통 500 `INTERNAL_ERROR`; 부분 파일/손상 metadata를 정상 수신 Chunk처럼 계산하지 않는다.
- sequence 정렬은 numeric ascending이다. 중복 파일명/임시 파일은 집계에서 한 Chunk로 이중 계산되지 않게 저장소의 Session+sequence key를 따른다.
- API는 별도 `missingSequences`를 반환하지 않는다. 호출부가 전체 번호 목록과 종료 시점의 expected chunk 수를 가지고 계산한다.

이 Endpoint는 Session status를 변경하지 않는다. 집계는 같은 Session 범위의 완전히 게시된 임시 Chunk만 대상으로 하며 삭제/조립은 후속 Audio lifecycle 작업 책임이다.

## 수용 기준

- 저장된 Chunk가 없으면 빈 sequence 목록과 0 count/bytes를 반환한다.
- 저장된 Chunk가 있으면 모든 sequence를 오름차순으로 반환하고 count/byte sum이 일치한다.
- API-007 업로드가 원자적으로 게시되기 전 부분 파일은 응답에 포함되지 않는다.
- Session이 없으면 404 `SESSION_NOT_FOUND`다.
- 저장소 오류는 안전한 공통 오류로 반환하고 내부 경로/Audio를 노출하지 않는다.
- 응답 조회가 Session 또는 Chunk 저장 데이터를 변경하지 않는다.

## 결정 및 전제

API-008은 클라이언트가 재전송 여부를 판단할 수 있도록 실제 수신 sequence 전체를 제공한다. `missingSequences`를 별도 계약으로 추가하지 않아 API-009의 종료/expected count 정보를 복제하지 않는다.

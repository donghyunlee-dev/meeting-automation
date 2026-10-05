# Audio Chunk API 검증 계획

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| API-007-01 | 유효 Session/policy와 binary, 허용 MIME, 정확한 길이/hash | HTTP 200 `{sequence,received:true}`, chunk 1건 | Controller/service/temp adapter test |
| API-007-02 | 존재하지 않는 Session | 404 `SESSION_NOT_FOUND`, 파일 없음 | service/controller test |
| API-007-03 | sequence 음수/문자열/허용 숫자 범위 초과 | 400 `AUDIO_CHUNK_INVALID`, 파일 없음 | route validation test |
| API-007-04 | body 누락/빈 값 또는 필수 header 누락 | 400 `AUDIO_CHUNK_INVALID` | request validation test |
| API-007-05 | policy에 없는 MIME | 400 `AUDIO_CHUNK_INVALID` | policy validation test |
| API-007-06 | 선언 Content-Length 불일치 | 400 `AUDIO_CHUNK_INVALID`, 파일 없음 | body length test |
| API-007-07 | SHA-256 누락/형식 오류/실제값 불일치 | 400 `AUDIO_CHUNK_INVALID`, 파일 없음 | digest verification test |
| API-007-08 | maxChunkBytes 초과 | 제한된 body read 뒤 400, 임시/완성 파일 없음 | bounded streaming test |
| API-007-09 | 저장된 sequence에 같은 bytes/MIME 재업로드, 같은/다른 Idempotency-Key | 동일 HTTP 200 ACK, 저장 파일 하나 | idempotency test |
| API-007-10 | 같은 Session/sequence에 다른 bytes | 409 `AUDIO_CHUNK_CONFLICT`, 기존 파일 checksum 유지 | conflict test |
| API-007-11 | 동일 요청 동시 도착 | 완료 파일 하나, 호출별 ACK 결과가 결정적 | repository concurrency test |
| API-007-12 | 임시 저장 디스크 오류 | 500 `INTERNAL_ERROR`, 오류 envelope에 내부 경로 미포함 | adapter/error mapping test |
| API-007-13 | 임의 Session ID로 경로 조작 시도 | 설정된 temp root 밖에 파일 생성 없음 | path safety test |
| API-007-14 | 오류 상황의 로그/응답 검사 | raw Audio, SHA-256, 내부 예외/경로 비노출 | log/error assertion |

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행하고 결과를 기록한다.

## 수동/통합 QA

- 실제 API에 제한 크기 내 binary fixture를 전송하고 저장 파일의 byte 수와 SHA-256을 대조한다.
- 같은 sequence/bytes 재전송은 200, 다른 bytes는 409인지 확인한다.
- 최대 크기보다 큰 body 전송 시 메모리 급증 없이 거절되고 임시 파일이 남지 않는지 확인한다.
- 구성된 `TEMP_AUDIO_DIR` 하위에만 파일이 생기며 API 응답/일반 로그에 Audio bytes/hash/path가 출력되지 않는지 확인한다.

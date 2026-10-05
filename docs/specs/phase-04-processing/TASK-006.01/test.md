# Audio 조립 검증 계획

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| ASSEMBLY-01 | 연속 sequence 0..N-1, 동일 MIME, 유효 checksum | sequence 순서의 assembled Audio 및 metadata 1건 publish | usecase/storage integration test |
| ASSEMBLY-02 | 조립 결과의 bytes fixture와 기대 concat | byte-for-byte 동일 output | binary fixture test |
| ASSEMBLY-03 | 중간 sequence 누락 | assembly 실패, downstream handoff 없음, partial output 제거 | validation/cleanup test |
| ASSEMBLY-04 | expected 범위 밖 추가/중복 sequence | 안전한 assembly 실패, output 없음 | metadata validation test |
| ASSEMBLY-05 | source bytes의 SHA-256 또는 length 불일치 | 실패 처리, partial 삭제, source Chunk 보존 | integrity test |
| ASSEMBLY-06 | Chunk MIME 하나가 job mimeType과 다름 | assembly 거절, STT 미호출 | MIME validation test |
| ASSEMBLY-07 | temp write/atomic move 도중 오류 | `.part`/partial 제거, source Chunk 보존 | filesystem failure injection |
| ASSEMBLY-08 | output atomic publish 성공 뒤 원본 Chunk 정리 | 검증 output은 유지, source sequence files 제거 | cleanup success test |
| ASSEMBLY-09 | source cleanup 일부 실패 | 성공 output 유지, cleanup 실패 관측, 중복 assembly 없음 | cleanup retry test |
| ASSEMBLY-10 | 동일 job assembly 재실행 | 기존 검증 output reference 재사용, 두 번째 output 없음 | idempotency test |
| ASSEMBLY-11 | 후속 STT 처리 전 terminal cleanup 호출 없음 | assembled temp output 보존 | lifecycle boundary test |
| ASSEMBLY-12 | 구조화 로그 capture | `sessionId`,`traceId`,`stage` 포함, Audio/절대 경로/Secret 제외 | logging assertion |

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행하고 결과를 기록한다.

## 수동/통합 QA

- API-007 업로드 fixture 세 개를 조립해 원본 순서와 assembled bytes를 대조한다.
- 누락/손상 fixture로 처리 시작 후 실패 결과, partial 삭제와 source 보존을 확인한다.
- 성공 뒤 STT test double이 임시 output을 읽고 terminal cleanup 전까지 파일이 유지되는지 확인한다.
- 성공/실패/중복 실행 로그와 `TEMP_AUDIO_DIR` 밖 경로 접근 여부를 확인한다.

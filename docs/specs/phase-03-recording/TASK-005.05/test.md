# Audio 업로드 현황 API 검증 계획

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| API-008-01 | 존재하는 Session, 저장 Chunk 없음 | HTTP 200 `receivedSequences=[]`, count/bytes 0 | Controller/service test |
| API-008-02 | sequence 0/2/10의 완료된 Chunk | `[0,2,10]`, count 3, 실제 byte length 합계 | repository query test |
| API-008-03 | 저장소에 중복 임시/완료 metadata fixture | sequence 한 번만 표시하고 실제 객체 기준 bytes 집계 | adapter consistency test |
| API-008-04 | API-007 transaction 도중 미완료 partial file 존재 | partial record 제외 | temp adapter integration test |
| API-008-05 | 존재하지 않거나 사라진 Session | HTTP 404 `SESSION_NOT_FOUND` | service/controller test |
| API-008-06 | 임시 저장소 읽기 실패 | HTTP 500 공통 `INTERNAL_ERROR`; 경로/Audio 노출 없음 | error mapping/log test |
| API-008-07 | 정상 GET 반복 | 매번 같은 집계, 저장소 변경 없음 | read-only query test |

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행하고 결과를 기록한다.

## 수동/통합 QA

- API-007로 sequence 0과 2를 올린 뒤 API-008이 `[0,2]`, 올바른 count/bytes를 반환하는지 확인한다.
- 업로드 전/완료 후 API-008을 조회해 partial 파일이 수신된 것으로 표시되지 않는지 확인한다.
- 빈 업로드 및 없는 Session 응답의 status/error envelope를 확인한다.
- 연속 GET이 임시 파일이나 Session 데이터를 변경하지 않는지 확인한다.

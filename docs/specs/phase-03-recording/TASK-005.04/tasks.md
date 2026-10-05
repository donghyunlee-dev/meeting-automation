# Audio Chunk API 작업 항목

1. API-007, 공통 오류 envelope, Session Store와 upload policy port를 확인한다. 완료 증거: adapter와 application contract의 파일 경로가 정해진다.
2. 요청 command/header parser와 표준 오류 case를 정의한다. 완료 증거: controller가 raw Audio를 domain model에 직접 결합하지 않는다.
3. 정상/오류/경계/동시성 Controller·service 테스트를 먼저 작성해 실패를 확인한다.
4. Session 존재, sequence/header/MIME/길이/hash/maxChunkBytes 검증을 구현한다. 완료 증거: 검증 불일치가 저장 전에 안전 오류로 반환된다.
5. 임시 chunk 저장 port와 원자적 adapter를 구현한다. 완료 증거: partial file은 게시되지 않고 sequence 충돌이 checksum 비교로 결정된다.
6. 동일 bytes 재전송 ACK 및 상이 bytes 충돌을 구현한다. 완료 증거: 저장 한 건, 기존 bytes 불변, 적절한 response가 검증된다.
7. 로그 마스킹과 오류 envelope를 확인하고 API integration을 추가한다. 완료 증거: Audio/hash/path/exception 원문 비노출이 확인된다.
8. `./gradlew test`, `./gradlew clean build`를 실행하고 변경 파일을 리뷰한다. 완료 증거: 테스트 결과와 저장 동시성 근거가 기록된다.

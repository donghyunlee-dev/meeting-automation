# Audio 업로드 현황 API 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `FR-005`, `API-008`, `TASK-005.05`
- 선행 TASK-005.04 Issue [#22](https://github.com/donghyunlee-dev/meeting-automation/issues/22): Session별 atomic temp chunk 저장
- 본 TASK Issue [#23](https://github.com/donghyunlee-dev/meeting-automation/issues/23)
- 후속 TASK-005.06: 반환된 수신 sequence를 expected queue와 대조해 누락 Chunk 재전송
- 후속 TASK-005.07: API-009에서 expectedChunks와 연속성 검증

## 변경 경계

- Backend Controller: Session ID path와 API response envelope
- Application query: Session 존재 확인 및 Audio metadata 조회
- Audio chunk repository: 완료된 저장물의 sequence/size 집계 query
- API-007 temp storage adapter 재사용; binary bytes를 메모리에 다시 읽지 않고 metadata만 집계
- Upload, delete, processing, Frontend 변경 없음

## 구현 순서

1. API-007 저장소의 canonical Session+sequence key, atomic publish 경계와 API-008 response DTO를 확인한다. 결과: 조회 범위가 저장소 key와 일치한다.
2. Empty/ordered/bytes/session missing/storage error query 테스트를 먼저 작성한다. 결과: 모든 응답 변형이 구현 전에 고정된다.
3. Session existence와 metadata query port/application handler를 구현한다. 결과: 없음과 빈 업로드가 서로 구별된다.
4. 저장소가 완료 파일 metadata만 읽어 sequence와 bytes 합계를 반환하게 한다. 결과: Audio body 전체를 메모리로 읽지 않는다.
5. 정렬/중복 방지/response envelope/controller error mapping을 연결한다. 결과: 명세된 JSON response가 반환된다.
6. API-007 adapter 통합, 공통 error regression, 빌드를 검증한다. 결과: test evidence가 기록된다.

## 검증 명령

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행한다. API-007 upload와 API-008 조회의 연계는 temp adapter integration test로 확인한다.

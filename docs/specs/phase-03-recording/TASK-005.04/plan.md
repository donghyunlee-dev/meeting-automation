# Audio Chunk 업로드 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `FR-005`, `API-007`, `TASK-005.04`
- 선행 TASK-004.04 Issue [#17](https://github.com/donghyunlee-dev/meeting-automation/issues/17): memory-only Session 및 Session upload policy
- 본 TASK Issue [#22](https://github.com/donghyunlee-dev/meeting-automation/issues/22)
- Frontend 연계 TASK-005.03/005.06: 순번 Blob 전송 및 retry
- 후속 TASK-005.05: 수신 sequence 조회, TASK-006.01: 조립/임시 파일 cleanup

## 변경 경계

- Backend Controller: raw request body와 header/path를 application command로 변환
- Application service: Session 조회, policy 조회, 검증 순서 및 idempotent 저장 조정
- Audio chunk repository/adapter: Session/sequence별 atomic temporary write, checksum metadata 대조
- 공통 오류 handler: API 오류 코드를 공통 envelope/status에 매핑
- API-008/009 또는 Frontend 변경은 없다.

기존 Backend domain/application/adapter 분리를 따른다. Controller는 직접 파일 경로를 만들거나 body를 무제한 buffer하지 않고, 저장 포트는 임시 파일 구현을 감춘다. API-006 Session Store가 제공하는 policy를 사용해 client가 크기/MIME 상한을 지정하지 못하게 한다.

## 구현 순서

1. API 공통 header/body/error 계약, Session store/policy port 및 Backend 기술 기반을 확인한다. 결과: 요청 DTO와 repository 경계가 확정된다.
2. header/path/body 검증과 오류 mapping 테스트를 먼저 작성한다. 결과: 정상·누락·경계값·hash mismatch 기대가 고정된다.
3. sequence storage port 및 checksum metadata/atomic create 계약을 정의한다. 결과: 중복 race와 충돌을 테스트 double로 재현할 수 있다.
4. 실제 bytes 상한 streaming 검증 및 Session policy 검증을 구현한다. 결과: 상한 초과 데이터를 저장 전에 차단한다.
5. temp adapter에 원자적 chunk 게시와 동시 중복 판정을 구현한다. 결과: 동일 bytes ACK, 상이 bytes conflict가 일관된다.
6. Controller success/error envelope를 연결하고 Secret/Audio/path 로그 비노출을 확인한다. 결과: HTTP contract가 외부에서 검증된다.
7. Controller/service/adapter 테스트 및 Backend 빌드를 실행한다. 결과: 아래 test evidence가 기록된다.

## 검증 명령

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행한다. 임시 파일의 실제 생성/원자성은 임시 디렉터리 테스트와 통합 테스트로 확인한다.

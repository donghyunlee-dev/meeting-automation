# 순차 Audio 업로드 통합 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `FR-005`, `NFR-007`, `SCR-003`, `API-007`, `API-008`, `TASK-005.06`
- 선행 TASK-005.03 Issue [#21](https://github.com/donghyunlee-dev/meeting-automation/issues/21): IndexedDB pending/ACK 저장소
- 선행 TASK-005.04 Issue [#22](https://github.com/donghyunlee-dev/meeting-automation/issues/22): API-007 binary upload/idempotent ACK
- 선행 TASK-005.05 Issue [#23](https://github.com/donghyunlee-dev/meeting-automation/issues/23): API-008 received sequence 조회
- 본 TASK Issue [#24](https://github.com/donghyunlee-dev/meeting-automation/issues/24)
- 후속 TASK-005.07/005.08: 업로드 complete 뒤 Processing API 시작

## 변경 경계

- Frontend upload orchestrator: reconcile, serial missing upload, retry policy, cancellation, completion callback
- API client: API-007 raw binary와 API-008 JSON query typed contract
- IndexedDB adapter: pending 조회, sequence별 ACK 삭제를 TASK-005.03 contract로 호출
- Recording UI: 진행률·오류·수동 재시도·미전송 시 종료 처리 차단 안내
- Backend API는 별도 TASK에서 제공하며 본 Task는 계약 mock 및 통합 검증만 수행

## 구현 순서

1. 세 선행 Issue의 실제 request/response/local repository contract와 SCR-003 진행/오류 UX를 확인한다. 결과: adapter 경계가 일치한다.
2. reconcile, 직렬 순서, header/digest, ACK-delete, retryable 분류 및 UI 상태 테스트를 먼저 작성한다. 결과: 핵심 failure 경로가 구현 전에 고정된다.
3. API-008 조회 및 local pending 차집합으로 업로드 계획을 만든다. 결과: already-received Chunk가 다시 보내지지 않는다.
4. Web Crypto digest/header와 API-007 serial uploader를 구현한다. 결과: ACK 확인 후 단일 Chunk만 삭제된다.
5. 5회 bounded backoff, 수동 재시도 및 Session-not-found 보존 경로를 구현한다. 결과: 무한 자동 retry와 silent data loss가 없다.
6. Recording UI progress/error/retry callback을 연결하고 pending이 남으면 processing handoff를 거부한다. 결과: 화면에서 복구 결과가 명확하다.
7. FE 단위/통합 테스트, lint/build 및 API contract mock을 실행한다. 결과: 아래 verification evidence가 남는다.

## 검증 명령

Frontend root에서 `npm run test`, `npm run lint`, `npm run build`를 실행한다. FE/BE 통합은 API-007/008 contract mock과 개발 환경 연계 QA로 분리한다.

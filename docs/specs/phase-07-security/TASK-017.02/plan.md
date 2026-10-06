# 구현 계획

## 선행 조건

- TASK-006.01 (#27): chunk assembly와 source/assembled Audio 참조의 생성·정리
- TASK-006.04 (#30), TASK-006.05: pipeline stage별 실패 기록과 완료 stage부터 재개하는 처리 경계. Minutes 생성 시 stage는 `MINUTES_GENERATION`으로 노출한다.
- TASK-006.06 (#32), TASK-006.07 (#33): API-010 조회 및 Processing 화면 계약. 기존 `PROCESSING_FAILED` action 없음 기준은 이 Issue에서 승인한 retry/finalize 계약으로 대체한다.
- TASK-010.02 (#44): Document Provider 저장/멱등성 계약
- API-010 common response envelope 및 provider-neutral Document Port

## 변경 파일 및 책임

| 파일/경계 | 담당 | 변경 |
|---|---|---|
| Processing failure use case / Session model | BE | failed stage, retryability, Audio availability/expiry를 Session snapshot에 보존 |
| Temporary Audio storage boundary | BE | retry·download·expiry/finalize 때만 정리하고 idempotent cleanup 제공 |
| API-010 query/serializer | BE | failure audio metadata 및 상태별 `allowedActions` 계산 |
| API-020 retry command | BE | 버전·retryability·TTL 확인, Idempotency-Key 보호, 실패 stage 재개 |
| API-021 download controller | BE | 미만료 파일 stream, MIME/disposition 검증 및 안전한 파일명 |
| API-022 failure finalize use case | BE | 실패 Meeting document를 생성하고 Delivery orchestration을 호출하지 않음 |
| Failure Meeting document writer | BE | meeting metadata와 safe failure summary/disposition만 Document Port에 저장 |
| Cleanup scheduler / startup cleanup | BE | 분 단위 만료 확인, stale app-owned temp file 정리, 실패 로깅 |
| `docs/product/PRD.md` | Product contract | FR-027/028/NFR-006, Processing flow, TASK-017 추적 행 갱신 |
| `docs/product/api-spec.md` | API contract | API-010 failure fields/actions, API-020~022 및 `AUDIO_NOT_AVAILABLE` 추가 |
| `docs/product/data-spec.md` | Data contract | Audio TTL, failure Session metadata, 실패 문서 기록 정의 |
| `docs/product/architecture.md` | System flow | 사용자 retry와 Audio download/failure close 흐름 반영 |

## 순서와 검증 이유

Backend API/상태 계약을 먼저 구현한다. Frontend 후속 TASK-017.03는 `allowedActions`, expiry, download disposition을 이 계약에 맞춰야 하고 독립된 API integration 결과로 검증할 수 있다. 현재 Issue는 Backend deliverable에 한정하며 UI 구현은 다음 leaf task로 분리한다.

- Session/audio lifecycle domain 변경부터 하고 핵심 전이 단위 테스트를 추가한다.
- 성공/실패/failure-stage retry와 만료 계산을 구현한다.
- API-010 serialization과 `allowedActions`를 상태별로 연결한다.
- API-020/021/022를 use case에 연결하고 idempotency/version 검증을 적용한다.
- provider-neutral failure document writer를 구현한다. 성공 Review/Confirm/Publish는 건드리지 않는다.
- scheduler/startup cleanup을 구현해 만료 파일과 미소유 잔여 경로를 안전하게 제거한다.
- Controller/Document Port 경계 테스트 후 현재 PRD에 정의된 API/BE 관련 검증 명령을 확인한다. 설계 문서 작성 중에는 테스트를 실행하지 않는다.

## 소유권 및 공개 계약

- BE가 status, retry eligibility, 만료 시각, Audio 가용성을 정본으로 결정한다. FE는 API-010 action만 표시하고 서버가 명령 때 재검증한다.
- 오류 응답은 common `{error}` envelope를 사용한다. API-021의 binary 성공 응답만 JSON `{data}` envelope 예외다.
- Document write는 기존 Document Port를 사용한다. Provider ID나 payload 구조가 Domain으로 새지 않도록 한다.
- 실패 문서는 기록 전용이며 Email/Slack port를 호출하지 않는다.
- Local temporary path는 외부 입력으로 만들지 않는다. 파일 stream은 필요한 크기 제한 및 backpressure를 적용한다.

## 구현 중 주의할 계약

- Retry는 사용자가 명시적으로 요청할 때만 실행하고 같은 Session/job의 실패 stage부터 재개한다.
- retryability는 retry button eligibility다. 다운로드 또는 실패 마무리 eligibility와 분리한다.
- TTL은 만료 시각에서 API로 즉시 차단하고 정리 scheduler 지연이 download/retry 허용을 연장하지 않는다.
- Download 완료를 확인하지 못하면 `audioDisposition=DOWNLOADED` finalize를 허용하지 않는다.
- 만료 자동 종료는 `DISCARDED`로 기록하고 외부 delivery를 만들지 않는다.
- Backend 재시작 시 Session은 복구하지 않는다. 잔존 파일이 있는 환경에서 startup cleanup은 app-owned temp subtree만 대상으로 한다.

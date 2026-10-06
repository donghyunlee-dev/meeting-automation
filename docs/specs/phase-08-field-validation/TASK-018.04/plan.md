# 구현 계획

## 선행 조건

- TASK-018.01 Issue #65 및 `docs/evidence/TASK-018.01.md`
- TASK-018.02 Issue #66 및 `docs/evidence/TASK-018.02.md`
- TASK-018.03 Issue #67 및 `docs/evidence/TASK-018.03.md`
- TASK-005.02/.03/.04/.05/.06/.08 녹음, 로컬 저장, API-007/008 upload, API-009 종료 계약
- PRD v1.8.1, `NFR-001~003`, `NFR-007`, `FR-005`, `API-006~009`, `DEC-020`, Section 21

선행 현장 Evidence가 아직 없다면 코드 수정 task로 진행하지 않는다. 부재한 증거와 선행 Issue 상태를 기록하고 Phase 8의 다음 단계가 입증 가능한 시점까지 blocker를 유지한다.

## 변경 경계와 담당

| 경계 | 담당 | 검증 산출물 |
|---|---|---|
| Recorder/MIME/UI | FE | 브라우저 별 재현 test, 사용자 안전 오류/복구 안내, capability 경계 |
| IndexedDB Chunk/sequence | FE | 원자 저장/ACK 및 missing-only 재전송 regression |
| API-007/008 수신/hash/sequence | BE | 계약 유지와 실제 업로드/server inventory regression |
| 종료/session route | FE + BE + 통합 | 모든 upload 확인 전 API-009 차단, 같은 Session handoff |
| 기기 현장 검증 | QA | 영향 run 재실행, browser matrix와 민감데이터 없는 Evidence |

독립 경계는 소유자별 테스트로 확인하고 FE/BE 결합 동작만 통합 run에서 대조한다. 결함이 한 경계를 넘으면 우선 관련 계약을 먼저 확정하고 소유자별 수정 후 기기 통합 검증을 한다.

## 수정 및 검증 순서

- 선행 Evidence 경로와 open defect를 확인하고 재현 가능한 항목만 수락한다. 결과: defect register에 expected/actual, run, contract, owner, severity가 있다.
- 위험이 가장 높은 data-loss/sequence/hash/processing-handoff 결함부터 원 build에서 재현한다. 결과: 실패 증거와 최소 regression case가 확보된다.
- 변경 전 regression test를 test-first로 추가해 실패를 확인한다. FE는 recorder/store/uploader 단위·브라우저 테스트, BE는 API/store contract 테스트를 선택한다.
- 계약을 바꾸지 않는 최소 수정으로 테스트를 통과시킨다. 결과: 관련 regression이 green이고 API-006~009 contract fixture가 비회귀다.
- 나머지 재현 UI/MIME/호환성 결함도 각각 실패 test → 최소 수정 → 관련 regression 순으로 처리한다. 한 test에 여러 독립 현상을 섞지 않는다.
- 모든 변경 후 저장소의 task-specific test/lint/build 명령과 API contract mock을 실행한다. 정확한 명령은 현재 package scripts/CI 정의를 사용하고 Evidence에 버전과 결과를 남긴다.
- 최초 실패와 같은 device/OS/browser mode/MIME/network/run 조건에서 affected scenario를 재실행한다. 결과: 같은 조건에서 fix outcome 또는 여전히 실패하는 경로가 남는다.
- 실제 pass 조합, fail 조합, 실행되지 않은 조합을 matrix에 반영한다. 결과: 실행 범위 밖으로 지원을 확대하지 않는다.
- unresolved architecture/contract problem을 별도 decision Issue/ADR로 연결하고, Evidence를 정리한 후 완료 기준을 판정한다.

## 구조와 데이터 계약

- Frontend MIME 선택은 API-006 `acceptedMimeTypes` order와 `MediaRecorder.isTypeSupported` 계약을 유지한다. capability check보다 앞서 마이크를 요청하지 않는다.
- Chunk/body/MIME/hash/byte/sequence는 API-007/008 기존 계약을 따른다. 기존 accepted MIME이나 max size를 변경하지 않는다.
- 누락 Chunk가 남으면 API-009/Processing route로 이동하지 않는다. missing-only retry와 ACK 후 해당 Chunk cleanup을 유지한다.
- 구현 편의를 위해 fixture 오디오, transcript 또는 인증 데이터를 일반 로그/Evidence로 내보내지 않는다.
- `frontend`, `backend`, test scripts, CI workflows에서 이번 결함에 연결된 파일만 선택 수정하고, 비관련 변경은 포함하지 않는다.

## 완료 정의

각 수정 PR은 defect register, 실패-first regression, 녹색 관련 automated tests, 영향 시나리오의 기기 재검증, browser support matrix, cleanup/Evidence를 포함한다. 구조 변경이나 미확인 환경 때문에 이 조건을 만족하지 못하면 해당 항목을 unresolved로 남기고 연결 Issue를 만든다.

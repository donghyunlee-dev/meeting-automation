# 검증 계획

## 자동화 테스트

이번 설계 task는 코드 변경 없이 실기기 QA를 정의한다. 자동화 harness나 정확한 repository command가 확인되지 않았으므로 명령을 추측하지 않는다. API 오류 주입과 server inventory는 기존 non-production QA fixture/proxy를 사용한다. 해당 도구가 없어 장애를 재현하지 못하면 행을 pass로 처리하지 않고 환경 blocker로 기록한다.

## 합성 Chunk fixture

- 최소 8개의 결정적 test Blob을 사용하고 0-based sequence 및 서로 다른 `chunkId`를 부여한다.
- sequence, `chunkId`, `mimeType`, `byteLength`, SHA-256 manifest를 만들고 API-007 body와 server 수신 inventory를 대조한다. manifest에도 실제 payload/전사 가능 문구/개인정보는 포함하지 않는다.
- 시험 시작점 예: local pending `[1,2,3,4,5,6,7]`, API-008 received `[0,1,3,5,6]`; 기대 missing 전송 순서는 `[2,4,7]`이다. fixture의 Session/sequence는 run마다 고유하게 만든다.
- Android Chrome과 iOS Safari에서 각 행을 별도 QA Session으로 실행한다.

## 수동 기기/장애 행렬

| ID | 입력/절차 | 기대 결과 | Evidence |
|---|---|---|---|
| REC-RECONCILE | 서버 수신/로컬 pending이 겹치는 fixture에서 uploader 재개 | API-008 먼저 조회, 이미 수신된 local만 정리, missing만 오름차순 직렬 PUT | 시작/최종 sequence, 요청 순서, in-flight 최대 1 |
| REC-ACK-DELETE | sequence별 HTTP 200·sequence·`received:true` ACK 확인 | ACK된 sequence만 IndexedDB에서 제거, 인접 pending 보존 | ACK와 제거 sequence 쌍 |
| REC-ACK-LOST | PUT 서버 수락 뒤 response drop 후 자동/수동 retry | 같은 `chunkId`/body/hash로 중복 안전, API-008 원격 수신 확인 후 local copy 정리 | PUT 시도 횟수, final object count/hash/bytes |
| REC-OFFLINE | 저장 pending 후 uplink 차단, 60초 뒤 복구 | outage 동안 data loss 또는 API-009 handoff 없음; 복구 후 API-008 reconcile부터 시작 | offline 시각, pending/remote inventory |
| REC-RELOAD | pending이 남은 상태에서 page reload 및 browser 종료/reopen | 동일 origin과 유효 Session에서 pending 및 next sequence 복원, 재시작 직후 API-008 조회 | 재시작 방법/시각, 복구 sequence 집합 |
| REC-RETRYABLE | timeout, 408, 429, 5xx를 각각 주입 | 최대 5회 재시도 delay 1/2/4/8/16초, 이후 수동 retry 노출 | 오류 종류, 시도 순서/시각, UI 결과 |
| REC-CLIENT-ERROR | validation/conflict 4xx 주입 | 자동 재시도 없음, pending 보존, 안전한 오류 표시 | status/error category, pending inventory |
| REC-SESSION-MISSING | synthetic Session에 `SESSION_NOT_FOUND` 주입 | 복구 성공으로 표시하거나 local Chunk 자동 삭제/처리 handoff하지 않음 | 오류 결과 및 local 상태 |
| REC-QUOTA | quota 초과/transaction abort test setup 실행 | append/ACK가 성공으로 표시되지 않고 잔여 데이터/오류가 확인되며 사용자가 안전하게 중단 가능 | setup 조건, storage error, 상태 summary |
| REC-HANDOFF-GATE | pending 1개 이상인 상태와 모두 원격 확인된 상태에서 종료 action | pending 상태에서는 API-009 0회; 모두 정리 후 정상 종료 run에서만 API-009 허용 | API request count, Session/route ID |
| REC-CLEANUP | 양 OS run의 fixture 종료 후 cleanup | 서버 test object 0, 브라우저 Session fixture 정리, 운영 Audio 미생성 | count/status만 기록 |

`REC-RETRYABLE`은 각 응답을 독립된 run으로 수행하고 fixture 재사용 시 매번 API-008 시작 목록을 확인한다. API-007 hash는 body의 실제 bytes로 계산해 manifest expected value와 비교한다.

## 성공 및 실패 판정

- 성공: 서버 수신 목록과 정상 ACK로 확인된 원격 receipt만 local에서 정리되고, 나머지 local pending은 누락 없이 재전송된다. sequence별 수신 결과는 하나이며 hash/byte가 원본 manifest와 일치한다.
- 실패: API-008 없이 재전송, 잘못된 순서/병렬 전송, ACK 전 삭제, 응답 유실 재시도에서 다른 id/body, sequence duplicate/missing, 예상 밖 payload, 미전송 상태 API-009, Session 오류 뒤 local 자동 삭제.
- 저장소가 사용자의 브라우저 설정에 의해 삭제된 run은 복구 성공/제품 결함으로 일괄 분류하지 않고 사전 경계를 기록한다. UI가 이를 성공 처리하거나 무손실 보장을 약속하면 실패다.
- Browser IndexedDB `complete` 결과는 transaction 완료 확인으로만 사용한다. OS crash/power-loss 물리 flush 보장으로 간주하지 않는다.

## Evidence와 정리

- 위치: `docs/evidence/TASK-018.03.md`
- 허용: fixture ID, 기기/OS/browser/app/API build, Session ID, `chunkId`, sequence, hash, byte length, 시도 횟수/지연, HTTP status/category, object count, pass/fail 및 후속 Issue.
- 금지: Audio payload, Transcript, 개인 이메일, 통화 내용/전화번호, auth/secret/header value, raw request/response, 실제 사용자 Session/object URL.
- 모든 fixture object를 비운 뒤 evidence에 정리 count만 남긴다. 실패 cleanup은 비운영 scope로 격리해 삭제 결과를 후속 Issue로 기록한다.

# 검증 계획

## 자동화 테스트

| ID | 입력/준비 | 기대 결과 | 증거 |
|---|---|---|---|
| AUDIO-LIFE-01 | pipeline 성공 → REVIEW | assembled Audio와 backend chunks가 삭제됨 | storage fake delete assertion |
| AUDIO-LIFE-02 | TRANSCRIPTION retryable 실패, Audio 존재 | `PROCESSING_FAILED`, retryable=true, audioAvailable=true, expiry=실패+24h, retry/download/finalize action 제공 | Session/API-010 response assertion |
| AUDIO-LIFE-03 | non-retryable 변환 실패 | retry action 없음, 다운로드/실패 종료는 가능 | API-010 allowedActions assertion |
| AUDIO-LIFE-04 | 만료 전 명시 retry | 완료 stage는 건너뛰고 실패 stage부터 시작 | stage adapter call counts |
| AUDIO-LIFE-05 | retry 중 TTL 도달 | 실행 중 Audio 유지, retry terminal result까지 보존 | fake clock + storage assertion |
| AUDIO-LIFE-06 | retry가 다시 실패 | expiry가 새 실패 시각 기준 24시간으로 갱신 | API-010 timestamp assertion |
| AUDIO-LIFE-07 | expiry 전/후 retry 및 download | 전에는 허용, 후에는 action 제거와 `AUDIO_NOT_AVAILABLE`/상태 오류 | fake clock API test |
| AUDIO-LIFE-08 | 유효한 Audio download | raw binary, 원 MIME, attachment disposition, 안전한 서버 파일명 | MockMvc byte/header assertion |
| AUDIO-LIFE-09 | retryable assembly 실패와 intact source chunks | retry action 유지, download action 숨김 | API response + chunk inventory assertion |
| AUDIO-LIFE-10 | 성공 download 뒤 `DOWNLOADED` finalize | meeting metadata와 safe failure/disposition 저장, `COMPLETED_WITH_WARNINGS` | Document Port capture |
| AUDIO-LIFE-11 | `DISCARDED` finalize 및 TTL 자동 finalize | 실패 문서 기록, Audio/chunk 삭제, email/notification 호출 0회 | adapter interaction assertions |
| AUDIO-LIFE-12 | 성공 download 없이 `DOWNLOADED` finalize | 거절, Session/document 불변 | HTTP error + version assertion |
| AUDIO-LIFE-13 | 동일 finalize key 재요청 | 최초 성공 결과 재사용, 문서 중복 생성 없음 | idempotency/document call count |
| AUDIO-LIFE-14 | 중복 cleanup, 객체 없음, delete timeout | 멱등 처리 또는 cleanup-pending; object key/Audio 없는 로그 | object storage fake + log capture |
| AUDIO-LIFE-15 | 임의 object key, 다른 Session/object prefix | 외부 key 접근 및 cross-session object 조작 거부 | storage adapter authorization test |
| AUDIO-LIFE-16 | Backend 재시작 후 기존 Session 조회 | `SESSION_NOT_FOUND`; 메모리 Session 복구 없음 | restart fixture + logs |
| AUDIO-LIFE-17 | 실패 Meeting Document 본문 | metadata와 safe failure/disposition만 있고 Transcript/Minutes/Audio/raw provider response 없음 | captured body scan |
| AUDIO-LIFE-18 | 기존 REVIEW/CONFIRMED/PUBLISH 정상 경로 | 정상 action, 저장, 선택 Delivery 결과 불변 | focused regression suite |
| AUDIO-LIFE-19 | `MINUTES_GENERATION` retryable 실패 후 retry | 기존 Transcript/mapping 사용, STT와 diarization adapter 재호출 0회 | stage and adapter invocation assertions |
| AUDIO-LIFE-20 | Failure Document Provider write 오류 후 새 finalize key로 재요청 | safe `DOCUMENT_FAILED`; Audio disposition 유지, externalSessionId로 문서 중복 없이 저장 재시도 | Document Port failure/retry test |
| AUDIO-LIFE-21 | 두 retry 명령 동시 제출 또는 stale `If-Match` | Session 단위 한 실행만 접수; stale version은 412, 중복은 상태 충돌/동일 접수 결과 | concurrency and version tests |
| AUDIO-LIFE-22 | Backend restart 뒤 storage expiry sweep | Session 복구 없이도 expiry metadata가 지난 app-owned Audio object를 제거 | adapter listing fake + fake clock |
| AUDIO-LIFE-23 | Chunk durable write timeout/failure | API-007 성공 ACK가 없고 retry-safe storage error 반환 | storage adapter failure injection |

모든 수용 기준을 AUDIO-LIFE-01~23에 연결한다. JSON API 오류는 공통 `{error}` envelope와 safe code/category/traceId를 검증한다.

## 수동 QA

| ID | 절차 | 기대 결과 | 증거 |
|---|---|---|---|
| AUDIO-QA-01 | 실제 또는 고정 fixture의 일시적 STT 실패 확인 | 실패 stage/보존 만료가 표시되고 action이 API-010과 일치 | API response + 화면 캡처 |
| AUDIO-QA-02 | `변환 재시도` 선택 | 사용자 동작 1회가 새 실행을 만들고 성공하면 Review로 이동 | request trace + 상태 변화 |
| AUDIO-QA-03 | 반복 실패 후 원본 Audio 다운로드와 실패 종료 선택 | 브라우저 download 후 저장 확인을 받고 실패 종료 | 다운로드 파일 + UI 상태 |
| AUDIO-QA-04 | 최종 실패 문서 및 Delivery 조회 | 회의 정보/변환 실패/disposition 기록, Email/Slack delivery 없음 | Provider 문서 + delivery 조회 |
| AUDIO-QA-05 | 만료 시각 전/후 확인 | 만료 뒤 retry/download 거부, cleanup 완료 | API 응답 + 파일 정리 evidence |

Evidence에 실제 Audio, Transcript, Secret, Provider raw body를 저장하지 않는다. Fixture ID, Session ID, traceId, 응답 코드와 정리 여부만 남긴다.

## Release 확인

- PRD, API, Data, Architecture, SDD 간 계약 일치와 `git diff --check`를 확인한다.
- Java 21/Gradle baseline의 실행 명령은 구현 단계에서 확정한다. 이 설계 요청에서는 테스트를 실행하지 않는다.
- Backend 재시작 시 Session 복구는 지원하지 않지만 private Audio object는 만료까지 durable하며 cleanup sweep은 Session과 독립적으로 수행함을 확인한다.

# 검증 계획

## 자동화 검증

Backend 구현 시 JUnit 5로 API/application/provider 경계를 검증한다. 실제 실행 명령은 repository의 Backend build 설정 및 TASK-001.05 결과에 따라 확정한다. 설계 단계에서는 테스트를 실행하지 않는다.

| ID | 준비 및 입력 | 기대 결과 | 증거 |
|---|---|---|---|
| PUB-API-01 | `CONFIRMED` Session, 현재 If-Match, 필수 key, 유효 body | HTTP 202 공통 envelope의 `{sessionId,status:"PUBLISHING"}`, version +1, publish 1건 접수 | Controller/application API test |
| PUB-API-02 | 필수 header 누락, malformed JSON, 잘못된 타입/null/unknown field | HTTP 400 `VALIDATION_FAILED`; Session/version unchanged | Request validation test |
| PUB-API-03 | 존재하지 않는 session ID | HTTP 404 `SESSION_NOT_FOUND`; provider 호출 0회 | API test |
| PUB-API-04 | `CONFIRMED` 이전/이후 publish 불가 상태 | HTTP 409 `SESSION_STATE_CONFLICT`; provider 호출 0회 | State guard test |
| PUB-API-05 | stale If-Match | HTTP 412 `SESSION_VERSION_CONFLICT`; Session unchanged | Version guard test |
| PUB-API-06 | 같은 key/session/body/version 재전송 | 최초 202 body replay; version/worker enqueue/provider call 증가 없음 | Idempotency replay test |
| PUB-API-07 | 같은 key를 다른 body/session/version으로 재사용 | HTTP 409 `IDEMPOTENCY_KEY_CONFLICT`; 상태 unchanged | Fingerprint conflict test |
| PUB-API-08 | recipient ID가 Session roster 밖이거나 중복됨 | HTTP 400 `VALIDATION_FAILED`; provider 호출 0회 | Roster/body validation test |
| PUB-DOC-01 | Provider lookup가 기존 ref 반환 | ref 재사용, create 0회, API-010에 documentId/URL | Provider fake interaction test |
| PUB-DOC-02 | Provider lookup가 empty 반환 | metadata `externalSessionId=sessionId`인 command로 create 1회 | Command/interaction test |
| PUB-DOC-03 | 같은 Session, 서로 다른 key의 동시 요청 | 원자적 state gate로 한 작업만 채택; 논리적 Meeting 1개 | Concurrency test |
| PUB-DOC-04 | Provider lookup 실패/timeout | create로 fallback하지 않고 `DOCUMENT_FAILED`; downstream 0회 | Failure interaction test |
| PUB-DOC-05 | Provider create 실패 | `DOCUMENT_FAILED`, 안전한 error만 API-010에 표시; Email/Notification 0회 | Failure/handoff test |
| PUB-DOC-06 | Provider create 성공 뒤 결과 기록 | `DOCUMENT_SAVED`, documentId 및 유효 URL 기록; 이후 후속 전달 handoff | State/API-010 test |
| PUB-SEC-01 | Provider가 credential을 포함한 원문 오류 반환 | response/log에 authorization, token, 원문 본문, Minutes/Transcript 없음 | Redaction assertion/log capture |

모든 acceptance criterion은 PUB-API-01~08 및 PUB-DOC-01~06에서 확인한다. 같은 Session ID metadata를 기준으로 기존 문서 재발견이 가능해야 한다.

## 수동 API QA

- Confirm된 fixture에서 유효 API-015 요청 후 HTTP 202와 version 증가, API-010의 `PUBLISHING`을 확인한다.
- 동일 요청/key를 재전송하고 같은 접수 결과가 돌아오며 version/provider 호출이 중복되지 않는지 확인한다.
- DocumentProvider에 같은 Session metadata가 있는 fixture로 기존 URL이 채택되고 신규 page가 생기지 않는지 확인한다.
- Provider 신규 생성 성공 및 생성 실패를 각각 재현해 API-010 최종 상태와 document reference/error category를 확인한다.
- 실패 경로에서 Email/Notification provider가 호출되지 않고 로그에 credential, Minutes/Transcript, Provider 원문이 없는지 확인한다.

## 릴리스 확인

- 기본 Confluence Cloud 구성은 REST API v2 및 Basic(email/API token) 설정을 기존 Adapter 경계에서 사용하며 secret은 API 응답/로그에 없어야 한다.
- 문서 생성의 externalSessionId가 Session ID와 일치하는지 검토한다.
- 자동화/manual evidence와 실제 명령 결과를 Issue #44에 기록한다. 실제 Provider 사용 여부는 구현 단계의 별도 승인 절차를 따른다.

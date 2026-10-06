# Secret 경계 및 로그 마스킹 설계

## 작업 식별 정보

- 작업: TASK-017.01
- 상위 작업: TASK-017 Security / Temporary Data
- 단계: Phase 7 — History / Settings / Operations
- PRD 기준: PRD-MA-001 v1.7.0, 2026-10-05
- 관련 기준: DEC-017, FR-030, NFR-004~006
- 영역: Backend, Frontend
- 선행 작업: TASK-001.05 (#5), TASK-016.01 (#57)
- GitHub Issue: [#60](https://github.com/donghyunlee-dev/meeting-automation/issues/60)

## 결과

Provider credential과 다른 secret이 Backend runtime 설정에만 존재하고 Frontend build artifact, public API response, 일반 application log에 나타나지 않는지 자동 검증한다. 테스트는 synthetic canary 값을 사용하며 운영 Secret을 읽거나 출력하지 않는다.

## 경계

- Frontend는 공개 API base URL 등 public 설정만 받는다. VITE_ bundle에 Provider key/token, OAuth secret, webhook, Authorization header 또는 backend-only environment value가 포함될 수 없다.
- Backend는 Document, AI, Email, Slack credentials를 runtime secret configuration에서만 읽는다. API-001/API-019 및 업무 API DTO는 명시한 public allowlist만 직렬화하며 environment map, root credential, token, provider response 원문을 반환하지 않는다.
- API error message/details와 일반 structured log는 Secret, Audio, Transcript, Minutes, 전체 email, Authorization/header, request/response body를 기록하지 않는다. 성공 API의 UI용 콘텐츠 응답 자체와 오류/로그 노출을 구분한다.
- HTTP 외부 구간은 TLS/HTTPS 배포 설정을 사용한다. 이 작업은 배포 인증서/네트워크 구성을 바꾸지 않고 설정 경계를 검증한다.

## 자동 검사 설계

- TASK-001.05 env sample 검사를 유지한다. FE sample은 공개 VITE_API_BASE_URL만 허용하고 BE sample credential 값은 비워 둔다.
- Build/API/log 경계 테스트는 운영 값 대신 synthetic canary secret, fake token, fake webhook, fake provider body를 주입한다.
- FE production build 산출물을 canary 문자열 기준으로 검사한다. 실제 environment values를 출력하거나 dump하지 않는다.
- API serialization tests는 API-001/API-019 및 업무 response DTO allowlist와 error details를 검증한다.
- Log capture tests는 Provider/API/processing 오류를 주입하고 captured events만 검사한다. request/response body logging은 비활성화한다.
- Scanner report에는 파일 경로와 rule id만 표시한다. 매칭 문자열/값은 출력하지 않는다.
- 대상은 repo source/config templates, test build output, API DTO/error response, captured logs다. 개인 .env, secret store, live production dumps는 열지 않는다.

## 비범위

- Secret rotation, secret 입력 UI, vault/cloud secret store migration은 추가하지 않는다.
- 전 저장소의 모든 파일을 검사하는 범용 secret scanner 도입은 범위 밖이다.
- 성공 API의 Transcript/Minutes 업무 응답은 기존 계약상 허용하며 제거하지 않는다.
- Audio 임시 파일 삭제 수명주기는 TASK-017.02가 소유한다.

## 완료 기준

- Provider credential이 Frontend bundle에 포함되지 않는 빌드 경계를 검증한다.
- API-001/API-019, error response, Admin Slack payload allowlist에서 synthetic secret/provider body가 제거된다.
- 대표 error logs에 canary Secret, Authorization, Audio/Transcript/Minutes/email이 없다.
- scanner normal/violation fixture가 결정적으로 통과/실패하며 발견값을 출력하지 않는다.
- 개인 환경 파일 및 운영 Secret store를 scanner가 읽지 않는다.
- HTTPS/TLS 배포 설정을 검토하고 완료 기준을 [검증 계획](./test.md)에 연결한다.

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

# 검증 계획

## 자동화 테스트

모든 fixture는 가짜 canary 값, synthetic transcript/audio metadata, fake provider를 사용한다. 실패 assertion과 실행 report는 원문 값을 출력하지 않는다.

| ID | 입력/준비 | 기대 결과 | 증거 |
|---|---|---|---|
| SEC-REG-01 | FE production build와 synthetic Backend secret public env fixture | env allowlist 실패 또는 bundle 검사 검출; canary 원문은 stdout/stderr/artifact report에 없음 | scanner rule/result |
| SEC-REG-02 | tracked env samples와 정상/위반 fixture | 공개 FE key만 허용, Backend Secret sample 위반은 경로/rule만 보고 | env checker tests |
| SEC-REG-03 | API-001/API-019와 업무 DTO에 가짜 credential 필드 주입 | allowlist 필드만 직렬화되고 Secret/config map이 응답에 없음 | response schema assertion |
| SEC-REG-04 | Provider 예외 body/header에 canary 주입 | 공통 `{error}` response에 raw body, Authorization, token 없음 | exception mapping test |
| SEC-REG-05 | Audio/Transcript/Minutes/Email을 담은 처리 오류 fixture | captured structured logs에 원문/전체 PII 없음; safe stage/error/trace만 있음 | log capture rule summary |
| SEC-REG-06 | Admin incident payload에 credential, webhook, content 필드 추가 | allowlist 밖 필드 제거/거부; 실제 Admin Slack 호출은 mock에서 0회 | payload schema + interaction count |
| SEC-REG-07 | storage config, object access mode, synthetic object | public/anonymous read 거부; Browser/API response에 credential, object key, direct URL 없음 | isolated storage access result |
| SEC-REG-08 | API-021 유효 Session/action 및 만료/없는 Session | 유효한 요청만 Backend stream; 만료/없는 Session은 `AUDIO_NOT_AVAILABLE` 또는 `SESSION_NOT_FOUND` | API contract result |
| SEC-REG-09 | Audio object `audioExpiresAt` 전·후 | expiry 전 허용, 만료 즉시 API retry/download 차단 | fake clock and API result |
| SEC-REG-10 | 성공 REVIEW 전이 | chunk와 assembled Audio object delete가 요청되고 storage에 남지 않음 | adapter interaction/object inventory |
| SEC-REG-11 | 실패 Audio의 retry, 재실패, active retry lease | retry 중 delete 없음; 재실패 후 expiry가 failure time+24h로 재설정 | fake clock/object metadata check |
| SEC-REG-12 | 만료 sweep, Backend restart 후 실행, 동일 sweep 반복 | expired app object 삭제, 미만료 object 유지, repeat가 멱등 | object inventory without key values |
| SEC-REG-13 | `DOWNLOADED`/`DISCARDED` 실패 finalize | 허용 metadata만 Meeting document에 있고 Email/Slack delivery 0건 | document allowlist + mock call count |
| SEC-REG-14 | 자동 검사 violation report와 stderr | canary value, secret, content, raw response가 한 건도 출력되지 않음 | captured process output scan |

모든 수용 기준은 SEC-REG-01~14에 연결한다. Scanner는 Git 추적 대상, 전용 fixture, 제한된 build output만 검사하고 personal `.env*`, keyring, 배포 Secret store, production data를 열지 않는다.

## 수동 QA

| ID | 절차 | 기대 결과 | 필요한 증거 |
|---|---|---|---|
| SEC-QA-01 | Non-production storage console/API에서 store가 private인지 확인하고 unauthenticated direct read를 요청 | 객체가 공개되지 않고 실제 object key/credential을 티켓·캡처에 남기지 않음 | 상태 코드/설정 상태만 기록 |
| SEC-QA-02 | TLS endpoint, 저장 암호화, region, configured deletion 및 provider-managed backup retention 설정 검토 | 적용 환경·보존 범위와 provider 근거를 구분해 기록 | provider 설정 이름/문서 링크, 민감값 없음 |
| SEC-QA-03 | Browser failure recovery에서 API-021을 통한 fixture download 확인 | Browser가 object-store origin/credential로 요청하지 않고 attachment download만 수행 | redacted network route/status |
| SEC-QA-04 | `COMPLETED_WITH_WARNINGS` 실패 Meeting 및 전송 이력 확인 | 회의 metadata/안전한 실패/disposition만 있고 attendee Email/Slack 발송이 없음 | Document URL/전송 건수, 본문 캡처 없음 |
| SEC-QA-05 | FE/BE test/scan 결과와 evidence 초안 확인 | 로그·표준출력·Evidence에 secret, canary 원문, Audio, Transcript가 없음 | sanitization checklist |

## 실행 명령과 환경

- 구현 시 저장소의 package/build manifests와 TASK-017.01의 기존 scanner 경로에서 정확한 명령을 확인한다. 이 설계에서 명령을 추측하지 않는다.
- FE/BE 자동 test, focused security scanner, 필요한 관련 regression test/build 결과를 분리 기록한다. 테스트가 명령을 새로 도입하면 tasks의 결과물로 문서화한다.
- Storage 수동 검증은 non-production 전용 store/object prefix와 synthetic fixture를 사용하고 작업 후 테스트 객체를 삭제한다. Production account, production data, 운영 Secret store는 사용하지 않는다.

## Evidence와 완료 확인

- 생성 경로: `docs/evidence/TASK-017.04.md`
- 기록 항목: repository commit, FE/BE runtime/build version, non-production environment class, 실행 명령, pass/fail 요약, storage private/expiry 설정 상태, 잔존 object 수, 문서/전송 호출 수.
- 금지 항목: canary 원문, Secret, 개인 식별정보, Audio/Transcript/Minutes 본문, storage object key/public URL, raw API/provider response, 환경 dump, 운영 로그.
- 종료 전 `git diff --check` 및 변경 파일 목록을 확인하고 Evidence 자체도 같은 민감값 scanner 대상으로 검사한다.

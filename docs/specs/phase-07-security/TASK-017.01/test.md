# 검증 계획

## 자동화 테스트

테스트 전용 synthetic canary만 사용한다. 실제 runtime secret이나 개인 환경 파일은 읽지 않는다.

| ID | 조건 | 기대 결과 | 증거 |
|---|---|---|---|
| SEC-SCOPE | source/template/build/API/log 및 local env/secret-store fixture | 허용 대상만 검사하며 제외 경로는 읽거나 출력하지 않음 | checker scope test |
| SEC-ENV-VALID | 정상 FE/BE env sample | 검사 통과 | checker test |
| SEC-ENV-INVALID | backend sample credential 값 또는 FE backend-only key | 실패 종료, 값 없이 file/rule만 표시 | checker test |
| SEC-FE-BUNDLE | 잘못된 VITE variable에 fake Provider secret | config gate 실패 또는 artifact scan 검출; 값 출력 없음 | FE build test |
| SEC-API-CONFIG | API-001/API-019 credential-like fixture | allowlisted public fields만 serialize | API test |
| SEC-API-ERROR | Provider exception body/header canary | common error response/details에 원문 없음 | exception handler test |
| SEC-PROVIDER-LOG | HTTP/provider failure fixture의 Authorization/token/webhook | captured logs에서 credential/body 없음 | log capture test |
| SEC-CONTENT-LOG | Audio/Transcript/Minutes/email이 포함된 처리/전달 실패 | content/PII 로그 없음; trace/stage/error code 보존 | log capture test |
| SEC-ADMIN-PAYLOAD | Admin incident input에 금지 fields 추가 | 허용 필드만 serialize; webhook/token/raw error 없음 | payload test |
| SEC-OUTPUT-SAFETY | violation fixture 검사 | stdout/stderr에 canary value 없음 | process output assertion |
| SEC-HTTPS | production deployment config/source | 외부 endpoint TLS/HTTPS 경계 선언 | config review evidence |

## 수동 QA

- FE build bundle, API response fixture, captured fake-provider logs에서 canary marker를 검색한다. Environment dump는 사용하지 않는다.
- 개인 local env 파일이 Git ignore 규칙에 적용되는지 파일 내용을 출력하지 않고 확인한다.
- Evidence에 파일 경로/rule/result만 포함되고 Secret/PII 값이 없는지 확인한다.

## 릴리스 확인

- Repository-defined secret-boundary checker, FE tests/build, Backend tests/build를 실행한다.
- CI log/artifact report에 credential raw value가 없는지 확인하고 docs/evidence/TASK-017.01.md에 pass/fail 요약만 기록한다.

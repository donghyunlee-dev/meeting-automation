# 작업 목록

## 사전 조건

- TASK-001.04 공통 exception handler/traceId 및 TASK-006.04 processing failure model이 준비되어 있다.
- Provider/Delivery tasks의 public errorCode/retryable 결과가 존재한다.

## 구현 단계

- [ ] API 오류 characterization tests를 먼저 작성한다: 여섯 필드, 기존 HTTP/code mapping, X-Request-Id 전파, generic exception redaction.
- [ ] FailureContext taxonomy tests를 작성한다: 네 업무 분류와 validation/not-found/conflict 분리 및 stage mapping.
- [ ] Mapping implementation을 추가한다: 기존 code/status 유지, safe message, INTERNAL_ERROR fallback, 신규 business code 금지.
- [ ] Processing/Document/Email/Notification call sites를 연결하고 adapter retryable 값을 보존한다. 부작용 모호한 결과는 false로 처리한다.
- [ ] API-010/Delivery projection과 structured logs를 검토해 correlation 및 금지 정보 제거를 검증한다.
- [ ] 네 분류의 정상/오류/retryable cases, unknown exception 및 redaction 회귀 테스트를 실행한다.
- [ ] Backend tests/build를 실행하고 docs/evidence/TASK-016.01.md에 비민감 결과를 기록한다.

## 완료 확인

- 모든 분류/API contract가 자동 검증된다.
- API-016 retry 및 후속 TASK-016.02 입력 contract와 모순되지 않는다.
- git diff --check와 secret/log capture 검토를 통과한다.

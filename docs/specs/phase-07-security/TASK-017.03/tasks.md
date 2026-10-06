# 구현 작업

모든 항목은 TASK-017.02/#61 API 계약과 TASK-017.03 spec을 따른다. FE tests는 구현 전에 작성한다. 이 설계 작업에서는 테스트를 실행하지 않는다.

## 단계

1. FE route/API client/test scripts를 확인하고 API-010/020~022 typed contract를 확정한다. 완료 증거: 구현 파일/스크립트 경계와 response union이 기록된다.
2. failure state matrix tests를 작성한다. 완료 증거: 4 stage, retryable/non-retryable, audioAvailable, expiry에 따른 text/action visibility가 실패 테스트로 준비된다.
3. stage/status-to-Korean-copy mapper와 API-010 failure status renderer를 구현한다. 완료 증거: raw error/provider details/partial Transcript·Minutes가 화면에 없다.
4. API-020 retry button/mutation을 구현한다. 완료 증거: click마다 새 attempt key, 최신 If-Match, single-flight, uncertain-response API-010 reconciliation, success Review navigation이 검증된다.
5. API-021 authenticated attachment navigation과 post-download confirmation을 구현한다. 완료 증거: Backend가 private object를 stream해 native browser download를 시작하고 FE가 object URL/credential/bytes를 blob/state/log에 저장하지 않는다.
6. API-022 `DOWNLOADED`/`DISCARDED` action과 DOCUMENT_FAILED handling을 구현한다. 완료 증거: 저장 확인/action choice에 맞는 payload와 idempotency policy, failure-only terminal result가 보인다.
7. expiry timer/tab visibility refresh 및 session terminal states를 구현한다. 완료 증거: 만료 action이 숨겨지고 Session restart `SESSION_NOT_FOUND`가 안전하게 안내되며 active retry 중 polling이 깨지지 않는다.
8. 360px/mobile safe area, keyboard, screen reader, reduced-motion 및 manual native-download QA를 수행한다. 완료 증거: automated evidence와 browser/device manual matrix가 기록된다.

## 의존 관계

- 1~3단계는 4~7단계 UI handler보다 먼저 완료한다.
- API-020/021/022 사용자 동작은 TASK-017.02 Backend 계약 완료 후 integration한다.
- 8단계는 recovery controls와 expiry lifecycle을 integration한 뒤 수행한다.

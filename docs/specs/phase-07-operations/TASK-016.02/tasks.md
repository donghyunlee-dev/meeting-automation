# 작업 목록

## 사전 조건

- TASK-016.01이 FailureContext/분류 contract를 제공한다.
- TASK-012.01 Meeting Slack webhook 경계가 유지된다.
- 설정 샘플/테스트에서 두 webhook URL이 분리되어 있다.

## 구현 단계

- [ ] Payload mapper tests를 먼저 작성한다: 네 incident type, optional session/stage, allowlist, unsafe text 정규화.
- [ ] Fake HTTP tests를 작성한다: 설정 누락/무효, HTTP 200 ok, 4xx/5xx, timeout/reset, request count=1.
- [ ] SLACK_ADMIN_WEBHOOK_URL 전용 config/port/adapter를 구현한다. Meeting adapter URL/client/payload는 재사용하지 않는다.
- [ ] 네 업무 failure boundary에서 notifier를 한 번 호출한다. notifier exception이 원 업무 오류를 덮지 않게 한다.
- [ ] failure recursion guard 및 best-effort execution을 연결하고 자동 retry loop가 없는지 검증한다.
- [ ] log/request/response redaction과 Admin/Meeting webhook 분리를 검토한다.
- [ ] Backend tests/build 및 docs/evidence/TASK-016.02.md 기록을 완료한다.

## 완료 확인

- 네 분류 payload 및 refusal/response ambiguity cases가 자동 검증된다.
- Admin Slack outcome에 관계없이 원 업무 failure result가 보존된다.
- git diff --check 및 secret/log 검토를 통과한다.

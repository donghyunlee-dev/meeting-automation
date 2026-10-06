# 작업 목록

## 사전 조건

- TASK-002.04 API-001/API-019 common response 및 선택 Provider Health가 준비되어 있다.
- TASK-011.01 Email health contributor와 TASK-012.01 Notification contributor가 준비되어 있다.
- 기존 Settings/Participants route, API client/query convention을 확인한다.

## 구현 단계

- [ ] API-001/API-019 DTO fixture 및 selector tests를 먼저 작성한다: common response, null provider, configured/reachable/root 조합, 미등록 contributor를 검증한다.
- [ ] Settings page tests를 작성한다: 병렬 요청, 전체/부분 실패, 성공 영역 보존, 영역별 retry, loading, 관리자 안내 및 no-secret UI를 검증하고 기대대로 실패하는지 확인한다.
- [ ] Typed API clients/query hooks를 구현해 두 endpoint를 독립 query key로 병렬 요청하고 필요 시 각각 refetch한다.
- [ ] Company/Document/Email/Notification view-model과 Settings cards를 구현한다. provider null은 연결 필요 안내로 매핑하고 Secret 입력이나 provider 자동 선택을 추가하지 않는다.
- [ ] Email/Slack reachable=false의 의미를 과장하지 않는 문구와 retry를 연결한다. Refresh가 GET만 발생시키는지 검증한다.
- [ ] 접근성, keyboard focus, 작은 모바일 layout, Participants entry/Bottom Navigation 보존을 확인한다.
- [ ] FE tests/lint/build를 실행하고 docs/evidence/TASK-015.01.md에 비민감 결과를 기록한다.

## 완료 확인

- 각 완료 기준에 자동화 또는 수동 evidence가 있다.
- 응답/DOM에는 Secret, Provider token, Root ID 또는 Provider 오류 본문이 없다.
- git diff --check 및 no-side-effect review를 통과한다.

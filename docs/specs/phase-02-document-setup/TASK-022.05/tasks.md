# ✅ 자료 이전과 활성 문서 서비스 전환 작업 목록

> TASK-022.05 · 선행: TASK-022.04, TASK-022.01
> GitHub Issue: [#100](https://github.com/donghyunlee-dev/meeting-automation/issues/100)

- [ ] **SWITCH-COPY** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **SWITCH-COPY**을 통과시킨다: 복사·참조·digest 검증 후 한 번만 활성화, 원본 삭제와 Email/Slack/STT 호출 0건.
- [ ] **SWITCH-EMPTY** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **SWITCH-EMPTY**을 통과시킨다: 원본 보존과 새 목록/빈 roster 안내, 준비된 대상만 활성화.
- [ ] **SWITCH-BUSY** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **SWITCH-BUSY**을 통과시킨다: 409 busy, 동시 Session 생성과 switch 중 정확히 하나만 lock 획득.
- [ ] **SWITCH-FAIL-RESTART** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **SWITCH-FAIL-RESTART**을 통과시킨다: source active 유지, INTERRUPTED 표시, 수동 재개 시 완료 item 재사용.
- [ ] **SWITCH-SOURCE-EDIT** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **SWITCH-SOURCE-EDIT**을 통과시킨다: 전환 완료 거절 및 재검증 요구; 변경 전 active 유지.
- [ ] **SWITCH-CANCEL-VERSION** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **SWITCH-CANCEL-VERSION**을 통과시킨다: 안전 지점 취소, 부분 target 보존, 412/409 충돌, 새 active cache와 ID 재조회.
- [ ] **SWITCH-CLEANUP** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **SWITCH-CLEANUP**을 통과시킨다: 불필요 credential 제거, 본문 spool 없음, 비민감 summary/map만 보존 후 정리.
- [ ] 관련 Provider/API/화면 회귀를 한 번 수행하고 저장된 Secret·원본 삭제·불필요 전달이 없는지 검증한다.
- [ ] commit/push 후 master 대상 PR을 열고 현재 head 리뷰와 적용 QA를 받는다.
- [ ] 리더가 정확한 SHA의 증거·병합·Issue cleanup·PRD DONE을 확인한다. 설계 완료와 구현 DONE을 혼동하지 않는다.

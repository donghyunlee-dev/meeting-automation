# ✅ 문서 서비스 변경과 자료 이전 화면 통합 검증 작업 목록

> TASK-022.06 · 선행: TASK-022.03, TASK-022.05
> GitHub Issue: [#101](https://github.com/donghyunlee-dev/meeting-automation/issues/101)

- [ ] **CHANGE-ENTRY** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **CHANGE-ENTRY**을 통과시킨다: Provider 선택부터 같은 위저드 재진입; 테스트 중 source 표시·활성 상태 유지.
- [ ] **CHANGE-CHOICE** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **CHANGE-CHOICE**을 통과시킨다: 복사 범위와 원본 보존 안내, 선택에 맞는 API-028 한 번 요청.
- [ ] **CHANGE-PROGRESS** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **CHANGE-PROGRESS**을 통과시킨다: 안전한 상태·재개/취소 안내; 불명확한 생성 자동 재시도 없음.
- [ ] **CHANGE-REFRESH** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **CHANGE-REFRESH**을 통과시킨다: 같은 operation을 조회, 중복 switch 방지, revision 충돌 재읽기.
- [ ] **CHANGE-INTEGRATION** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **CHANGE-INTEGRATION**을 통과시킨다: 설정/참석자 API 실통합과 Provider 복사본 read-back 검증, 새 roster 조회 및 후속 History 소비자 계약 fixture 검증.
- [ ] **CHANGE-GATE** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **CHANGE-GATE**을 통과시킨다: 참석자 실제 쓰기 차단과 새 roster 조회, 후속 Session/History 상태 gate 계약 fixture 검증, 로그인 화면 없음.
- [ ] **CHANGE-ACCESSIBILITY** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **CHANGE-ACCESSIBILITY**을 통과시킨다: 영역별 오류/재시도·focus·상태 알림, 키 저장·재노출 없음.
- [ ] 관련 Provider/API/화면 회귀를 한 번 수행하고 저장된 Secret·원본 삭제·불필요 전달이 없는지 검증한다.
- [ ] commit/push 후 master 대상 PR을 열고 현재 head 리뷰와 적용 QA를 받는다.
- [ ] 리더가 정확한 SHA의 증거·병합·Issue cleanup·PRD DONE을 확인한다. 설계 완료와 구현 DONE을 혼동하지 않는다.

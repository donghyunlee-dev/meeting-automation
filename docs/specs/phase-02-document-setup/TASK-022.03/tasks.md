# ✅ 최초 실행 연결 위저드와 공용 설정 화면 작업 목록

> TASK-022.03 · 선행: TASK-022.02
> GitHub Issue: [#98](https://github.com/donghyunlee-dev/meeting-automation/issues/98)

- [ ] **WIZARD-ENTRY** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **WIZARD-ENTRY**을 통과시킨다: 미설정의 회의/참석자 route 차단, READY 정상 진입, 저장소 오류 복구 안내.
- [ ] **WIZARD-GUIDE** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **WIZARD-GUIDE**을 통과시킨다: Provider별 필드·권한 가이드 표시; 다른 Provider의 이전 입력 제거.
- [ ] **WIZARD-TEST** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **WIZARD-TEST**을 통과시킨다: 필드 오류와 재시도, TESTED인 동일 revision만 완료 가능, 입력 변경 시 결과 무효.
- [ ] **WIZARD-FINISH** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **WIZARD-FINISH**을 통과시킨다: 준비될 페이지 구조 확인, 중복 클릭 방지, 완료 뒤 config/roster 재조회.
- [ ] **WIZARD-RESUME** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **WIZARD-RESUME**을 통과시킨다: credential 재노출 없이 public 상태 복원, 같은 operation 조회, 412 재읽기.
- [ ] **WIZARD-SECRET-A11Y** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **WIZARD-SECRET-A11Y**을 통과시킨다: 웹 저장소/URL/console에 키 없음, focus/label/error 연결, 가로 넘침 없음.
- [ ] 관련 Provider/API/화면 회귀를 한 번 수행하고 저장된 Secret·원본 삭제·불필요 전달이 없는지 검증한다.
- [ ] commit/push 후 master 대상 PR을 열고 현재 head 리뷰와 적용 QA를 받는다.
- [ ] 리더가 정확한 SHA의 증거·병합·Issue cleanup·PRD DONE을 확인한다. 설계 완료와 구현 DONE을 혼동하지 않는다.

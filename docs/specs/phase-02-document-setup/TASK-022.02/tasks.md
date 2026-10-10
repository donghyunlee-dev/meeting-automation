# ✅ Notion과 Confluence 기본 페이지 초기화 작업 목록

> TASK-022.02 · 선행: TASK-022.01
> GitHub Issue: [#97](https://github.com/donghyunlee-dev/meeting-automation/issues/97)

- [ ] **BOOT-NOTION** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **BOOT-NOTION**을 통과시킨다: Page로 루트·직속 두 child 생성, Database/Data Source 호출 0건.
- [ ] **BOOT-CONFLUENCE** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **BOOT-CONFLUENCE**을 통과시킨다: spaceId 및 parentId에 맞는 루트·두 child, space 권한 검증.
- [ ] **BOOT-REUSE** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **BOOT-REUSE**을 통과시킨다: 완전 구조 재사용; 부분 누락만 생성; 기존 문서와 root 이름 변경/삭제 없음.
- [ ] **BOOT-DUPLICATE** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **BOOT-DUPLICATE**을 통과시킨다: 구조 충돌로 실패; 임의 선택·새 중복 생성 없음.
- [ ] **BOOT-RECOVERY** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **BOOT-RECOVERY**을 통과시킨다: journal/marker 확인 후 같은 page 재사용; 불명확하면 RECONCILIATION_REQUIRED.
- [ ] **BOOT-ACTIVATION** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **BOOT-ACTIVATION**을 통과시킨다: 첫 연결만 완료 뒤 READY; 기존 active 보존; 실패는 활성화하지 않음.
- [ ] 관련 Provider/API/화면 회귀를 한 번 수행하고 저장된 Secret·원본 삭제·불필요 전달이 없는지 검증한다.
- [ ] commit/push 후 master 대상 PR을 열고 현재 head 리뷰와 적용 QA를 받는다.
- [ ] 리더가 정확한 SHA의 증거·병합·Issue cleanup·PRD DONE을 확인한다. 설계 완료와 구현 DONE을 혼동하지 않는다.

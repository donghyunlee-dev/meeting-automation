# ✅ 전역 문서 연결 설정 저장과 연결 테스트 작업 목록

> TASK-022.01 · 선행: TASK-002.04 (#9), TASK-001.04 (#4)
> GitHub Issue: [#96](https://github.com/donghyunlee-dev/meeting-automation/issues/96)

- [ ] **SETUP-EMPTY** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **SETUP-EMPTY**을 통과시킨다: version 0, UNCONFIGURED, provider null, setup.required true; 제품 로그인 없이 상태 조회.
- [ ] **SETUP-PERSIST** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **SETUP-PERSIST**을 통과시킨다: 정규화한 선택·자격 증명·revision 복원, 저장 파일에 평문 token/email 없음.
- [ ] **SETUP-STORAGE** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **SETUP-STORAGE**을 통과시킨다: STORAGE_UNAVAILABLE 및 저장 거절; 기존 파일 보존; 빈 설정으로 초기화하지 않음.
- [ ] **SETUP-CONCURRENCY** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **SETUP-CONCURRENCY**을 통과시킨다: 한 변경만 성공, 오래된 요청 412, 다른 payload 409, restart 뒤에도 결과 재사용.
- [ ] **SETUP-TEST** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **SETUP-TEST**을 통과시킨다: 정확한 읽기 테스트 결과; 페이지 쓰기 0건; 원문 오류/credential 미노출.
- [ ] **SETUP-SECRET** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **SETUP-SECRET**을 통과시킨다: 입력 검증/Origin 거절; GET 응답·로그·영속 평문에 Secret 없음; 쓰기 미검증은 UNVERIFIED.
- [ ] 관련 Provider/API/화면 회귀를 한 번 수행하고 저장된 Secret·원본 삭제·불필요 전달이 없는지 검증한다.
- [ ] commit/push 후 master 대상 PR을 열고 현재 head 리뷰와 적용 QA를 받는다.
- [ ] 리더가 정확한 SHA의 증거·병합·Issue cleanup·PRD DONE을 확인한다. 설계 완료와 구현 DONE을 혼동하지 않는다.

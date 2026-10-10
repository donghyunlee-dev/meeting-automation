# ✅ 문서 서비스 간 표준 자료 export와 import 작업 목록

> TASK-022.04 · 선행: TASK-022.02
> GitHub Issue: [#99](https://github.com/donghyunlee-dev/meeting-automation/issues/99)

- [ ] **TRANSFER-ROUNDTRIP** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **TRANSFER-ROUNDTRIP**을 통과시킨다: title/date/template/Minutes/Transcript/Speaker IDs가 정규화 후 동일.
- [ ] **TRANSFER-PARTICIPANTS** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **TRANSFER-PARTICIPANTS**을 통과시킨다: source ID별 별도 생성/재사용, Meeting/Speaker/owner 참조 정확한 map 적용.
- [ ] **TRANSFER-FAILURE-DOC** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **TRANSFER-FAILURE-DOC**을 통과시킨다: 실패 metadata 보존, 없는 Transcript/Minutes를 만들지 않음.
- [ ] **TRANSFER-PAGINATION** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **TRANSFER-PAGINATION**을 통과시킨다: manifest 완전성 확인, UI 최대 목록 길이로 종료하지 않음.
- [ ] **TRANSFER-CONFLICT** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **TRANSFER-CONFLICT**을 통과시킨다: preflight/충돌 오류; 덮어쓰기·조용한 누락 없음.
- [ ] **TRANSFER-IDEMPOTENCY** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다.
- [ ] 해당 최소 구현으로 **TRANSFER-IDEMPOTENCY**을 통과시킨다: 한 copy 재사용; 확인 불가시 reconciliation; 자동 반복 생성 없음.
- [ ] 관련 Provider/API/화면 회귀를 한 번 수행하고 저장된 Secret·원본 삭제·불필요 전달이 없는지 검증한다.
- [ ] commit/push 후 master 대상 PR을 열고 현재 head 리뷰와 적용 QA를 받는다.
- [ ] 리더가 정확한 SHA의 증거·병합·Issue cleanup·PRD DONE을 확인한다. 설계 완료와 구현 DONE을 혼동하지 않는다.

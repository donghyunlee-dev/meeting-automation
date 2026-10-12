# ✅ 문서 서비스 간 표준 자료 export와 import 작업 목록

> TASK-022.04 · 선행: TASK-022.02
> GitHub Issue: [#99](https://github.com/donghyunlee-dev/meeting-automation/issues/99)

## 자동 구현 및 검증

- [x] **TRANSFER-ROUNDTRIP** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다. `task-1-red.log`, `task-2-red.log`, `task-3-red.log`에서 기능/API 부재로 인한 RED를 기록했다.
- [x] 해당 최소 구현으로 **TRANSFER-ROUNDTRIP**을 통과시킨다: title/date/template/Minutes/Transcript/Speaker IDs가 정규화 후 동일. Codec, Provider round-trip 및 service 테스트 통과; 최종 범위별 실행은 `task-1-review-coverage.log`, `task-2-review-focused.log`, `task-3-review-fix-focused.log` 참조.
- [x] **TRANSFER-PARTICIPANTS** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다. 초기 adapter 부재, Participant 제목 정규화 및 CRUD marker 테스트의 RED는 `task-2-red.log`, `task-2-null-title-red.log`, `task-2-crud-red.log`에 기록했다.
- [x] 해당 최소 구현으로 **TRANSFER-PARTICIPANTS**을 통과시킨다: source ID별 별도 생성/재사용, Meeting/Speaker/owner 참조 정확한 map 적용. Adapter/service 테스트와 stale participant-map 회귀 테스트 통과; `task-2-review-focused.log`, `task-3-review-fix-test.log` 참조.
- [x] **TRANSFER-FAILURE-DOC** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다. 구현 전 codec API 부재 RED는 `task-1-red.log`, adapter 부재 RED는 `task-2-red.log`에 기록했다.
- [x] 해당 최소 구현으로 **TRANSFER-FAILURE-DOC**을 통과시킨다: 실패 metadata 보존, 없는 Transcript/Minutes를 만들지 않음. Codec, Provider 및 service 회귀 테스트에서 확인; `task-1-review-coverage.log`, `task-2-review-focused.log`, `task-3-review-fix-focused.log` 참조.
- [x] **TRANSFER-PAGINATION** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다. Adapter 부재 RED 및 커서/페이지 경계 RED는 `task-2-red.log`, `task-2-final-edges-red.log`에 기록했다.
- [x] 해당 최소 구현으로 **TRANSFER-PAGINATION**을 통과시킨다: manifest 완전성 확인, UI 최대 목록 길이로 종료하지 않음. Provider pagination/opaque cursor 테스트 통과; `task-2-review-focused.log` 참조.
- [x] **TRANSFER-CONFLICT** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다. 서비스 API 부재 RED 및 재사용 meeting map preflight RED는 `task-3-red.log`, `task-3-review-fix-red.log`에 기록했다.
- [x] 해당 최소 구현으로 **TRANSFER-CONFLICT**을 통과시킨다: preflight/충돌 오류; 덮어쓰기·조용한 누락 없음. Service conflict 및 무기록(no-write) 검증, stale-map preflight 검증 통과; `task-3-review-fix-focused.log`, `task-3-review-fix-test.log` 참조.
- [x] **TRANSFER-IDEMPOTENCY** 실패 테스트를 작성하고 기대한 이유로 실패하는지 기록한다. 서비스 API 부재 RED는 `task-3-red.log`에 기록했다.
- [x] 해당 최소 구현으로 **TRANSFER-IDEMPOTENCY**을 통과시킨다: 한 copy 재사용; 확인 불가시 reconciliation; 자동 반복 생성 없음. Service 재사용/ambiguous outcome 테스트 통과; `task-3-review-fix-focused.log` 참조.
- [ ] 관련 Provider/API/화면 회귀를 한 번 수행하고 저장된 Secret·원본 삭제·불필요 전달이 없는지 검증한다. API 및 Provider 자동 회귀는 완료했지만, 전체 보안·데이터 취급 검토의 최종 확인은 독립 review/QA 단계에 남긴다.

## 외부 릴리스 및 완료 게이트

- [ ] commit/push 후 master 대상 PR을 열고 현재 head 리뷰와 적용 QA를 받는다.
- [ ] 리더가 정확한 SHA의 증거·병합·Issue cleanup·PRD DONE을 확인한다. 설계 완료와 구현 DONE을 혼동하지 않는다.

> 테스트 및 구현 체크는 자동 검증만 반영한다. 실제 Provider 수동 QA, PR/review/QA, merge, Issue cleanup, verification evidence 및 PRD 완료 기록은 완료된 것으로 표시하지 않는다.

> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

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
- [x] 관련 Provider/API/화면 회귀를 한 번 수행하고 저장된 Secret·원본 삭제·불필요 전달이 없는지 검증한다. backend/API 작업이라 화면 회귀는 N/A이며, 자동 테스트와 양방향 실 Provider QA에서 원본 보존 및 synthetic 데이터만 전송하는 동작을 확인했다.

## 외부 릴리스 및 완료 게이트

- [x] commit/push 후 master 대상 PR을 열고 현재 head 리뷰와 적용 QA를 받는다. PR [#108](https://github.com/donghyunlee-dev/meeting-automation/pull/108), exact head `48b667e3a54b6fb7820eb69db6e2a845b2ab93be`; exact-head review 및 양방향 실 Provider QA 통과.
- [x] 리더가 정확한 SHA의 증거·병합·Issue cleanup·PRD DONE을 확인한다. squash merge `375897a486592adc5ea8f87c1d5e9f2a93e085b9`; Issue #99을 완료 처리하고 PRD 개발 tracker를 갱신했다.

> 자동 검증, 실제 Provider QA, PR/review/CI/merge, Issue cleanup 및 PRD 완료 기록은 exact-head 검증 증거에 기록한다. 자격 증명과 실제 사용자 회의 내용은 코드와 Evidence에 포함하지 않는다.

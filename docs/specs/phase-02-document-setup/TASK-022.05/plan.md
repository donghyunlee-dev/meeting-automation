# 🛠️ 자료 이전과 활성 문서 서비스 전환 구현 계획

> TASK-022.05 · PRD v1.9.0 · 선행: TASK-022.04, TASK-022.01
> GitHub Issue: [#100](https://github.com/donghyunlee-dev/meeting-automation/issues/100)

## 🧩 변경 범위와 책임

DocumentSwitchUseCase, MigrationCoordinator, 전역 상태 store/journal, operation retry/cancel, Session/Participant/Publish/Delivery 연결 gate

Backend가 Port/유스케이스/Adapter와 unit·계약 테스트를 소유한다. 공개 API와 durable 상태를 먼저 완성해 후속 UI가 임의 상태를 만들지 않게 한다.

## 🔌 인터페이스

활성 Session·진행 쓰기가 없을 때만 전환 lock을 얻는다. COPY_ALL은 preflight→참석자→회의록→read-back→source 재검증→원자 activation 순서다. START_EMPTY는 복사 없이 확인된 대상 활성화다. 실패·INTERRUPTED에서 active는 원본이고 신규 쓰기는 재개/취소 전까지 차단한다. 취소는 현재 외부 요청 결과 조정 후 lock을 해제한다.

## 🧭 구현 순서

- spec의 수용 기준별 실패 fixture와 테스트를 먼저 추가하고 실패 원인을 확인한다.
- DocumentSwitchUseCase, MigrationCoordinator, 전역 상태 store/journal, operation retry/cancel, Session/Participant/Publish/Delivery 연결 gate에 필요한 최소 변경을 적용한다. 다른 진행자의 수정이나 기존 검증 기록을 되돌리지 않는다.
- 상태/멱등/오류/보안 회귀를 통과시키고 공통 명세와 실제 요청·응답을 대조한다.
- 선행/후속 계약을 연결하고 read-only Health, Secret 조회 미노출과 원본 보존을 확인한다.
- 현재 head의 테스트·리뷰·적용 가능한 QA를 마친 뒤 리더가 master 병합과 Evidence를 확정한다.

## 🔎 검증

[test.md](./test.md)의 각 사례를 수행한다. 구현·테스트 명령·정확한 SHA·수동 QA 결과는 docs/evidence/TASK-022.05/verification.md에 기록한다. 불가능한 실제 credential/배포 검증은 BLOCKED로 표시한다.

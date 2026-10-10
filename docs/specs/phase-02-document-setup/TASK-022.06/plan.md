# 🛠️ 문서 서비스 변경과 자료 이전 화면 통합 검증 구현 계획

> TASK-022.06 · PRD v1.9.0 · 선행: TASK-022.03, TASK-022.05
> GitHub Issue: [#101](https://github.com/donghyunlee-dev/meeting-automation/issues/101)

## 🧩 변경 범위와 책임

Frontend Settings 문서 연결 row, wizard switching mode, MigrationProgress, operation polling, cache invalidation, browser QA 시나리오

Frontend가 화면·typed client·컴포넌트 테스트를 소유한다. 검증된 Backend 계약을 먼저 소비한 뒤 실제 브라우저 QA로 연결을 확인한다. 혼합 작업에서 QA는 읽기 검증/결함 보고를 소유하고 리더가 로컬 서비스와 병합을 맡는다.

## 🔌 인터페이스

변경 버튼은 계정/관리자 로그인 없이 배포 접근 경계 안에서 제공한다. 선택 화면은 source 보존과 COPY_ALL 범위·START_EMPTY 결과를 설명한다. 기존 active 요약과 진행 counts를 표시하며 credential/회의 본문을 progress 응답에서 요구하지 않는다. terminal/화면 이탈시 polling을 종료하고 다시 진입시 작업을 복원한다.

## 🧭 구현 순서

이 시점의 실통합 범위는 API-001/003~005/019/023~030과 Provider의 초기 구조·전체 복사·read-back이다. 아직 구현되지 않은 API-006/015~018/022와 녹음 화면은 후속 소비자 계약 fixture로만 검증한다. History cache 무효화 이벤트/새 connectionVersion 계약은 이 단계에서 검증하고 실제 목록 API 연결은 TASK-014에서 검증한다. 존재하지 않는 endpoint를 호출한 404를 이 작업의 구현 장애로 판정하지 않는다.

- spec의 수용 기준별 실패 fixture와 테스트를 먼저 추가하고 실패 원인을 확인한다.
- Frontend Settings 문서 연결 row, wizard switching mode, MigrationProgress, operation polling, cache invalidation, browser QA 시나리오에 필요한 최소 변경을 적용한다. 다른 진행자의 수정이나 기존 검증 기록을 되돌리지 않는다.
- 상태/멱등/오류/보안 회귀를 통과시키고 공통 명세와 실제 요청·응답을 대조한다.
- 선행/후속 계약을 연결하고 read-only Health, Secret 조회 미노출과 원본 보존을 확인한다.
- 현재 head의 테스트·리뷰·적용 가능한 QA를 마친 뒤 리더가 master 병합과 Evidence를 확정한다.

## 🔎 검증

[test.md](./test.md)의 각 사례를 수행한다. 구현·테스트 명령·정확한 SHA·수동 QA 결과는 docs/evidence/TASK-022.06/verification.md에 기록한다. 불가능한 실제 credential/배포 검증은 BLOCKED로 표시한다.

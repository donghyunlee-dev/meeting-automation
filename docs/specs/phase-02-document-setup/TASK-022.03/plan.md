# 🛠️ 최초 실행 연결 위저드와 공용 설정 화면 구현 계획

> TASK-022.03 · PRD v1.9.0 · 선행: TASK-022.02
> GitHub Issue: [#98](https://github.com/donghyunlee-dev/meeting-automation/issues/98)

## 🧩 변경 범위와 책임

Frontend App routes, SetupGate, DocumentSetupWizard, ProviderGuide, typed setup API client, 기존 NewMeeting form/App Config 소비 코드

Frontend가 화면·typed client·컴포넌트 테스트를 소유한다. 검증된 Backend 계약을 먼저 소비한 뒤 실제 브라우저 QA로 연결을 확인한다. 혼합 작업에서 QA는 읽기 검증/결함 보고를 소유하고 리더가 로컬 서비스와 병합을 맡는다.

## 🔌 인터페이스

/setup/document와 /settings/document route를 제공한다. 미설정만 최초 위저드로 강제 이동하고 일시 Provider 장애는 Settings 복구 안내로 처리한다. 연결 키는 password 입력의 메모리에만 두고 단계 이탈·성공·새로고침 시 비운다. 완료 요청만 초기화하며 응답 유실 뒤 operationId로 재조회한다.

## 🧭 구현 순서

- spec의 수용 기준별 실패 fixture와 테스트를 먼저 추가하고 실패 원인을 확인한다.
- Frontend App routes, SetupGate, DocumentSetupWizard, ProviderGuide, typed setup API client, 기존 NewMeeting form/App Config 소비 코드에 필요한 최소 변경을 적용한다. 다른 진행자의 수정이나 기존 검증 기록을 되돌리지 않는다.
- 상태/멱등/오류/보안 회귀를 통과시키고 공통 명세와 실제 요청·응답을 대조한다.
- 선행/후속 계약을 연결하고 read-only Health, Secret 조회 미노출과 원본 보존을 확인한다.
- 현재 head의 테스트·리뷰·적용 가능한 QA를 마친 뒤 리더가 master 병합과 Evidence를 확정한다.

## 🔎 검증

[test.md](./test.md)의 각 사례를 수행한다. 구현·테스트 명령·정확한 SHA·수동 QA 결과는 docs/evidence/TASK-022.03/verification.md에 기록한다. 불가능한 실제 credential/배포 검증은 BLOCKED로 표시한다.

# 🛠️ 전역 문서 연결 설정 저장과 연결 테스트 구현 계획

> TASK-022.01 · PRD v1.9.0 · 선행: TASK-002.04 (#9), TASK-001.04 (#4)
> GitHub Issue: [#96](https://github.com/donghyunlee-dev/meeting-automation/issues/96)

## 🧩 변경 범위와 책임

Backend configuration/application/document, DocumentProviderResolver, AppConfigController, IntegrationHealth, 새 전역 설정 저장 Port와 파일 Adapter; backend 환경 샘플 및 setup 가이드

Backend가 Port/유스케이스/Adapter와 unit·계약 테스트를 소유한다. 공개 API와 durable 상태를 먼저 완성해 후속 UI가 임의 상태를 만들지 않게 한다.

## 🔌 인터페이스

API-023의 version 0/UNCONFIGURED와 STORAGE_UNAVAILABLE를 구분한다. API-024는 If-Match 및 Idempotency-Key로 draft를 저장하며 credentials는 password 입력에서 요청으로만 전달한다. API-025는 읽기 전용 테스트이고 draft revision에 묶인 10분 유효 결과를 저장한다. 설정되지 않은 env를 활성 연결로 fallback하지 않는다.

## 🧭 구현 순서

- spec의 수용 기준별 실패 fixture와 테스트를 먼저 추가하고 실패 원인을 확인한다.
- Backend configuration/application/document, DocumentProviderResolver, AppConfigController, IntegrationHealth, 새 전역 설정 저장 Port와 파일 Adapter; backend 환경 샘플 및 setup 가이드에 필요한 최소 변경을 적용한다. 다른 진행자의 수정이나 기존 검증 기록을 되돌리지 않는다.
- 상태/멱등/오류/보안 회귀를 통과시키고 공통 명세와 실제 요청·응답을 대조한다.
- 선행/후속 계약을 연결하고 read-only Health, Secret 조회 미노출과 원본 보존을 확인한다.
- 현재 head의 테스트·리뷰·적용 가능한 QA를 마친 뒤 리더가 master 병합과 Evidence를 확정한다.

## 🔎 검증

[test.md](./test.md)의 각 사례를 수행한다. 구현·테스트 명령·정확한 SHA·수동 QA 결과는 docs/evidence/TASK-022.01/verification.md에 기록한다. 불가능한 실제 credential/배포 검증은 BLOCKED로 표시한다.

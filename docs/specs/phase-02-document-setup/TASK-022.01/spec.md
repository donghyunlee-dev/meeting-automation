# ⚙️ 전역 문서 연결 설정 저장과 연결 테스트

> PRD v1.9.0 · 2026-10-10 · TASK-022.01 · 영역 BE
> GitHub Issue: [#96](https://github.com/donghyunlee-dev/meeting-automation/issues/96)

## 🎯 결과

서비스 공통 연결 설정을 암호화한 영속 파일로 저장하고, 계정 없이 draft 생성·검증과 활성 연결 조회를 제공한다. 기존 환경변수 Resolver를 저장된 active snapshot 기반 Resolver로 변경한다.

## 🔗 선행과 계약

- 선행: TASK-002.04 (#9), TASK-001.04 (#4)
- 관련: DEC-021~024, FR-031~034, API-001, API-019, API-023~025
- 공통 불변식: [문서 연결·이전 설계](../../../product/document-setup.md)

API-023의 version 0/UNCONFIGURED와 STORAGE_UNAVAILABLE를 구분한다. API-024는 If-Match 및 Idempotency-Key로 draft를 저장하며 credentials는 password 입력에서 요청으로만 전달한다. API-025는 읽기 전용 테스트이고 draft revision에 묶인 10분 유효 결과를 저장한다. 설정되지 않은 env를 활성 연결로 fallback하지 않는다.

## ✅ 수용 기준

- **SETUP-EMPTY**: version 0, UNCONFIGURED, provider null, setup.required true; 제품 로그인 없이 상태 조회.
- **SETUP-PERSIST**: 정규화한 선택·자격 증명·revision 복원, 저장 파일에 평문 token/email 없음.
- **SETUP-STORAGE**: STORAGE_UNAVAILABLE 및 저장 거절; 기존 파일 보존; 빈 설정으로 초기화하지 않음.
- **SETUP-CONCURRENCY**: 한 변경만 성공, 오래된 요청 412, 다른 payload 409, restart 뒤에도 결과 재사용.
- **SETUP-TEST**: 정확한 읽기 테스트 결과; 페이지 쓰기 0건; 원문 오류/credential 미노출.
- **SETUP-SECRET**: 입력 검증/Origin 거절; GET 응답·로그·영속 평문에 Secret 없음; 쓰기 미검증은 UNVERIFIED.

## 📐 경계

제품 계정·로그인·사용자별 설정, 원본 삭제, 이전에 의한 메일/Slack 재전송, 임의 provider 데이터/첨부/댓글/버전 전체 복제는 추가하지 않는다. 이 문서는 구현 완료 증거가 아니다. 이미 DONE인 기반 작업을 재개방하지 않고 새 변경 작업으로 적용한다.

## 📎 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

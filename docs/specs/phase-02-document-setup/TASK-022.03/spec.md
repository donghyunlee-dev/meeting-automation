# ⚙️ 최초 실행 연결 위저드와 공용 설정 화면

> PRD v1.9.0 · 2026-10-10 · TASK-022.03 · 영역 FE
> GitHub Issue: [#98](https://github.com/donghyunlee-dev/meeting-automation/issues/98)

## 🎯 결과

앱 첫 진입을 전역 설정 상태로 gate하고 Notion/Confluence 선택·가이드·입력·연결 테스트·완료 구조 확인·초기화 진행을 하나의 재사용 위저드로 제공한다. 제품 로그인/사용자 프로필은 추가하지 않는다.

## 🔗 선행과 계약

- 선행: TASK-022.02
- 관련: DEC-021~024, FR-031~034, API-001, API-023~027
- 공통 불변식: [문서 연결·이전 설계](../../../product/document-setup.md)

/setup/document와 /settings/document route를 제공한다. 미설정만 최초 위저드로 강제 이동하고 일시 Provider 장애는 Settings 복구 안내로 처리한다. 연결 키는 password 입력의 메모리에만 두고 단계 이탈·성공·새로고침 시 비운다. 완료 요청만 초기화하며 응답 유실 뒤 operationId로 재조회한다.

## ✅ 수용 기준

- **WIZARD-ENTRY**: 미설정의 회의/참석자 route 차단, READY 정상 진입, 저장소 오류 복구 안내.
- **WIZARD-GUIDE**: Provider별 필드·권한 가이드 표시; 다른 Provider의 이전 입력 제거.
- **WIZARD-TEST**: 필드 오류와 재시도, TESTED인 동일 revision만 완료 가능, 입력 변경 시 결과 무효.
- **WIZARD-FINISH**: 준비될 페이지 구조 확인, 중복 클릭 방지, 완료 뒤 config/roster 재조회.
- **WIZARD-RESUME**: credential 재노출 없이 public 상태 복원, 같은 operation 조회, 412 재읽기.
- **WIZARD-SECRET-A11Y**: 웹 저장소/URL/console에 키 없음, focus/label/error 연결, 가로 넘침 없음.

## 📐 경계

제품 계정·로그인·사용자별 설정, 원본 삭제, 이전에 의한 메일/Slack 재전송, 임의 provider 데이터/첨부/댓글/버전 전체 복제는 추가하지 않는다. 이 문서는 구현 완료 증거가 아니다. 이미 DONE인 기반 작업을 재개방하지 않고 새 변경 작업으로 적용한다.

## 📎 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

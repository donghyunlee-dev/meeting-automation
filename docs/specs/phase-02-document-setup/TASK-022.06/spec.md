# ⚙️ 문서 서비스 변경과 자료 이전 화면 통합 검증

> PRD v1.9.0 · 2026-10-10 · TASK-022.06 · 영역 FE, QA
> GitHub Issue: [#101](https://github.com/donghyunlee-dev/meeting-automation/issues/101)

## 🎯 결과

Settings에서 같은 위저드를 재사용해 새 연결 준비·전체 복사/새 시작 선택·진행·실패 재개·취소·완료를 제공한다. 최초 설정과 양방향 변경을 통합 검증한 뒤 기존 회의 생성 개발을 재개할 수 있게 한다.

## 🔗 선행과 계약

- 선행: TASK-022.03, TASK-022.05
- 관련: DEC-021~024, FR-031~034, API-023~030, SCR-011, SCR-013, SCR-014
- 공통 불변식: [문서 연결·이전 설계](../../../product/document-setup.md)

변경 버튼은 계정/관리자 로그인 없이 배포 접근 경계 안에서 제공한다. 선택 화면은 source 보존과 COPY_ALL 범위·START_EMPTY 결과를 설명한다. 기존 active 요약과 진행 counts를 표시하며 credential/회의 본문을 progress 응답에서 요구하지 않는다. terminal/화면 이탈시 polling을 종료하고 다시 진입시 작업을 복원한다.

## ✅ 수용 기준

- **CHANGE-ENTRY**: Provider 선택부터 같은 위저드 재진입; 테스트 중 source 표시·활성 상태 유지.
- **CHANGE-CHOICE**: 복사 범위와 원본 보존 안내, 선택에 맞는 API-028 한 번 요청.
- **CHANGE-PROGRESS**: 안전한 상태·재개/취소 안내; 불명확한 생성 자동 재시도 없음.
- **CHANGE-REFRESH**: 같은 operation을 조회, 중복 switch 방지, revision 충돌 재읽기.
- **CHANGE-INTEGRATION**: 기본 구조·자료·참조·source 보존 검증, 완료 뒤 새 roster/history 조회.
- **CHANGE-GATE**: 신규 회의/참석자 쓰기 차단 및 최신 목록 재조회, 로그인 화면 없음.
- **CHANGE-ACCESSIBILITY**: 영역별 오류/재시도·focus·상태 알림, 키 저장·재노출 없음.

## 📐 경계

제품 계정·로그인·사용자별 설정, 원본 삭제, 이전에 의한 메일/Slack 재전송, 임의 provider 데이터/첨부/댓글/버전 전체 복제는 추가하지 않는다. 이 문서는 구현 완료 증거가 아니다. 이미 DONE인 기반 작업을 재개방하지 않고 새 변경 작업으로 적용한다.

## 📎 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

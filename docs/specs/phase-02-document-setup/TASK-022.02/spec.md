# ⚙️ Notion과 Confluence 기본 페이지 초기화

> PRD v1.9.0 · 2026-10-10 · TASK-022.02 · 영역 BE
> GitHub Issue: [#97](https://github.com/donghyunlee-dev/meeting-automation/issues/97)

## 🎯 결과

완료 버튼의 명시적 초기화 요청으로 부모 위치 아래 Meeting Automation/Meetings/Participants를 생성하거나 확인된 기존 루트를 재사용한다. 최초 연결에만 활성화하고 운영 중 draft 준비는 active를 바꾸지 않는다.

## 🔗 선행과 계약

- 선행: TASK-022.01
- 관련: DEC-021~024, FR-031~034, API-026, API-027
- 공통 불변식: [문서 연결·이전 설계](../../../product/document-setup.md)

initializeStructure는 discoverStructure와 분리한다. API-026은 테스트된 동일 draft revision만 허용하고 202 operation을 반환한다. API-027은 public summary만 반환한다. 신규 최초 연결은 페이지 확인 후 active/version을 원자적으로 교체한다. Confluence space 최상위 또는 parentId 아래, Notion 지정 부모 아래 생성한다.

## ✅ 수용 기준

- **BOOT-NOTION**: Page로 루트·직속 두 child 생성, Database/Data Source 호출 0건.
- **BOOT-CONFLUENCE**: spaceId 및 parentId에 맞는 루트·두 child, space 권한 검증.
- **BOOT-REUSE**: 완전 구조 재사용; 부분 누락만 생성; 기존 문서와 root 이름 변경/삭제 없음.
- **BOOT-DUPLICATE**: 구조 충돌로 실패; 임의 선택·새 중복 생성 없음.
- **BOOT-RECOVERY**: journal/marker 확인 후 같은 page 재사용; 불명확하면 RECONCILIATION_REQUIRED.
- **BOOT-ACTIVATION**: 첫 연결만 완료 뒤 READY; 기존 active 보존; 실패는 활성화하지 않음.

## 📐 경계

제품 계정·로그인·사용자별 설정, 원본 삭제, 이전에 의한 메일/Slack 재전송, 임의 provider 데이터/첨부/댓글/버전 전체 복제는 추가하지 않는다. 이 문서는 구현 완료 증거가 아니다. 이미 DONE인 기반 작업을 재개방하지 않고 새 변경 작업으로 적용한다.

## 📎 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

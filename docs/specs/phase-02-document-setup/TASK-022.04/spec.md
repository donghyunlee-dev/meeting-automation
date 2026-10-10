# ⚙️ 문서 서비스 간 표준 자료 export와 import

> PRD v1.9.0 · 2026-10-10 · TASK-022.04 · 영역 BE
> GitHub Issue: [#99](https://github.com/donghyunlee-dev/meeting-automation/issues/99)

## 🎯 결과

양쪽 Adapter가 앱 관리 회의록·Transcript·실패 문서·참석자를 표준 모델로 읽고 반대 Provider에 복사하는 전송 Port를 구현한다. 이후 Publish/History가 같은 문서 codec을 재사용하도록 계약을 고정한다.

## 🔗 선행과 계약

- 선행: TASK-022.02
- 관련: DEC-021~024, FR-031~034, EXT-003, API-017, API-018의 공유 Adapter 기반
- 공통 불변식: [문서 연결·이전 설계](../../../product/document-setup.md)

readManifest는 pagination을 완료하고 source revision/digest를 반환한다. exportMeeting은 schemaVersion 검증을 수행한다. importParticipant/importMeeting은 생성 payload에 transferKey·canonical digest를 기록한다. 기존 target가 같은 키·같은 digest인 경우만 재사용한다. participant ID map으로 mapping/owner 참조를 변경하고 externalSessionId를 보존한다.

## ✅ 수용 기준

- **TRANSFER-ROUNDTRIP**: title/date/template/Minutes/Transcript/Speaker IDs가 정규화 후 동일.
- **TRANSFER-PARTICIPANTS**: source ID별 별도 생성/재사용, Meeting/Speaker/owner 참조 정확한 map 적용.
- **TRANSFER-FAILURE-DOC**: 실패 metadata 보존, 없는 Transcript/Minutes를 만들지 않음.
- **TRANSFER-PAGINATION**: manifest 완전성 확인, UI 최대 목록 길이로 종료하지 않음.
- **TRANSFER-CONFLICT**: preflight/충돌 오류; 덮어쓰기·조용한 누락 없음.
- **TRANSFER-IDEMPOTENCY**: 한 copy 재사용; 확인 불가시 reconciliation; 자동 반복 생성 없음.

## 📐 경계

제품 계정·로그인·사용자별 설정, 원본 삭제, 이전에 의한 메일/Slack 재전송, 임의 provider 데이터/첨부/댓글/버전 전체 복제는 추가하지 않는다. 이 문서는 구현 완료 증거가 아니다. 이미 DONE인 기반 작업을 재개방하지 않고 새 변경 작업으로 적용한다.

## 📎 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

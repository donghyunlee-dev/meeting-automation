# ⚙️ 자료 이전과 활성 문서 서비스 전환

> PRD v1.9.0 · 2026-10-10 · TASK-022.05 · 영역 BE
> GitHub Issue: [#100](https://github.com/donghyunlee-dev/meeting-automation/issues/100)

## 🎯 결과

전체 복사 또는 새 서비스 시작을 실행하고 검증 후 활성 연결을 원자적으로 전환한다. durable journal로 재시작·부분 실패를 복구하고 source 보존·전환 lock·cache 무효화를 보장한다.

## 🔗 선행과 계약

- 선행: TASK-022.04, TASK-022.01
- 관련: DEC-021~024, FR-031~034, API-028~030, API-027, API-006 gate
- 공통 불변식: [문서 연결·이전 설계](../../../product/document-setup.md)

활성 Session·진행 쓰기가 없을 때만 전환 lock을 얻는다. COPY_ALL은 preflight→참석자→회의록→read-back→source 재검증→원자 activation 순서다. START_EMPTY는 복사 없이 확인된 대상 활성화다. 실패·INTERRUPTED에서 active는 원본이고 신규 쓰기는 재개/취소 전까지 차단한다. 취소는 현재 외부 요청 결과 조정 후 lock을 해제한다.

## ✅ 수용 기준

- **SWITCH-COPY**: 복사·참조·digest 검증 후 한 번만 활성화, 원본 삭제와 Email/Slack/STT 호출 0건.
- **SWITCH-EMPTY**: 원본 보존과 새 목록/빈 roster 안내, 준비된 대상만 활성화.
- **SWITCH-BUSY**: 409 busy, 동시 Session 생성과 switch 중 정확히 하나만 lock 획득.
- **SWITCH-FAIL-RESTART**: source active 유지, INTERRUPTED 표시, 수동 재개 시 완료 item 재사용.
- **SWITCH-SOURCE-EDIT**: 전환 완료 거절 및 재검증 요구; 변경 전 active 유지.
- **SWITCH-CANCEL-VERSION**: 안전 지점 취소, 부분 target 보존, 412/409 충돌, 새 active cache와 ID 재조회.
- **SWITCH-CLEANUP**: 불필요 credential 제거, 본문 spool 없음, 비민감 summary/map만 보존 후 정리.

## 📐 경계

제품 계정·로그인·사용자별 설정, 원본 삭제, 이전에 의한 메일/Slack 재전송, 임의 provider 데이터/첨부/댓글/버전 전체 복제는 추가하지 않는다. 이 문서는 구현 완료 증거가 아니다. 이미 DONE인 기반 작업을 재개방하지 않고 새 변경 작업으로 적용한다.

## 📎 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

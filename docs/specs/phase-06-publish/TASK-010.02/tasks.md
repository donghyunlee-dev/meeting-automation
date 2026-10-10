# 구현 작업 목록

> 📌 v1.9.0 변경 계약: TASK-022.04/.05의 공통 문서 codec과 활성 connection snapshot을 사용한다. 전환 lock 중 Publish를 차단하며 export/import와 같은 metadata/본문 round-trip 계약을 따른다. 이전으로 전달을 재발송하지 않는다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

## 사전 조건

- [ ] TASK-010.01 Issue #43 완료 결과에서 Session `CONFIRMED`, version, `allowedActions` 계약을 확인한다.
- [ ] Backend 실제 package layout, 공통 response/error/idempotency 구현, DocumentProvider Port와 API-010 snapshot 모델을 확인한다.
- [ ] 테스트 명령과 비동기 worker 실행 방식이 현재 V1 구성과 일치하는지 확인하고 결정 사항을 Issue #44에 남긴다.

## 구현 단계

- [ ] API-015 유효 요청, malformed body/header, 미존재 Session, 잘못된 state, stale version, roster 밖 수신자 입력의 실패 사례를 먼저 작성한다. 각 사례의 기대 HTTP/error/status/version을 assertion으로 고정한다.
- [ ] 같은 key replay, payload/key conflict, 다른 key 동시 요청의 실패/경합 재현 사례를 작성하고 중복 작업 등록이 검출되는지 확인한다.
- [ ] Provider fake로 existing document, no document, lookup failure, create success/failure 사례를 우선 작성하고 예상 호출 순서를 고정한다.
- [ ] API-015 최소 입력 검증 및 공통 `202` envelope 구현 후 동일 fingerprint replay와 상충 fingerprint 409을 통과시킨다.
- [ ] Session 상태/version 변경과 publish request fingerprint 등록을 원자 처리해 서로 다른 key 요청도 한 건만 접수되게 한다.
- [ ] accepted snapshot으로 `CreateMeetingCommand`를 만들고 Session ID metadata를 포함한다.
- [ ] `findMeetingBySessionId` 선행 후 기존 참조 재사용 또는 `createMeeting` 실행을 구현한다. lookup 오류는 create 우회 없이 실패시킨다.
- [ ] 문서 참조를 Session에 기록하고 성공/실패 상태를 API-010 결과로 매핑한다. Provider failure는 `DOCUMENT_FAILED`; downstream Email/Notification invocation은 0회다.
- [ ] 기존 오류 redaction, trace correlation, API-010/common envelope를 검증하고 필요한 회귀 확인을 실행한다.
- [ ] 자동화 결과와 수동 QA 결과를 증거로 정리해 Issue #44에 기록한다.

## 검토 완료 조건

- 모든 구현 단계는 해당 자동화 assertion 또는 수동 절차 결과와 연결된다.
- 같은 Session으로 문서가 두 개 생기는 테스트가 없다.
- 동기 API 응답은 접수 상태이고, 최종 상태/document reference는 API-010으로 확인된다.
- 후속 작업의 소유 경계가 지켜지고 secrets/provider raw payload가 노출되지 않는다.

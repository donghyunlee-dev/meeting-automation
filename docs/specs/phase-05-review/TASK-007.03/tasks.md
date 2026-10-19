> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# Speaker mapping 저장 통합 작업 항목

관련 Issue: [#36](https://github.com/donghyunlee-dev/meeting-automation/issues/36).

1. API-010 version/allowedActions snapshot, API-011 full replacement DTO, Review draft owner와 Transcript speakerId join을 대조한다. 완료 증거: FE/BE integration field mapping을 PR에 기록한다.
2. clean/dirty submit, full body/If-Match, success/cache update, 412, provider/validation error, network timeout/reconcile tests를 먼저 작성한다. 완료 증거: 저장 경계와 실패 보존 test가 구현 전 실패한다.
3. dirty draft만 전체 API-011 request로 제출하고 loading 중 duplicate submit을 막는다. 완료 증거: 한 번의 action은 한 mutation이며 현재 snapshot version을 쓴다.
4. 성공 response mapping/version을 committed base와 API-010 cache에 반영하고 Transcript view model을 갱신한다. 완료 증거: 같은 speakerId의 모든 segment label이 함께 바뀌고 text/time은 같다.
5. 실패에서 base mapping을 유지하고 draft를 보존하며 412 최신 snapshot reconcile을 구현한다. 완료 증거: user draft 자동 삭제/강제 덮어쓰기가 없다.
6. 네트워크 전송 결과가 불명확하면 API-010 GET 후 비교하고, 확정되지 않은 경우 자동 PUT retry를 하지 않는다. 완료 증거: 저장 결과 일치/불일치/GET 실패가 각각 안전하게 처리된다.
7. provider-null Settings guard, read-after-write, Frontend tests/lint/build 및 통합 QA를 수행한다. API-010/API-011 선행 구현은 소비하되 Backend 코드는 수정하지 않는다. 통합 과정에서 Backend 결함이 확인돼 실제 수정이 필요하면 그 변경에 직접 관련된 Backend 테스트만 선택한다. 완료 증거: 사용 명령과 evidence를 Issue에 남긴다.

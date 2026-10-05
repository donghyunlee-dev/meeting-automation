# Speaker mapping 저장 통합 작업 항목

관련 Issue: [#36](https://github.com/donghyunlee-dev/meeting-automation/issues/36).

1. API-010 version/allowedActions snapshot, API-011 full replacement DTO, Review draft owner와 Transcript speakerId join을 대조한다. 완료 증거: FE/BE integration field mapping을 PR에 기록한다.
2. clean/dirty submit, full body/If-Match, success/cache update, 412, provider/validation error, network timeout/reconcile tests를 먼저 작성한다. 완료 증거: 저장 경계와 실패 보존 test가 구현 전 실패한다.
3. dirty draft만 전체 API-011 request로 제출하고 loading 중 duplicate submit을 막는다. 완료 증거: 한 번의 action은 한 mutation이며 현재 snapshot version을 쓴다.
4. 성공 response mapping/version을 committed base와 API-010 cache에 반영하고 Transcript view model을 갱신한다. 완료 증거: 같은 speakerId의 모든 segment label이 함께 바뀌고 text/time은 같다.
5. 실패에서 base mapping을 유지하고 draft를 보존하며 412 최신 snapshot reconcile을 구현한다. 완료 증거: user draft 자동 삭제/강제 덮어쓰기가 없다.
6. 네트워크 전송 결과가 불명확하면 API-010 GET 후 비교하고, 확정되지 않은 경우 자동 PUT retry를 하지 않는다. 완료 증거: 저장 결과 일치/불일치/GET 실패가 각각 안전하게 처리된다.
7. provider-null Settings guard, read-after-write, Frontend/Backend tests, lint/build 및 통합 QA를 수행한다. 완료 증거: 사용 명령과 evidence를 Issue에 남긴다.

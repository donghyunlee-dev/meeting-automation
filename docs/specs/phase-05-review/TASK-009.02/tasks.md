# Template 재생성 UI 구현 작업

1. Review route, API-002/010 clients/cache, API-013 client, Minutes dirty/save coordinator와 API-010 polling helper를 코드에서 추적한다. 완료 결과: 변경 대상 컴포넌트, callback 계약, 사용 가능한 scripts가 Issue에 기록된다.
2. Template selection, dialog cancel/confirm, dirty save barrier, API requests, terminal success/failure, stale response, unmount cases의 tests를 먼저 추가한다. 완료 결과: 구현 전 기대 동작이 실패 테스트로 표현된다.
3. API-002 query와 current/pending Template selector를 구현한다. 완료 결과: 초기 current 선택, pending 변경/원복, loading/error/목록 누락 처리와 invalid request 차단이 검증된다.
4. 재생성 confirmation 및 API-012 save coordinator를 연결한다. 완료 결과: dirty draft는 저장 성공 및 API-010 authoritative version 확인 전 보존되고 API-013 호출은 없다.
5. API-013 mutation에 `{templateId}`, latest `If-Match`, per-intent `Idempotency-Key`를 연결한다. 완료 결과: 확인 취소/중복 클릭은 mutation을 만들지 않고 승인한 한 의도는 한 번만 수락된다.
6. 202 뒤 blocking DRAFT_REGENERATION view와 API-010 polling을 연결한다. 완료 결과: 1초 정상 간격/최대 10초 오류 backoff, 단일 GET, terminal/unmount cleanup이 동작한다.
7. matching Session/version/`lastOperation`으로 성공 또는 rollback snapshot을 version-aware 적용한다. 완료 결과: 성공 Minutes/current template 반영, 실패 기존 Minutes 복원/안내와 stale response 무시가 검증된다.
8. API-002/API-013/API-010 error recovery와 uncertain response 재조회 동작을 구현한다. 완료 결과: API blind retry 없이 사용자 draft와 pending selection이 보존된다.
9. keyboard, accessible dialog/focus/live region, reduced motion, 360px layout test 및 manual QA를 실행한다. 완료 결과: 검증 evidence가 Issue #41에 첨부되고 실제 Frontend package scripts/명령이 기록된다.

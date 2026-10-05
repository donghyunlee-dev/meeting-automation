# Review Minutes 저장 통합 작업 항목

관련 Issue: [#39](https://github.com/donghyunlee-dev/meeting-automation/issues/39).

1. API-010 latest snapshot, API-012 response/error, Review form callbacks, `allowedActions` 및 cache version 경계를 확인한다. 완료 증거: state transition table/field mapping을 PR에 기록한다.
2. clean/dirty/no-op, If-Match/full body, success/read-after-write, in-flight edit, validation/412, network-unknown tests를 먼저 작성한다. 완료 증거: 상태/draft 보존 test가 구현 전에 실패한다.
3. base/draft/submitted snapshot을 분리하고 dirty/no-op/single-flight mutation을 구현한다. 완료 증거: clean call 0회, dirty submit 1회, duplicate submit 차단을 확인한다.
4. API-012 success response 및 API-010 GET을 version-aware cache에 반영한다. 완료 증거: 최신 Minutes/version은 반영되고 lower-version query response는 무시된다.
5. validation/provider error, 412, 응답 유실/GET 실패 복구를 구현한다. 완료 증거: committed base와 user draft 보존, blind PUT retry 없음이 확인된다.
6. saving 중 새 edit, provider-null Session, missing Session/Review 이탈을 연결한다. 완료 증거: edit overwrite/불필요한 provider gate/잘못된 화면 잔류가 없다.
7. FE/BE 테스트·lint/build 및 API-010/012 왕복 QA를 수행한다. 완료 증거: 실행 결과와 저장/재조회 evidence를 Issue에 남긴다.

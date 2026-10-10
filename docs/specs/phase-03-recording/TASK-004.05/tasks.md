# 구현 작업 목록

> 📌 v1.9.0 변경 계약: TASK-022.06과 TASK-004.04 변경 완료 후 API-001의 documentConnectionVersion을 불변 제출 snapshot에 포함한다. 미설정·전환 중에는 제출을 차단하고, DOCUMENT_CONNECTION_CHANGED는 참석자 목록과 설정을 다시 읽어 새 시도/key를 만들도록 안내한다. 동일 연결의 응답 유실 재시도만 기존 snapshot/key를 재사용한다. 계정이나 사용자별 설정은 추가하지 않는다. 상세는 [문서 연결·이전 설계](../../../product/document-setup.md)와 [API 명세](../../../product/api-spec.md#api-006-meeting-session-create)를 따른다.

1. 성공 mock/router 테스트를 작성한다. 기대 결과: API-006 요청 후 Session Context에 값을 쓰고 Recording destination으로 이동한다.
2. 중복 click/loading 및 네트워크 timeout retry 테스트를 작성한다. 기대 결과: pending 중 1회 호출, retry에는 동일 body/key pair가 전달된다.
3. 수정 payload 재시도 테스트를 작성한다. 기대 결과: 변경된 값은 새 recoveryKey/Idempotency-Key를 받는다.
4. API 400/409/500/502 실패 테스트를 작성한다. 기대 결과: 안전한 메시지를 보이고 draft를 보존하며 성공 이동을 하지 않는다.
5. API client와 Session Context DTO를 구현한다.
6. logical attempt snapshot/key lifecycle와 submit disable/error/retry를 연결한다.
7. 성공 response storage와 `/meetings/{sessionId}/recording` navigation을 구현한다.
8. API mock/router/component tests, `npm run test`, `npm run lint`, `npm run build`를 실행한다.

완료 기준과 테스트 증거는 [spec.md](./spec.md), [test.md](./test.md)를 따른다.

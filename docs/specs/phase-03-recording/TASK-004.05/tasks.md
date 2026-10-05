# 구현 작업 목록

1. 성공 mock/router 테스트를 작성한다. 기대 결과: API-006 요청 후 Session Context에 값을 쓰고 Recording destination으로 이동한다.
2. 중복 click/loading 및 네트워크 timeout retry 테스트를 작성한다. 기대 결과: pending 중 1회 호출, retry에는 동일 body/key pair가 전달된다.
3. 수정 payload 재시도 테스트를 작성한다. 기대 결과: 변경된 값은 새 recoveryKey/Idempotency-Key를 받는다.
4. API 400/409/500/502 실패 테스트를 작성한다. 기대 결과: 안전한 메시지를 보이고 draft를 보존하며 성공 이동을 하지 않는다.
5. API client와 Session Context DTO를 구현한다.
6. logical attempt snapshot/key lifecycle와 submit disable/error/retry를 연결한다.
7. 성공 response storage와 `/meetings/{sessionId}/recording` navigation을 구현한다.
8. API mock/router/component tests, `npm run test`, `npm run lint`, `npm run build`를 실행한다.

완료 기준과 테스트 증거는 [spec.md](./spec.md), [test.md](./test.md)를 따른다.

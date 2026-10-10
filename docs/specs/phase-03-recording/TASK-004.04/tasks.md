# 구현 작업 목록

> 📌 v1.9.0 변경 계약: TASK-022.06 완료 후 재개한다. API-006에 documentConnectionVersion과 전역 READY/전환 lock guard를 적용하고 기존 201·멱등성·검증과 함께 미설정/전환 중/stale connection 409를 검증한다. PR #95는 보존하고 새 master 반영 후 테스트·리뷰·QA를 다시 수행한다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

1. 유효 요청의 Service/API 테스트를 작성한다. 기대 결과: Session snapshot의 title/template/participant IDs/timezone과 201 response를 검증한다.
2. 필수값, 잘못된 Template, 빈/중복/알 수 없는 Participant ID, 잘못된 timezone/recoveryKey 입력 테스트를 작성한다. 기대 결과: 각 케이스는 400이고 store는 비어 있다.
3. Participant/Template lookup 오류 및 오류 원문 비노출 테스트를 작성한다. 기대 결과: lookup 오류가 정의한 표준 오류로 변환된다.
4. Idempotency-Key 같은 payload 재전송 및 다른 payload 충돌 테스트를 작성한다. 기대 결과: 1회 생성/409 충돌이다.
5. 입력 DTO/validator와 Template/roster lookup을 구현한다.
6. memory Session store 및 idempotency result를 연결해 Session을 원자적으로 만든다.
7. upload policy 설정과 response DTO를 구현한다. 기대 결과: client가 보낸 값을 무시하고 server policy만 반환한다.
8. process restart 후 memory store의 복구를 기대하지 않는 경계를 검증하고 Backend test/build를 실행한다.

각 수용 조건과 오류는 [test.md](./test.md)에 연결한다.

- [ ] TASK-022의 후속 실제 소비자 검증을 수행하고 [검증 계획](./test.md)에 결과를 남긴다: 이 작업은 TASK-022에서 계약 fixture로만 검증한 실제 API-006 소비자를 연결한다. READY 성공 생성, 미설정/전환 중/stale connectionVersion 409, 생성과 전환의 원자 경쟁 및 Session 등록/종료 gate 사용을 실제 요청과 공용 registry 계약으로 검증한다.

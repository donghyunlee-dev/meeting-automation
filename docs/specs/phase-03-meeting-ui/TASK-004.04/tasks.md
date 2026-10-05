# 구현 작업 목록

1. 유효 요청의 Service/API 테스트를 작성한다. 기대 결과: Session snapshot의 title/template/participant IDs/timezone과 201 response를 검증한다.
2. 필수값, 잘못된 Template, 빈/중복/알 수 없는 Participant ID, 잘못된 timezone/recoveryKey 입력 테스트를 작성한다. 기대 결과: 각 케이스는 400이고 store는 비어 있다.
3. Participant/Template lookup 오류 및 오류 원문 비노출 테스트를 작성한다. 기대 결과: lookup 오류가 정의한 표준 오류로 변환된다.
4. Idempotency-Key 같은 payload 재전송 및 다른 payload 충돌 테스트를 작성한다. 기대 결과: 1회 생성/409 충돌이다.
5. 입력 DTO/validator와 Template/roster lookup을 구현한다.
6. memory Session store 및 idempotency result를 연결해 Session을 원자적으로 만든다.
7. upload policy 설정과 response DTO를 구현한다. 기대 결과: client가 보낸 값을 무시하고 server policy만 반환한다.
8. process restart 후 memory store의 복구를 기대하지 않는 경계를 검증하고 Backend test/build를 실행한다.

각 수용 조건과 오류는 [test.md](./test.md)에 연결한다.

# 구현 작업 목록

1. Service/API 테스트에 name-only, email-only, both-field 수정 사례를 추가한다. 검증 결과: 생략 필드 보존 assertion이 구현 전 실패한다.
2. Validation 테스트에 빈 body, null, 공백 name, 잘못된 email을 추가한다. 검증 결과: 각각 400과 Provider 미호출이 요구된다.
3. 미존재 ID 및 Provider 오류 테스트를 추가한다. 기대 결과: 404 `PARTICIPANT_NOT_FOUND`, 502 `DOCUMENT_FAILED`가 확인된다.
4. Request 검증과 Service 부분 merge를 구현한다. 기대 결과: 요청에 포함된 필드만 정규화해 갱신한다.
5. Provider Adapter에서 title/body 일부를 수정하고 표준 Participant 전체를 반환한다. 기대 결과: 수정하지 않은 Page 필드가 보존된다.
6. 예외 응답/로그와 통합 테스트를 확인한다. 기대 결과: Provider 원문/인증정보가 없고 전체 테스트가 통과한다.

상세 수용 조건은 [spec.md](./spec.md), 입력과 결과 조합은 [test.md](./test.md)를 따른다.

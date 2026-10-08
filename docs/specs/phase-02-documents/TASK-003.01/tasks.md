# 구현 작업 목록

1. Controller/API 테스트에 목록 정상 응답과 `{data:{items:[]}}` 빈 결과를 추가한다. 검증 결과: 두 테스트가 구현 전 실패한다.
2. Service 테스트에 구조 누락 및 Provider 예외 변환을 추가한다. 검증 결과: 422/502 코드, category, retryable 값이 기대와 다르면 실패한다.
3. Provider contract 테스트에 Page ID/title/`Email:` 본문 매핑을 추가한다. 검증 결과: 세 필드 DTO와 malformed Page 실패가 드러난다.
4. query/filter argument 없는 Service와 Controller가 root 구조 탐색 후 `ParticipantListingProvider.listParticipants(participantsPageId)`를 호출하도록 구현한다. 검증 결과: 공개 요청 인자 없이 roster 전체가 반환된다.
5. Provider 예외와 필드 매핑 오류를 안전한 표준 오류로 변환한다. 검증 결과: 응답과 로그에 Provider 원문/secret이 없다.
6. 전체 관련 Backend 테스트를 실행하고 API 문서와 DTO 및 오류 계약을 대조한다. 검증 결과: 수용 기준 각각에 자동화 증거가 연결된다.

작업은 [spec.md](./spec.md)의 경계와 [test.md](./test.md)의 사례를 따른다.

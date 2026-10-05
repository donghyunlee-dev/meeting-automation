# 구현 작업 목록

1. API fixture로 App Config/templates/participants query loading, success, empty, error/retry 테스트를 작성한다. 기대 결과: fixture별 화면 출력과 재시도가 구현 전 실패한다.
2. 제목/Template/Participant 검증 테스트를 작성한다. 기대 결과: 빈 제목, Template 없음, 선택 인원 0명은 submit되지 않는다.
3. multi-select 테스트를 작성한다. 기대 결과: roster option 추가/제거와 선택 ID 순서/중복이 결정된다.
4. valid submit 테스트를 작성한다. 기대 결과: callback이 trim된 title, Template ID, 선택 Participant IDs와 company timezone을 전달한다.
5. API-001/002/003 client query, typed DTO 및 안전 오류 처리를 구현한다.
6. `/meetings/new` form, selector, Loading/Error/Empty 및 retry UI를 구현한다.
7. field validation, draft 보존, submit callback을 구현한다. API-006 POST는 연결하지 않는다.
8. 접근성/360px viewport와 frontend test/lint/build를 확인한다.

각 수용 조건의 검증은 [test.md](./test.md)를 따른다.

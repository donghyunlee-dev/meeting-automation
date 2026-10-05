# 구현 작업 목록

1. App Config 정상 fixture 및 전체 공통 field assertion 테스트를 작성한다. 기대 결과: 누락된 영역/field가 구현 전 검출된다.
2. document provider null, valid configured, credentials missing/invalid, unsupported enum 테스트를 작성한다. 기대 결과: 자동 선택·fallback 및 secret 노출을 검출한다.
3. Template catalog에 두 리소스가 존재할 때 목록과 순서를 검증하는 테스트를 작성한다.
4. 필수 Template 한 개를 제거/읽기 실패 처리한 테스트를 작성한다. 기대 결과: 부분 목록이 아니라 공통 500 오류여야 한다.
5. configuration DTO와 App Config endpoint를 구현한다. 기대 결과: 항상 다섯 공통 영역을 반환하며 null/credentials 계약을 만족한다.
6. static Template resource reader와 `GET /templates`를 구현한다. 기대 결과: 두 metadata 항목을 PRD 순서대로 반환한다.
7. 오류 envelope 및 response/log Secret 비노출을 확인하고 backend test/build를 실행한다.

테스트별 입력과 기대는 [test.md](./test.md)에 기록한다.

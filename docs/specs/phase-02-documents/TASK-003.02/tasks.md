# 구현 작업 목록

1. 입력 검증 Controller 테스트를 추가한다. 기대 결과: 빈/공백 name, 누락 필드, 잘못된 email 입력이 구현 전 실패한다.
2. Service 및 Provider contract 테스트를 추가한다. 기대 결과: 유효 요청의 표준 명령과 반환 DTO, 잘못된 입력의 Provider 미호출이 검증된다.
3. idempotency 테스트를 추가한다. 기대 결과: 동일 키/동일 payload 재전송은 1회 생성, 동일 키/다른 payload는 409로 실패한다.
4. Request 정규화와 Bean/application validation을 구현한다. 기대 결과: trim된 값만 저장되고 유효하지 않은 값은 400이다.
5. Controller/Service에서 생성 요청용 memory idempotency 구성요소를 도입하고 연결한다. 기대 결과: 동일 key의 Provider create가 안전하게 한 번 수행되고 최초 결과가 보존된다.
6. Provider Adapter 저장 규칙과 예외 변환을 검증한다. 기대 결과: title=name, 본문 Email, page ID=id이며 Provider 오류가 `DOCUMENT_FAILED` 또는 `DOCUMENT_STRUCTURE_NOT_FOUND`로 매핑된다.
7. 관련 자동화 테스트 및 회귀 검사를 실행한다. 기대 결과: 모든 수용 기준에 테스트 증거가 연결된다.

자세한 경계는 [spec.md](./spec.md), 테스트 준비와 기대 결과는 [test.md](./test.md)를 따른다.

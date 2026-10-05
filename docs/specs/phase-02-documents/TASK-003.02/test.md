# 검증 계획

## 자동화 테스트

| ID | 준비/입력 | 기대 결과 | 증거 |
|---|---|---|---|
| API-004-01 | 유효 `{name,email}`, 신규 Idempotency-Key | Provider 1회 생성, HTTP 201, `{id,name,email}` 응답 | Controller/API 테스트 |
| API-004-02 | name 누락/공백 | HTTP 400 `VALIDATION_FAILED`, Provider 미호출 | Validation 테스트 |
| API-004-03 | email 누락/형식 오류 | HTTP 400 `VALIDATION_FAILED`, Provider 미호출 | Validation 테스트 |
| API-004-04 | 같은 키와 같은 정규화 payload 재전송 | 기존 생성 결과 재사용, Provider 생성 1회 | Idempotency Service 테스트 |
| API-004-05 | 같은 키에 다른 payload | HTTP 409 `IDEMPOTENCY_KEY_CONFLICT` | Idempotency Service/API 테스트 |
| API-004-06 | Participants child 구조 없음 | HTTP 422 `DOCUMENT_STRUCTURE_NOT_FOUND` | Provider/error mapping 테스트 |
| API-004-07 | 일시 및 영구 Provider 생성 실패 | HTTP 502 `DOCUMENT_FAILED`, 원인에 따른 retryable 값 | Provider/error mapping 테스트 |
| API-004-08 | 유효 입력 Provider 전달 | title=name, `Email: <address>` 본문, page ID가 표준 id | Adapter contract 테스트 |
| API-004-09 | Provider 예외/로그 확인 | Provider 원문과 자격 정보가 응답/로그에 없음 | 예외/로그 테스트 |

구현 완료 시 `./gradlew test`, `./gradlew clean build`를 실행한다. 이 설계 단계에서는 테스트를 실행하지 않는다.

## 수동 QA

- 유효한 name/email 생성 시 Participants child 아래에 Page가 나타나고 새 목록 응답에 같은 표준 Participant가 포함된다.
- 누락/잘못된 입력은 저장 없이 필드 오류로 표시 가능한 API 오류를 반환한다.
- 네트워크 재전송은 동일 idempotency key 사용 시 페이지를 중복 생성하지 않는다.

## 릴리스 확인

배포 환경에서 생성 요청 후 응답에 provider page ID 외의 Provider 원문이나 인증정보가 노출되지 않는지 확인한다. UI 흐름과 실제 이메일 발송은 후속 작업 범위다.

# 검증 계획

## 자동화 테스트

| ID | 준비/입력 | 기대 결과 | 증거 |
|---|---|---|---|
| API-005-01 | 기존 `{id,name,email}`, body `{name}` | HTTP 200, 새 name과 기존 email | Service/API 테스트 |
| API-005-02 | 기존 Participant, body `{email}` | HTTP 200, 새 email과 기존 name | Service/API 테스트 |
| API-005-03 | 기존 Participant, body `{name,email}` | 두 필드가 모두 갱신된 DTO | Service/API 테스트 |
| API-005-04 | 빈 body | HTTP 400 `VALIDATION_FAILED`, Provider 미호출 | Validation 테스트 |
| API-005-05 | 제공 필드 null/공백 name/잘못된 email | HTTP 400 `VALIDATION_FAILED`, Provider 미호출 | Validation 테스트 |
| API-005-06 | 존재하지 않는 participantId | HTTP 404 `PARTICIPANT_NOT_FOUND` | Service/API 테스트 |
| API-005-07 | Provider update 실패 | HTTP 502 `DOCUMENT_FAILED`, 안전한 오류 본문 | Error mapping 테스트 |
| API-005-08 | Provider Page 부분 갱신 | title=name, Email 본문=email, 미수정 값 보존 | Adapter contract 테스트 |
| API-005-09 | 오류 응답과 로그 확인 | Provider 원문/인증정보 미노출 | 예외/로그 테스트 |
| API-005-10 | 다른 parent의 페이지 ID | 404, 해당 페이지 mutation 미호출 | 소속 검증 테스트 |
| API-005-11 | 추가 본문이 있는 Participant | 수정하지 않은 본문·필드 보존 | Adapter contract 테스트 |

구현 후 `./gradlew test`, `./gradlew clean build`를 실행한다. 문서 작성 단계에서는 테스트를 실행하지 않는다.

## 수동 QA

- name만 또는 email만 수정해도 목록 재조회에서 반대편 값이 보존된다.
- 빈 입력과 유효하지 않은 필드는 저장을 호출하지 않고 검증 오류를 반환한다.
- 알 수 없는 ID 요청은 미존재 응답을 반환한다.

## 릴리스 확인

Provider 저장 값과 API 응답을 비교해 부분 갱신 시 데이터 유실이 없는지 확인한다. 관리 UI 연결과 사용자 입력 흐름은 TASK-003.04에서 확인한다.

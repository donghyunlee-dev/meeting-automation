# 검증 계획

## 자동화 테스트

| ID | 준비/입력 | 기대 결과 | 증거 |
|---|---|---|---|
| API-003-01 | Provider가 `{id,name,email}` 2건 반환 | HTTP 200, 각 item은 정확히 `id`, `name`, `email` 필드로 구성 | Controller/API 테스트 결과 |
| API-003-02 | Provider가 빈 목록 반환 | HTTP 200, `items: []` | Controller/API 테스트 결과 |
| API-003-03 | Participants 구조 누락 예외 | HTTP 422 `DOCUMENT_STRUCTURE_NOT_FOUND`, 재시도 불가 | Exception mapping 테스트 |
| API-003-04 | 일시 Provider 오류 | HTTP 502 `PARTICIPANT_LIST_FAILED`, retryable true | Service/API 테스트 |
| API-003-05 | 권한 거부 등 영구 Provider 오류 | HTTP 502 `PARTICIPANT_LIST_FAILED`, retryable false | Service/API 테스트 |
| API-003-06 | 필수 title/email 누락 또는 email line 해석 실패 | 목록 전체가 502 `PARTICIPANT_LIST_FAILED` | Adapter contract 테스트 |
| API-003-07 | 정상 Page ID/title/`Email:` 본문 | ID/title/email 표준 매핑 | Adapter contract 테스트 |
| API-003-08 | 오류 응답 및 로그 검사 | Provider 원문, 인증정보 미노출 | 예외/로그 테스트 |

알려진 프로젝트 명령으로 구현 후 `./gradlew test`와 `./gradlew clean build`를 실행한다. 이 설계 단계에서는 테스트를 실행하지 않는다.

## 수동 QA

- Participants Page의 복수 roster 항목과 빈 목록을 API 응답으로 확인한다.
- 필수 child 구조가 빠진 환경에서 구조 오류 안내가 확인된다.
- Provider 접근 오류 시 안전한 오류와 재시도 가능 여부가 구분된다.

## 릴리스 확인

Backend 배포 환경에서 인증정보와 Provider 오류 원문이 응답·로그에 기록되지 않는지 확인한다. 이 Task는 변경 요청을 제공하지 않으며 수동 page 편집은 범위 밖이다.

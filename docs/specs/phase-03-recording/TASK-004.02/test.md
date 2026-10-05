# 검증 계획

## 자동화 테스트

| ID | 준비/입력 | 기대 결과 | 증거 |
|---|---|---|---|
| API-001-01 | 정상 환경 설정 | HTTP 200, company/document/email/notification/recording 다섯 영역 | Controller/DTO test |
| API-001-02 | `DOCUMENT_PROVIDER` 누락/공백 | `document.provider=null`, `configured=false`, 자동 선택 없음 | Configuration test |
| API-001-03 | 지원 Provider와 유효 credentials | 선택 Provider 유지, `configured=true` | Configuration test |
| API-001-04 | 지원 Provider credentials 누락/잘못됨 | 선택 Provider 유지, `configured=false` | Configuration test |
| API-001-05 | 미지원 비어있지 않은 provider enum | HTTP 500 `INTERNAL_ERROR`, 설정값/stack 미노출 | Configuration/error mapping test |
| API-001-06 | Secret sentinel 문자열 환경 설정 | response와 일반 로그 어디에도 값 없음 | API/log test |
| API-002-01 | 두 Template static resources 존재 | HTTP 200, default/project ID·name·version 순서 일치 | Resource/service test |
| API-002-02 | 한 required resource 누락/읽기 실패 | 부분 응답 대신 HTTP 500 `INTERNAL_ERROR` | Resource/error test |
| API-002-03 | config/templates endpoint 호출 | Document Provider와 외부 서비스 호출 없음 | Mock verification |

구현 후 `./gradlew test`, `./gradlew clean build`를 실행한다. 설계 문서 작성 중에는 실행하지 않는다.

## 수동 QA

- App Config 응답 다섯 영역과 Template 목록을 확인하고 Secret이 포함되지 않는지 검사한다.
- provider 미설정 환경에서 자동 Provider 선택 없이 null/false가 나타나는지 확인한다.
- required Template 리소스 제거 후 안전한 공통 오류를 확인한다.

## 릴리스 확인

배포 설정에서 자격 증명 값을 바꾸어도 App Config response schema에는 공개 flag만 나타나는지 확인한다. Provider 실제 연결 확인은 API-019 후속 구현 대상이다.

# 검증 계획

> 📌 v1.9.0 변경 계약: 활성 전역 connection과 TASK-022.04의 문서 codec을 사용한다. 전환 완료 후 이전 Provider cache와 ID를 재사용하지 않는다. 일반 History의 목록 제한을 자료 이전 completeness 판정으로 사용하지 않는다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

## 자동화 테스트

구현 단계에서 Backend 표준 JUnit/Gradle 테스트를 실행한다. 정확한 명령은 Backend 프로젝트 설정에 따른다. Provider 호출은 fake HTTP/client fixture를 사용하며 실계정 Provider에 연결하지 않는다.

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| API017-LIMIT-DEFAULT | `limit` 생략, 100개 미만 fixture | Provider 전체 결과 중 최신순 최대 100개가 `{data:{items}}`로 반환 | Controller/API test report |
| API017-LIMIT-BOUNDARY | `limit=1`, `limit=100` | 각각 최대 1개/100개, 0 또는 101은 400 `VALIDATION_FAILED` | parameterized API test |
| API017-LIMIT-FORMAT | 비정수 및 공백 query | 400 `VALIDATION_FAILED`; Provider 호출 0회 | Controller test |
| API017-EMPTY | Meetings child에 문서 없음 | 200 `{data:{items:[]}}` | service/API test |
| API017-SORT | 서로 다른 offset 표현의 meetingAt 및 동률 문서 | 실제 시각 내림차순, 동률 documentId 오름차순 | service test |
| API017-PAGINATION | 여러 Provider cursor/page, 최신 항목이 후속 페이지에 위치 | 모든 페이지를 읽고 전체 기준 최신 limit 선택 | adapter test with page-call assertions |
| API017-DUPLICATE | 동일 documentId가 두 페이지에 노출 | 한 응답 항목만 존재 | service/adapter test |
| API017-PARTICIPANTS | participantIds 순서가 roster 순서와 다름 | roster의 id/name만 문서 지정 순서대로 반환; email 없음 | adapter mapping test |
| API017-DOCUMENT-URL | URL 누락 또는 비 HTTP(S) | 계약상 안전한 `documentUrl=null`; 오류/본문은 반환되지 않음 | DTO mapping/API test |
| API017-METADATA-FAILURE | 필수 meetingAt/participantIds 누락·파싱 오류 또는 참조 불명 | 502 `DOCUMENT_FAILED`, 부분 items 없음 | adapter/API test |
| API017-PROVIDER-FAILURE | 첫 페이지 또는 후속 페이지 Provider 오류 | 502 `DOCUMENT_FAILED`, 부분 items 없음, 원문/Secret 로그 없음 | adapter/API test |

## 수동 QA

- Provider에 서로 다른 날짜의 Meeting 문서를 준비하고 API를 호출해 최신순 및 `limit` 결과 수를 확인한다.
- 결과가 없을 때 빈 상태를 위한 정상 200 응답을 확인한다. 실제 SCR-009 화면 상태 표현은 TASK-014.03에서 검증한다.
- 응답과 로그에 이메일, Meeting 본문, Transcript, Minutes, Authorization 또는 Provider 원문 응답이 없는지 확인한다.

## 릴리스 확인

- `./gradlew test` 및 저장소의 Backend build 명령을 실행하고 결과를 Evidence 문서에 남긴다.
- 선택된 Provider의 정상 자식 페이지 pagination을 비생산 데이터에서 확인한다. 운영 Secret이나 실제 민감 회의 데이터를 로그에 남기지 않는다.

## 🔗 전역 연결 소비자 실제 연동 회귀

이 작업은 TASK-022.06에서 계약 fixture로만 검증한 실제 API-017을 연결한다. 이전된 target 문서를 조회하고 전환 전 source 목록/cache를 재사용하지 않으며 참석자 참조가 target roster와 일치하는지 실제 요청으로 검증한다.

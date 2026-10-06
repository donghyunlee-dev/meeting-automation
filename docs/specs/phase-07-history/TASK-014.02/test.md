# 검증 계획

## 자동화 테스트

Backend 표준 JUnit/Gradle 테스트에서 fake DocumentProvider 및 Provider HTTP fixture를 사용한다. 실제 Provider 계정/회의 문서는 자동화 테스트에 사용하지 않는다.

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| API018-SUCCESS | 유효한 `documentId`, 모든 필드가 있는 Provider 문서 | 200 `{data:{documentId,title,meetingAt,participants,minutes,transcript,documentUrl}}` | API test report |
| API018-MAPPING | metadata, actionItems, Transcript segments fixture | ISO offset, Minutes schema, segment 필드 및 Participant ID/name가 순서대로 정확히 변환 | Adapter contract test |
| API018-EMAIL-OMISSION | roster에 이메일 포함 | 응답 Participant는 id/name만 가지며 이메일 없음 | serialization assertion |
| API018-NOT-FOUND | 존재하지 않는 documentId / Provider 404 | 404 `MEETING_NOT_FOUND`; provider 원문 없음 | Adapter/API test |
| API018-STRUCTURE-ERROR | Meetings child 구조 없음 | 422 `DOCUMENT_STRUCTURE_NOT_FOUND` | Adapter/API test |
| API018-PROVIDER-ERROR | Provider 401/403/429/5xx, timeout 또는 connection error | 502 `DOCUMENT_FAILED`; 404로 오인하지 않고 body/log에서 원문·Secret 제거 | exception mapping/API test |
| API018-PARSE-ERROR | 필수 metadata, Minutes 또는 Transcript schema 손상 | 502 `DOCUMENT_FAILED`; 부분 상세 없음 | adapter/API test |
| API018-URL | URL 누락, 유효 HTTP(S), javascript/기타 scheme | 각각 null, 보존, null | mapper test |
| API018-READ-ONLY | GET 상세 요청 | Provider update/create 호출 0회 | fake provider interaction assertion |
| API018-NO-LOG-PII | Transcript/Minutes, email, auth material 포함 fixture | response body를 제외한 application logs/exceptions에 민감 본문/Secret 없음 | log capture test |

## 수동 QA

- 사전 승인된 비생산 Provider 문서 하나를 ID로 조회해 제목·일시·참석자·요약·논의·결정·Action Items·Transcript를 대조한다.
- 원문 URL이 비어 있는 fixture에서는 응답 URL이 null인지 확인한다. Browser 외부 링크 열기 동작은 TASK-014.04에서 검증한다.
- 같은 조회 전후 Provider 문서가 변경되지 않았는지 확인한다.

## 릴리스 확인

- `./gradlew test`와 Backend build 명령을 실행하고 Evidence 문서에 결과를 기록한다.
- Notion page child/body pagination 및 Confluence page detail body-format 처리가 선택된 Adapter에서 확인된다.
- Log/trace 설정에서 요청/응답 본문과 Secret이 기록되지 않는지 확인한다.

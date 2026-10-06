# Meetings 필터 및 상세 연결 설계

## 작업 식별 정보

- 작업: `TASK-014.04`
- 상위 작업: `TASK-014 Meeting History`
- 단계: `Phase 7 — History`
- PRD 기준: `PRD-MA-001` v1.7.0, 2026-10-05
- 관련 요구사항: `FR-021~023`, `SCR-009`, `SCR-010`, `API-017`, `API-018`
- 영역: Frontend, Integration
- 선행 작업: `TASK-014.02`, `TASK-014.03`
- GitHub Issue: [#55](https://github.com/donghyunlee-dev/meeting-automation/issues/55)

## 결과

Meetings 화면에서 API-017이 돌려준 최대 100건 범위 안에서 제목/참석자 텍스트와 회의 날짜 범위를 필터한다. row 선택은 읽기 전용 상세 화면을 열어 API-018을 소비한다. Detail 화면은 표준 Minutes/Transcript를 표시하고 사용자 action으로 안전한 Provider 원문 URL을 새 탭에서 연다.

## 목록 필터 동작

- 검색어는 trim 및 Unicode NFKC 정규화 후 대소문자 구분 없이 회의 제목 또는 참석자 이름 부분 문자열과 일치하면 포함한다. 검색어가 비어 있으면 이 필터는 적용하지 않는다.
- 시작일 `from`과 종료일 `to`는 각각 선택할 수 있다. 한쪽만 주어져도 필터하며, 지정된 양 끝 날짜를 포함한다. 날짜는 browser의 사용자 local calendar day 기준이다.
- 텍스트 검색과 날짜 범위는 AND 조건이다. 모든 결과는 API에서 받은 최신순을 보존하며, 결과 집합을 다시 정렬하지 않는다.
- 시작일이 종료일보다 뒤면 목록을 임의로 비우지 않고 필드에 검증 안내를 표시하며 결과 범위 filter를 적용하지 않는다. 초기 필터값은 빈 값이다.
- 필터 뒤 결과가 없으면 전체 목록이 비었다는 Empty와 구분해 “검색 결과 없음” 안내와 필터 초기화 action을 표시한다.
- 월 그룹은 필터 후 남은 항목의 사용자 local month 기준으로 구성한다.

## 상세 및 외부 문서

- 선택된 API-017 item의 `documentId`로 `/meetings/{documentId}` read-only route를 연다. 상세 진입 시 `GET /api/v1/meetings/{documentId}`를 호출한다.
- 상세는 회의명, 일시, 참석자, Minutes summary/discussion/decisions/action items를 표시한다. Transcript는 별도 접근 가능한 Sheet/Drawer로 연다. Follow-ups는 Structured Minutes에 값이 있을 때 summary 이후의 별도 섹션으로 표시한다.
- 상세는 Loading, Provider Error + GET 재시도, `MEETING_NOT_FOUND` 404 안내 + 목록 복귀 동작을 가진다. 수정/삭제 버튼은 없다.
- `documentUrl`이 표준 DTO에서 제공되고 `http` 또는 `https` scheme이며 hostname이 있으면 명시적인 사용자 action `Notion/Confluence에서 열기`로 새 탭을 연다. `noopener noreferrer`를 적용한다. URL 누락/유효하지 않은 경우 링크를 렌더링하지 않는다.
- 원문 URL은 API가 제공한 목적지 그대로 사용한다. 문서 body 링크를 자동 추출하거나 Provider를 추측하지 않는다.

## 비범위 및 안전 경계

- Provider별 API/인증/URL 생성, 서버 전문검색, 100건 초과 페이지 탐색은 범위 밖이다.
- Participant 이메일은 목록 및 상세에 노출하지 않는다. Meeting/Participant 활성·진행·완료 상태 필드나 badge를 추가하지 않는다.
- Meeting 수정, Transcript 변경, 이력 삭제, 자동 외부 navigation은 제공하지 않는다.
- Provider 오류 본문이나 Secret은 UI에 표시하지 않는다.

## 완료 기준

- 제목/참석자 부분 문자열 및 시작/종료 날짜 필터가 로컬에서 동작하고 AND 결합·경계 날짜 포함·날짜 역전 검증이 통과한다.
- 날짜/검색 필터는 API-017 요청을 추가로 보내지 않으며 API가 반환한 최대 100건 안에서 실행된다.
- row에서 API-018 Detail로 연결되고 Minutes/Transcript/참석자 데이터가 표시된다.
- Loading/Error/Retry, not-found/list return, transcript drawer, URL 누락/유효하지 않음/안전 링크가 검증된다.
- 접근 가능한 row/search/date/clear controls와 read-only UX가 검증된다.
- 각 완료 기준이 [검증 계획](./test.md)의 사례와 연결된다.

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

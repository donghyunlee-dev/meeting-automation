# Notion Root 하위 Page 구조 탐색 작업 목록

## 작업 식별 정보

- 작업: `TASK-002.02`
- 선행 조건: Issue #6 완료
- GitHub Issue: [#7](https://github.com/donghyunlee-dev/meeting-automation/issues/7)

## 테스트 우선 구현 단계

### 선행 기반 확인

- [ ] Issue #6과 공통 Port 계약이 기본 브랜치에 게시됐는지 확인한다. 결과: 구현 대상 `DocumentStructure`/`ProviderHealth`와 오류 타입이 확정된다.
- [ ] 공식 Notion API 문서의 현재 `Notion-Version`, child block pagination 및 permission contract를 확인한다. 결과: request header와 fake 응답 필드가 최신 문서와 맞는다.
- [ ] `NOTION_TOKEN`, `DOCUMENT_ROOT_ID`가 Backend 설정에서 주입되는지 확인한다. 결과: Secret은 로그/테스트 출력에서 제외된다.

### 실패 테스트 작성

- [ ] fake Notion 응답으로 두 direct `child_page`를 `Meetings`/`Participants` 구조로 변환하는 테스트를 작성한다.
- [ ] `has_more=true` 뒤 cursor를 사용해 두 번째 페이지에서 대상 Page를 찾는 테스트를 작성한다.
- [ ] 빈 구조, 한쪽 child 누락, 중복 이름, 같은 이름의 `child_database` 응답 테스트를 작성한다. 결과: 자동 생성 없이 구조 오류가 된다.
- [ ] HTTP 401/403/404, 429/5xx, 네트워크 오류 fixture를 추가한다. 결과: 원문 메시지나 token이 출력되지 않는다.
- [ ] 실패 테스트를 실행한다. 결과: 테스트 로딩/compile은 성공하고 미구현 mapping assertion이 실패한다.

### 최소 구현

- [ ] Notion API client에 `GET /v1/blocks/{rootId}/children`, `Notion-Version`, Bearer 인증을 구현한다. 결과: 첫 child page를 읽는다.
- [ ] cursor를 끝까지 순회하는 로직을 추가한다. 결과: 뒤쪽 페이지에 있는 target child도 발견한다.
- [ ] `child_page` type과 정확한 title만 `DocumentStructure`로 바꾼다. 결과: Database/하위 단계 블록은 container로 선택되지 않는다.
- [ ] 누락/중복 구조와 API 권한·전송 실패를 기존 오류 계약으로 매핑한다. 결과: 구조 오류와 Provider 작업 실패가 구분되고 민감 원문은 감춰진다.
- [ ] Task 002.01 공통 hierarchy 계약 모음을 Notion adapter에 적용한다. 결과: 성공/오류 계약이 통과한다.

### 통과 확인과 증거

- [ ] Adapter 대상 테스트와 `./gradlew test`를 실행한다. 결과: 통과한다.
- [ ] `./gradlew clean build`를 실행한다. 결과: 종료 코드가 0이다.
- [ ] 의존성 및 변경 파일을 확인한다. 결과: Notion SDK, Database/Data Source, 금지 저장 인프라가 추가되지 않는다.
- [ ] Header/로그 fixture에서 token 및 원 Provider error body 비노출을 확인한다.
- [ ] 명령, API version, 결과 및 비민감 증거를 `docs/evidence/TASK-002.02.md`에 기록한다.

## 중단 조건

- [ ] 현재 공식 Notion API가 PRD의 Page hierarchy/basic CRUD와 호환되지 않으면 API 선택을 임의로 바꾸지 않고 Issue에 충돌과 근거를 남긴다.
- [ ] Root 자식 Page 이름이나 중복 처리로 PRD와 충돌하면 자동 생성/임의 선택을 하지 않고 Issue에 구체 결정을 요청한다.

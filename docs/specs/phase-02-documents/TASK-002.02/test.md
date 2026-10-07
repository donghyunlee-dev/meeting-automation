# Notion Root 하위 Page 구조 탐색 테스트 계획

## 테스트 목표

설정된 Notion Root에서 직속 `Meetings`, `Participants` child page를 안전하게 발견하고 `DocumentStructure`로 변환한다.

- GitHub Issue: [#7](https://github.com/donghyunlee-dev/meeting-automation/issues/7)
- 선행 조건: TASK-002.01 (#6) 구현 완료
- 실행 위치: `backend/`
- 공식 API 기준일: `2026-10-07`, `Notion-Version: 2026-03-11`

## 자동 테스트

### 성공 구조 발견

- 설정: fake Notion client가 Root child 목록에 `type=child_page`인 `Meetings`, `Participants`를 반환한다.
- 기대 결과: `DocumentStructure(rootId, meetingsPageId, participantsPageId)`가 정확한 ID로 구성된다.
- 호출 확인: `GET /v1/blocks/{rootId}/children`이 사용되고 `child_database`가 호출/선택되지 않는다.
- 증거: 반환 필드 assertion과 요청 경로 기록.

### Pagination

- 설정: 첫 child-list 응답은 `has_more=true`, `next_cursor=opaque-cursor-from-page-one`; 두 번째 응답에 `Participants`가 있다.
- 기대 결과: 두 번째 요청에 cursor 문자열이 그대로 `start_cursor`로 포함되고 모든 페이지 탐색 후 구조가 완성된다. Cursor 형식을 파싱하거나 검증하지 않는다.
- 경계: API가 page size보다 적은 결과를 반환해도 `has_more`가 true면 다음 cursor를 사용한다.
- 증거: 호출 횟수, cursor 전달 및 최종 ID assertion.

### 빈/잘못된 계층

- 입력: 빈 children, `Meetings` 누락, `Participants` 누락, 정확한 제목의 중복 child page, 같은 이름의 child database.
- 기대 결과: `DOCUMENT_STRUCTURE_NOT_FOUND`; 누락 Page 생성 호출은 0회다. Database 및 하위 단계 Page는 구조를 만족시키지 않는다.
- 증거: 표준 오류 코드 assertion과 create 요청 없음 확인.

### 권한과 Notion 오류

- 설정: HTTP 401 unauthorized, 403 restricted_resource, 404 object_not_found, 429 rate_limited, 5xx 응답 및 네트워크 timeout fixture.
- 기대 결과: 기존 `DOCUMENT_FAILED`/`DOCUMENT_FAILURE` 계약으로 변환된다. Notion 원문 `message`, response body, token은 반환값·로그에 포함되지 않는다.
- 상태 확인: HTTP 응답이 도착하면 API `reachable=true`; Root child 조회가 거부/실패하면 `rootAccessible=false`. 네트워크 timeout은 두 값 모두 false다. token/root 설정값은 출력하지 않는다.
- 증거: code/category/health assertion과 로그 비노출 검사.

### Header와 Secret 경계

- 설정: token 값은 fake 설정에서 주입하고 요청을 기록한다.
- 기대 결과: 요청마다 `Authorization: Bearer <설정 token>`과 `Notion-Version: 2026-03-11`이 포함된다. token은 캡처된 일반 로그나 오류 메시지에 없다.
- 증거: header 존재 여부만 기록하고 token 원문은 로그/증거에 복사하지 않는다.

### Backend 회귀

```powershell
./gradlew test
./gradlew clean build
```

- 기대 결과: Notion Adapter fixture 및 Backend 전체 검증이 통과한다.
- 추가 점검: dependency tree와 변경 diff에 Notion SDK, Database/Data Source 기능, DB/JPA/Redis/Queue가 추가되지 않는다.
- 증거: Java/Gradle 버전, 명령, 종료 코드, dependency 확인 결과.

## 수동 QA

- 실제 Notion credential을 사용하지 않고, 설정 누락/empty root/mock 권한 응답의 안전한 로그와 상태를 확인한다.
- API-019 Controller 응답 통합은 TASK-002.04에서 별도로 확인한다.
- `docs/evidence/TASK-002.02.md`에 비민감 검증 결과를 기록한다.

## 릴리스 전용 검사

실제 Notion workspace 권한, 운영 credential 배포 및 실제 Root 데이터 접근은 후속 배포/Provider 연동 QA에서 승인된 계정으로 수행한다. 자동/수동 개발 검증에는 실제 workspace secret을 사용하지 않는다.

## 완료 기준과 검사 연결

| 완료 기준 | 검사와 증거 |
|---|---|
| 설정 Root 직속 Page 발견 | 성공 구조 발견 테스트 |
| 모든 페이지 결과 탐색 | Pagination 테스트 |
| 빈/누락 구조 자동 생성 금지 | 빈/잘못된 계층 테스트 |
| 권한/Provider 오류 정규화와 비노출 | 권한과 Notion 오류 테스트 |
| 필수 API version/auth header | Header와 Secret 경계 테스트 |
| Backend 회귀 | `./gradlew test`, `./gradlew clean build` |

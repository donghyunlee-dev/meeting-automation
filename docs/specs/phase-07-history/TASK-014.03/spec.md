# Home 최근 회의 및 Meetings 화면 설계

## 작업 식별 정보

- 작업: `TASK-014.03`
- 상위 작업: `TASK-014 Meeting History`
- 단계: `Phase 7 — History`
- PRD 기준: `PRD-MA-001` v1.7.0, 2026-10-05
- 관련 요구사항: `FR-001`, `FR-020`, `SCR-001`, `SCR-009`, `API-017`
- 영역: Frontend
- 선행 작업: `TASK-014.01`; Home shell/route는 `TASK-004.01`
- GitHub Issue: [#54](https://github.com/donghyunlee-dev/meeting-automation/issues/54)

## 결과

Home에 최신 회의 최대 5건을 표시하고, 전체 보기에서 Meetings 화면으로 이동해 API-017의 최대 100건을 보여준다. 이 작업은 데이터 로딩과 목록 화면을 연결한다. 검색/필터, 상세 열기 동작은 `TASK-014.04`에서 완성한다.

## 화면 및 API 동작

### Home `/`

- 기존 Home 제목, 서비스 설명, `새 회의 시작` CTA 및 Bottom Navigation을 유지한다.
- Home 진입 시 `GET /api/v1/meetings?limit=5`를 한 번 호출해 최신순 목록을 표시한다.
- 각 목록 항목은 제목, 회의 일시, 참석자 수만 표시한다. 참여 roster 이름이나 이메일, 완료/활성 등의 상태 badge는 표시하지 않는다.
- `전체 보기`는 Meetings 화면으로 이동한다. 항목은 후속 `TASK-014.04`의 상세 연결까지 접근성 있는 비활성/대기 display row로 둔다.
- 결과가 없으면 짧은 Empty 안내를 목록 자리에 표시하고 새 회의 CTA는 유지한다.
- 첫 요청 중 Loading skeleton, 실패 시 안전한 Provider 오류 안내와 재시도 action을 표시한다. 재시도는 동일 API GET만 다시 호출한다.

### Meetings `/meetings`

- 화면 진입 시 `GET /api/v1/meetings`를 호출한다. API 기본 `limit=100`으로 최신 목록을 받는다.
- 각 항목은 제목, 회의 일시, 참석자 이름(최대 3명과 나머지 인원 수)을 표시한다. 이 스펙의 범위에서 검색 field/필터 동작 및 detail navigation은 후속 TASK-014.04에 둔다.
- Loading, 전체 결과가 없는 Empty, Provider Error/재시도 상태를 제공하고 Bottom Navigation을 표시한다.
- 리스트는 API의 최신순을 보존하며 FE에서 결과를 재정렬하지 않는다. Provider가 전달한 회의 일시의 offset을 보존해 사용자 locale에 맞는 표시 형식으로 보여준다.

## 경계 및 접근성

- 두 화면은 API-017 `{data:{items}}`만 소비한다. Provider별 분기, 문서 URL 조작, 상세 본문 조회는 하지 않는다.
- Home의 5건과 Meetings의 100건은 별도 요청/캐시 key를 사용한다. 동일 요청 중 중복 fetch를 방지하고 화면 재진입 시 기존 FE cache policy를 따른다.
- 오류 화면은 provider 원문/Secret을 표시하지 않는다. 재시도 action은 키보드 조작 및 accessible name을 가진다.
- 리스트/empty/loading/error 컨테이너는 작은 모바일 폭에서도 가로 overflow 없이 읽을 수 있어야 한다.

## 비범위

- 제목·참석자 검색/날짜 필터, 목록 항목에서 상세 페이지로 이동, 상세 및 Provider 원문 열기는 `TASK-014.04`다.
- API 구현/Provider adapter는 `TASK-014.01`; Meeting Detail API는 `TASK-014.02`다.
- 참가자 존재/활성 또는 Meeting 진행/완료 상태를 목록에 추가하지 않는다.

## 완료 기준

- Home은 API `limit=5` 결과를 최신순으로 최대 5건 표시하고 전체 보기 이동이 동작한다.
- Meetings는 기본 최대 100건 결과를 표시한다.
- 각 화면에서 loading, empty, provider error와 retry 동작을 테스트한다.
- list item에는 제목·일시·참석자 정보만 표시하며 status badge/email/Secret이 없다.
- 기존 Home CTA 및 Bottom Navigation이 보존되고 접근성·360px 폭 검증이 통과한다.
- 완료 기준이 [검증 계획](./test.md)의 사례에 연결된다.

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

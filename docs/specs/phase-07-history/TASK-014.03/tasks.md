# 작업 목록

## 사전 조건

- `TASK-004.01` Home shell과 `/` route가 준비되어 있다.
- `TASK-014.01` API-017 및 MeetingSummary type이 제공된다.
- 저장소의 기존 FE Router, API client, query/cache, design tokens 및 테스트 규칙을 따른다.

## 구현 단계

- [ ] route/API client 구성과 기존 Home/Bottom Navigation을 확인한다. 결과: 변경할 기존 entry/component/test 파일 목록이 확정된다.
- [ ] API-017 client/query 테스트를 먼저 작성한다: 기본 및 `limit=5`, cache key 분리, loading/success/empty/error/retry 상태를 검증하고 예상대로 실패하는지 확인한다.
- [ ] Home component 테스트를 작성한다: 최대 최근 5건, 제목/일시/참석자 수, 전체 보기, 새 회의 CTA와 Empty 안내를 검증한다.
- [ ] Meetings page 및 route 테스트를 작성한다: 기본 100건, loading/empty/error/retry, navigation shell을 검증한다.
- [ ] API typed client/query hook을 구현하고 Home/Meetings 화면을 연결한다. response data 외에 provider 원본을 다루지 않는다.
- [ ] 키보드/접근성 name, status badge 미표시, 360px 가로 overflow 및 기존 Bottom Navigation/CTA 보존을 검증한다.
- [ ] FE 관련 unit/component/router tests, lint/build를 실행하고 `docs/evidence/TASK-014.03.md`에 비민감 결과를 기록한다.

## 완료 확인

- 모든 완료 기준에 자동화 테스트 또는 지정된 viewport 수동 점검 증거가 있다.
- 검색/필터 및 Detail link 기능을 TASK-014.04에 남겨 scope가 중복되지 않는다.
- `git diff --check`와 접근성/민감정보 검토를 통과한다.

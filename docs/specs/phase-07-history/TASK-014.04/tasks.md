# 작업 목록

## 사전 조건

- TASK-014.02 API-018 및 TASK-014.03 Meetings list 화면/route가 준비되어 있다.
- 프로젝트의 FE Router, API query/cache, date picker, modal/dialog 접근성 규칙을 따른다.
- 구현은 FE unit/component/router 테스트 우선으로 진행한다.

## 구현 단계

- [ ] 기존 Meetings list component와 date control conventions를 확인하고 filter utility 계약을 정한다: NFKC/case-insensitive substring, local inclusive dates, AND predicate, stable order.
- [ ] Filter utility tests를 구현 전에 작성한다. 제목/attendee match, blank query, start/end only, inclusive same-day/range, date reverse, clear, month boundary를 검증한다.
- [ ] Meetings UI interaction tests를 작성한다: text/date 입력, 결과 없음 vs 원본 Empty, reset, accessible row navigation, query 재호출 없음.
- [ ] API-018 client와 Detail route/page tests를 작성한다: success mapping, 404, Provider error/retry, transcript dialog, no mutation controls.
- [ ] Filter utility와 list controls를 구현하고 URL query string이 요구되지 않는 범위에서는 filter state를 local UI state로 둔다.
- [ ] Detail page/Minutes view/Transcript Sheet를 구현한다. Participant는 id/name만 표시한다.
- [ ] 외부 document link는 `http(s)` scheme와 hostname 검증 후 사용자 click에서만 열고 `noopener noreferrer`로 격리한다.
- [ ] FE tests/lint/build, keyboard and 360px manual QA를 완료하고 `docs/evidence/TASK-014.04.md`에 결과를 기록한다.

## 완료 확인

- 모든 완료 기준에 테스트 또는 수동 QA 증거가 연결된다.
- API-017 반환 상한을 넘는 server/filter 요청이나 Provider 전용 처리가 없다.
- `git diff --check`, 민감정보 및 external-link 검토를 통과한다.

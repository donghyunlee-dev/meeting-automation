# Speaker 선택 UI 작업 항목

관련 Issue: [#35](https://github.com/donghyunlee-dev/meeting-automation/issues/35).

1. Review route/state owner, API-010 Speaker DTO, API-003 query, API-001 App Config 및 SCR-011 navigation을 확인한다. 완료 증거: 기존 client/cache/state 경계를 PR에 기록한다.
2. API fixtures 기반 기존 mapping/null/loading/empty/error/retry/provider-null/settings-return/keyboard 테스트를 먼저 작성한다. 완료 증거: 각 화면 상태 test가 구현 전 실패한다.
3. speaker 단위 accessible row와 controlled Participant Dropdown을 구현한다. 완료 증거: 각 Speaker에 select 하나만 렌더되고 변경 시 같은 speakerId draft만 갱신된다.
4. API-003 roster를 ID로 API-010 mapping과 결합해 option 이름을 표시한다. 완료 증거: 이메일 미표시, 기존 mapping 표시, 재조회 후 값 보존을 확인한다.
5. API-001 `document.provider=null`/`configured=false`에서 SCR-011 Settings 링크, 빈 roster에서 Participants 관리 경로를 연결한다. 완료 증거: 자동 Provider 선택 없이 현재 Review/draft 복귀가 유지된다.
6. 선택 draft를 Review 상위 form callback으로 전달한다. 완료 증거: API-011 호출이나 저장 완료 UI 없이 후속 TASK-007.03 소비 contract를 제공한다.
7. `npm run test`, `npm run lint`, `npm run build`와 mobile/accessibility QA를 수행한다. 완료 증거: 명령 결과 및 화면 동작을 Issue에 남긴다.

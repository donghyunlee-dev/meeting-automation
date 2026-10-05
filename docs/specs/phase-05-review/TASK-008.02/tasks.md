# Minutes 편집 및 Transcript Drawer 작업 항목

관련 Issue: [#38](https://github.com/donghyunlee-dev/meeting-automation/issues/38).

1. Review state owner, API-010 Minutes/Transcript/Speaker DTO, API-003 roster, API-001 Provider config, reusable Drawer와 접근성 규칙을 확인한다. 완료 증거: state/cache/component 경계를 PR에 기록한다.
2. initial values/dirty/no-op/validation/saving/error retention, Transcript ordering/speaker join/focus tests를 먼저 작성한다. 완료 증거: fixture tests가 구현 전 실패한다.
3. Structured Minutes editor와 section/Action Item add-remove controls를 구현한다. 완료 증거: 전체 field shape, metadata 불변, null/date/text validation을 확인한다.
4. API-003 owner roster를 연결한다. 완료 증거: 이름만 보이고 provider null/error에서 existing owner/draft가 보존되며 Settings link가 나온다.
5. Transcript Drawer/Sheet를 구현한다. 완료 증거: 시간순 원문, 시간/speaker 표시, Escape/close/focus return이 작동한다.
6. parent callbacks로 full draft 및 saving/saved/error state를 연결하되 API-012는 호출하지 않는다. 완료 증거: clean/save/error states가 caller signal에 맞고 가짜 성공 표시가 없다.
7. `npm run test`, `npm run lint`, `npm run build`와 mobile/accessibility QA를 실행한다. 완료 증거: commands/evidence를 Issue에 기록한다.

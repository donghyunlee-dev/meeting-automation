# Speaker 선택 UI 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `FR-010`, `FR-011`, `SCR-006`, `TASK-007.02`
- 선행 TASK-006.07 Issue [#33](https://github.com/donghyunlee-dev/meeting-automation/issues/33): Processing 완료 후 Review route와 API-010 navigation gate
- 선행 TASK-003.01 Issue [#10](https://github.com/donghyunlee-dev/meeting-automation/issues/10): API-003 표준 Participant roster
- API-001: `document.provider`, `document.configured` 및 Secret 비노출
- UI source: `docs/product/ui-design.md` SCR-006, SCR-011, 접근성/mobile 기준
- 본 TASK Issue [#35](https://github.com/donghyunlee-dev/meeting-automation/issues/35)
- 후속 TASK-007.03: API-011 저장 및 Transcript mapping 통합

## 변경 경계

- Review feature/view: API-010 Speaker 목록을 Speaker row와 accessible Dropdown으로 렌더링
- Participant query hook: API-003 roster loading/empty/error/retry 및 cache 재사용
- App config selector: 기존 API-001 값으로 Provider 미선택/미설정 Settings 안내 분기
- Draft state: Review의 speakerId→participantId 편집값을 기존 Review state owner로 전달
- Settings navigation: SCR-011 Settings 및 Participants 관리 경로와 현재 Review return location 유지
- API-011 backend call/optimistic version/update-response merge는 구현하지 않고 TASK-007.03에 둔다.

## 구현 순서

1. Review route/state owner, API-010 query result, API-003 query/cache, API-001 app config, Settings/Participants navigation을 확인한다. 결과: source of truth와 draft 소유 컴포넌트가 명확해진다.
2. UI fixture를 이용해 기존 mapping 표시, null 선택, loading/empty/error/retry, Provider-null Settings link, keyboard/mobile test를 먼저 작성한다. 결과: UI 상태 및 요구 동작이 구현 전 테스트로 고정된다.
3. Speaker별 controlled Dropdown component와 ID-keyed local mapping draft를 구현한다. 결과: 변경한 Speaker만 draft에서 달라지고 모든 segment별 입력은 생기지 않는다.
4. API-003 roster query를 연결하고 `participantId` 기준으로 label을 결합한다. 결과: 이름만 보이며 roster 재조회에도 기존 selection이 보존된다.
5. API-001 Provider 설정 분기를 연결해 미설정 시 Settings 링크, roster empty 시 Participants 관리 경로를 제공한다. 결과: 자동 Provider 선택 없이 적절한 후속 화면을 안내한다.
6. Review form callback으로 draft를 노출하고 API-011 저장은 호출하지 않는 것을 검증한다. 결과: TASK-007.03 통합 지점이 고정되고 가짜 저장 완료가 없다.
7. Frontend test/lint/build와 접근성·모바일 QA를 수행한다. 결과: 사용한 명령 및 주요 화면 증거를 Issue에 기록한다.

## 검증 명령

Frontend 프로젝트의 기존 package scripts인 `npm run test`, `npm run lint`, `npm run build`를 사용한다. API-010/API-003/API-001 fixture 및 360px viewport, keyboard, focus/label 접근성 QA를 확인한다.

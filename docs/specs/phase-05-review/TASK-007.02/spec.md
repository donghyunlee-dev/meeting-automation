# Speaker 선택 UI

## 목표

SCR-006 Review에서 감지된 각 Speaker에 Participant Dropdown 하나를 표시한다. API-010의 기존 mapping을 선택값으로 보여주고, 회의 생성자가 선택한 API-003 roster에서 이름을 골라 speaker 단위 draft로 유지한다. PRD v1.7.0 (2026-10-05), `FR-010`, `FR-011`, `SCR-006`, `TASK-007.02`를 구체화한다. Issue [#35](https://github.com/donghyunlee-dev/meeting-automation/issues/35).

## 범위

- Review에서 Speaker 단위 mapping 목록 렌더링
- API-010 speaker `participantId`와 API-003 `{id,name,email}` roster를 결합해 현재 선택 표시
- 미선택 placeholder와 단일 Speaker당 하나의 Participant Dropdown
- 사용자가 편집한 값의 local draft 유지 및 상위 Review form에 전달
- API-001의 `document.provider`/`configured` 기반 Settings 안내
- API-003 roster의 loading/empty/error/retry UX
- 접근 가능한 label, keyboard/touch 동작 및 mobile layout

## 비범위

API-011 저장 요청, version/`If-Match` 처리, backend mapping 결과의 Transcript 반영(`TASK-007.03`), Transcript segment 단위별 Participant 선택, Speaker 자동 식별, Participant 생성/수정은 포함하지 않는다.

## UI와 데이터 계약

- Review의 Speaker 목록을 기준으로 row를 만들고 각 row는 `label`(예: Speaker A)과 하나의 Participant select를 가진다. 같은 Speaker의 모든 Transcript segment에는 별도 select를 만들지 않는다.
- API-003은 `GET /api/v1/participants`를 사용한다. 성공 roster item의 `{id,name,email}` 중 id로 현재 `participantId`를 매칭하고 이름만 option/선택 라벨로 표시한다. Email은 dropdown에 표시하지 않는다.
- `participantId`가 roster 항목과 일치하면 기존 mapping을 선택값으로 표시한다. null/미지정은 `참가자 선택` placeholder로 표시한다. 자동 화자 실명 인식 또는 이름 추론은 하지 않는다.
- API-003 결과가 준비되기 전 기존 `participantId`와 사용자의 local draft를 보존한다. 로딩이 끝나면 roster ID로 값을 reconcile하되, roster 조회 오류/빈 결과만으로 draft나 API-010의 기존 mapping을 지우지 않는다.
- 사용자의 변경은 local draft에 즉시 반영하고 Review form callback으로 `{speakerId,participantId}` mapping set을 전달한다. 이 Task에서는 API-011을 호출하거나 저장 성공으로 표시하지 않는다.
- API-001의 `document.provider=null`이면 provider를 자동 선택하지 않는다. Dropdown 대신 문서 Provider 연결이 필요하다는 설명과 SCR-011 Settings로 가는 명시적 링크를 제공한다. `provider`가 선택되어도 `configured=false`이면 같은 Settings 안내를 제공한다. Settings 이동 후 Review로 돌아와도 선택 draft를 유지한다.
- API-003 일시/영구 오류는 safe message와 다시 불러오기 동작을 제공한다. App Config상 Provider 미선택/미설정이면 재시도만 요구하지 말고 Settings 이동을 우선 행동으로 제공한다. Provider 원문/Secret은 노출하지 않는다.
- API-003 성공이 빈 `items`이면 목록 empty 설명과 SCR-011 Participants 관리 경로를 제공한다. 이 상태에서 기존 draft를 초기화하거나 API-011을 호출하지 않는다.
- Loading/empty/error/settings 상태에서도 speaker row와 기존 mapping draft를 보존한다. Dropdown에 현재 값이 없을 때 임의의 다른 Participant를 자동 선택하지 않는다.

## 수용 기준

- API-010의 각 detected Speaker에 Dropdown 하나만 보이고 기존 매핑/미지정 값이 정확히 반영된다.
- API-003 roster 이름이 표시되고 이메일은 표시되지 않으며 선택 변경은 speaker 단위 local draft로 전달된다.
- Segment 단위 mapping UI 및 mapping 자동 추론이 없다.
- Loading, empty, retryable error, `document.provider=null`, `configured=false`에서 선택 draft와 기존 mapping을 보존한다.
- Provider 미선택/미설정 시 자동 설정 변경 없이 Settings 경로를 제공하고, roster empty에서는 Participants 관리 경로를 제공한다.
- Dropdown은 접근 가능한 이름, keyboard operation, focus indicator, 최소 터치 target, 모바일 폭을 지원한다.

## 결정 및 전제

API-010은 Session 내 Speaker와 `participantId`를 제공하고 API-003은 현재 표시용 roster 이름을 제공한다. 두 결과는 Participant ID를 join key로 결합한다. 이번 Task는 선택 UI와 draft만 소유하며 지속 저장은 후속 TASK-007.03에서 API-011과 연결한다. `document.provider=null`은 Settings 연결 안내로 처리하고 자동 Provider 선택을 하지 않는다.

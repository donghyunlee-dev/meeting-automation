# Speaker 선택 UI 검증 계획

관련 Issue: [#35](https://github.com/donghyunlee-dev/meeting-automation/issues/35).

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| SCR-006-01 | API-010에 여러 Speaker, API-003 유효 roster | Speaker별 row/select 하나, option label은 name, email 미표시 | Review component test |
| SCR-006-02 | API-010 speaker에 roster 내 `participantId` | 해당 Participant 이름이 현재 선택값으로 표시 | mapping display test |
| SCR-006-03 | null/미지정 `participantId` | `참가자 선택` placeholder, 자동 선택 없음 | unmapped state test |
| SCR-006-04 | Dropdown에서 Speaker 하나의 Participant 변경 | draft에서 해당 `speakerId` mapping만 갱신되고 parent callback으로 전달 | controlled state test |
| SCR-006-05 | Review Transcript 여러 segment가 같은 `speakerId` 사용 | segment-level Dropdown 없이 한 Speaker row만 표시 | grouping/render test |
| SCR-006-06 | API-003 pending 또는 refetch 중 API-010 mapping이 있음 | loading 표시와 기존 선택/draft 보존 | query lifecycle test |
| SCR-006-07 | API-003 빈 `items` | empty 설명 및 Participants 관리 경로 표시, mapping draft 보존 | empty roster test |
| SCR-006-08 | API-003 일시/영구 오류, Provider 설정됨 | safe error와 retry, 입력 draft 보존 | error/retry component test |
| SCR-006-09 | API-001 `document.provider=null` | 자동 선택/Secret 노출 없이 SCR-011 Settings 링크, dropdown 미선택 상태 | provider null routing test |
| SCR-006-10 | API-001 provider 존재, `configured=false` | 연결 설정 필요 안내와 Settings link, 현재 Review 경로/draft 보존 | settings routing test |
| SCR-006-11 | API-003 success/refetch 후 기존 mapping ID 유지 | same ID option이 계속 selected, 변경되지 않은 speaker draft 보존 | ID reconciliation test |
| SCR-006-12 | Review view mount/select action | API-011 PUT 호출 없음, mapping saved 성공 표시 없음 | API boundary mock assertion |
| SCR-006-13 | keyboard-only 사용 및 360px viewport | label/focus/select 조작 가능, 가로 overflow 없음, touch target 기준 충족 | accessibility/browser test |

Frontend 명령 `npm run test`, `npm run lint`, `npm run build`로 자동 검증하고 결과를 기록한다. 설계 단계에서는 테스트를 실행하지 않는다.

## 통합/수동 QA

- 정상 Review Session에서 기존 speaker mapping을 확인하고 각 Dropdown에서 roster Participant를 선택/해제한다.
- 동일 Speaker를 참조하는 여러 Transcript segment가 후속 TASK-007.03 연동 시 같은 선택값을 공유할 수 있도록 draft key가 `speakerId`인지 확인한다.
- roster 조회 loading/empty/error를 각각 재현해 local draft가 지워지지 않는지 확인한다.
- `document.provider=null` 또는 `configured=false` fixture에서 Provider를 자동 선택하지 않고 SCR-011 Settings로 이동하며 Review를 다시 열었을 때 draft가 유지되는지 확인한다.
- screen reader label, 키보드 포커스, 작은 화면 select 동작과 email 비노출을 확인한다.

## 릴리스 확인

- Review 화면에서 유효한 API-010 Session이 없을 때 Speaker Dropdown을 빈 임시 상태로 꾸며내지 않는다.
- Provider/config 조회 오류에서 Secret 및 Provider 응답 원문을 사용자 메시지에 표시하지 않는다.

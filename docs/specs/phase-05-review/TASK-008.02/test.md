# Minutes 편집 및 Transcript Drawer UI 검증 계획

관련 Issue: [#38](https://github.com/donghyunlee-dev/meeting-automation/issues/38).

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| SCR-006-14 | API-010 complete StructuredMinutes fixture | 요약/논의/결정/Action Items/후속 값이 각 field에 표시 | Minutes editor component test |
| SCR-006-15 | summary/list/action task/date/owner 편집 | 전체 StructuredMinutes draft callback, template metadata 불변 | controlled form test |
| SCR-006-16 | 각 string-list/Action Item add/remove | 항목 순서와 삭제/추가 결과가 local draft에 반영 | dynamic field test |
| SCR-006-17 | 빈 summary 및 빈 arrays | 유효 empty draft, field 오류 없음 | empty draft test |
| SCR-006-18 | list item/action task 공백-only 입력 | 해당 field inline validation, 유효 draft callback 억제 | form validation test |
| SCR-006-19 | 유효 `YYYY-MM-DD`, invalid date, blank date | 유효 날짜 보존, invalid 차단, blank는 null | date input test |
| SCR-006-20 | owner roster select/null | Participant name option과 미지정 표시, email 미표시 | owner select test |
| SCR-006-21 | API-003 loading/empty/error 또는 API-001 provider null | Minutes 본문 편집 유지, 기존 owner/draft 보존, Settings 안내 | provider roster state test |
| SCR-006-22 | initial draft와 같은 값을 가진 form | clean/no-change state, save 불필요 | dirty tracking test |
| SCR-006-23 | caller saving/error/saved props 변경 | submit 중 feedback, 오류 뒤 draft 유지, 성공 문구는 caller 신호만 반영 | save state presentation test |
| SCR-006-24 | Drawer open with multiple TranscriptSegments | 원문이 `startMs` 순서, 시간/Speaker name 표시 | Transcript Drawer test |
| SCR-006-25 | unmapped speaker 또는 empty Transcript | 원래 Speaker label/empty guide, 자동 인물 추론 없음 | Transcript fallback test |
| SCR-006-26 | segment text에 HTML-like literal | markup 실행 없이 escaped plain text 표시 | safe text rendering test |
| SCR-006-27 | Drawer open/close, Escape, focus | close/backdrop/Escape 지원, 닫은 뒤 trigger로 focus 복귀 | dialog accessibility test |
| SCR-006-28 | keyboard-only 및 360px viewport | field/array controls/Drawer 사용 가능, overflow 없음 | accessibility/browser test |
| SCR-006-29 | API-001에 provider 값은 있으나 `configured=false` | 본문 편집/기존 owner 보존, Settings 연결 안내 | provider configuration state test |

Frontend 명령 `npm run test`, `npm run lint`, `npm run build`로 자동 검증하고 결과를 기록한다. 설계 단계에서는 테스트를 실행하지 않는다.

## 통합/수동 QA

- Review fixture로 기존 Minutes를 열어 모든 Structured fields, empty draft, dynamic list controls를 확인한다.
- Action Item owner를 선택/미지정하고 Date를 수정해 전체 draft 값이 정확히 유지되는지 확인한다.
- Provider 미선택/미설정 fixture에서 Minutes text 편집은 가능하고 owner 영역만 SCR-011 Settings 안내가 되는지 확인한다.
- Transcript Drawer에서 시간·Speaker·원문이 표시되고 긴 text가 HTML로 해석되지 않는지 확인한다.
- 저장 중/오류 mock 상태에서 입력값과 drawer/edit context가 보존되는지 확인한다.
- keyboard, screen reader label/focus, Escape/닫기 focus return, 360px layout을 확인한다.

## 릴리스 확인

- 본 Task UI에서 API-012 network request/성공을 위조하지 않고, 다음 TASK-008.03 callback 경계에서 저장을 연결할 수 있는지 확인한다.
- raw Transcript/Minutes/Participant email/Secret을 console/log/error에 기록하지 않는다.

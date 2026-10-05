# 검증 계획

## 자동화 테스트

| ID | 준비/입력 | 기대 결과 | 증거 |
|---|---|---|---|
| SCR-002-01 | App Config/Template/Participant GET pending | 해당 selector loading, 폼 draft 유지 | Vitest component/API fixture test |
| SCR-002-02 | App Config/Template/Participant 정상 fixture | company timezone과 Template/roster option 표시, 기본 Template `default.md` 선택 | Component test |
| SCR-002-03 | API-001/002/003 각각 실패 fixture | 독립 안전 오류와 retry action 표시 | Component/API fixture test |
| SCR-002-04 | Template API 빈 목록 | template 안내, submit disabled | Component test |
| SCR-002-05 | Participant API 빈 목록 | roster 안내, submit 불가 | Component test |
| SCR-002-06 | name 공백/Template 미선택/Participant 없음 | 해당 필드 오류, submit callback 호출 안 됨 | Form validation test |
| SCR-002-07 | 미선택 roster member 추가 후 제거 | 선택 목록과 ID가 기대대로 바뀜 | Multi-select test |
| SCR-002-08 | 유효 제목/Template/Participant 및 company config | callback에 trim된 `{title,templateId,participantIds,timezone}` 전달 | Component test |
| SCR-002-09 | 조회/검증 오류 발생 후 재시도 또는 수정 | 기존 입력과 선택이 보존됨 | Component test |
| SCR-002-10 | request mock 검사 | GET API-001/002/003만 호출하고 API-006 POST는 없음 | API client mock assertion |
| SCR-002-11 | viewport/keyboard 검사 | 360px overflow 없음, labels/focus/44×44 target 충족 | Accessibility/browser QA |

Frontend 기반 Issue #2가 정한 `npm run test`, `npm run lint`, `npm run build`로 검증한다. 구현 후 결과를 task evidence에 남긴다. 문서 설계 중에는 명령을 실행하지 않는다.

## 수동 QA

- `/meetings/new`에서 company timezone, 기본 Template와 roster 선택지를 확인한다.
- 선택자에서 참석자를 추가/해제하고 선택한 이름/email을 확인한다.
- 제목/Template/참석자 누락 시 필드별 안내가 뜨고 입력값이 유지되는지 확인한다.
- Templates/Participants 요청 각각을 실패시킨 뒤 retry 동작을 확인한다.
- 유효 CTA 입력이 callback에 전달되고 이 화면에서 Session 생성 POST가 발생하지 않는지 확인한다.
- 360px 모바일 폭과 keyboard-only 조작을 확인한다.

## 릴리스 확인

실제 Session 생성 후 Recording 이동은 TASK-004.05에서 검증한다. Provider 선택 연결 안내는 Settings 화면 설계/연결 Task에 남긴다.

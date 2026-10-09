# 검증 계획

## 자동화 테스트

| ID | 준비/입력 | 기대 결과 | 증거 |
|---|---|---|---|
| SCR-001-01 | `/` Home route 렌더 | `Meeting Automation`, SCR-001 안내 문구, `새 회의 시작` 표시 | Vitest component test |
| SCR-001-02 | Home에서 CTA 클릭 | React Router location이 `/meetings/new`로 변경 | Router integration test |
| SCR-001-03 | Bottom Navigation 포함 shell 렌더 | 홈/회의록/설정 항목이 설계 순서로 한 번 표시 | Component test |
| SCR-001-04 | CTA keyboard focus 및 activation | 접근 가능한 이름, visible focus, keyboard activation 동작 | Component/accessibility test |
| SCR-001-05 | 360 CSS px viewport | Home 콘텐츠와 CTA 가로 overflow 없음 | Browser/component viewport test |
| SCR-001-06 | Home 초기화 | `API-017` 최근 회의 목록 요청 없음 | API mock assertion |

Frontend 기반 Issue #2에서 `npm run test`, `npm run lint`, `npm run build`를 정의한다. 이 작업은 구현 후 해당 명령을 실행한다. 이 설계 단계에서는 실행하지 않는다.

## 수동 QA

- 브라우저에서 `/`을 열어 제목, 안내 문구, CTA, Bottom Navigation을 확인한다.
- CTA 클릭 후 주소가 `/meetings/new`인지 확인한다.
- `/settings/participants`가 기존 Participants 화면을 표시하는지 확인한다.
- 모바일 폭 360px, 키보드 탭 이동, focus 표시, 버튼 터치 영역을 확인한다.
- Home 진입 시 recent-meetings API를 호출하지 않는지 확인한다.

## 릴리스 확인

New Meeting 화면을 포함한 실제 목적 페이지 연결은 TASK-004.03에서 검증한다. 최근 Meeting row/전체 보기는 TASK-014.03에서 추가한다.

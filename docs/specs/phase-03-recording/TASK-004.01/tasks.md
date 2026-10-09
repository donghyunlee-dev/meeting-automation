# 구현 작업 목록

1. 기존 frontend App, Participants 화면, 의존성, UI token, test script를 확인한다. 기대 결과: Router 미설치와 화면의 현재 진입점을 반영한 변경 위치가 확정된다.
2. Home content test를 작성한다. 기대 결과: 제목/안내/CTA가 없는 초기 앱에서 대상 테스트가 실패한다.
3. Router navigation test를 작성한다. 기대 결과: CTA 클릭 후 location이 `/meetings/new`가 아니면 실패한다.
4. React Router와 공유 App shell을 구현하고 기존 Participants 화면을 `/settings/participants`로 연결한다. 기대 결과: Home 추가 뒤에도 Participants 화면을 사용할 수 있다.
5. `/` Home component와 SCR-001 Bottom Navigation을 구현한다. 기대 결과: 안내 문구, CTA, 홈/회의록/설정 항목이 표시된다.
6. CTA에 React Router navigation을 연결한다. 기대 결과: 테스트용 destination fixture가 화면 경로를 확인한다.
7. 360px viewport와 접근성 확인을 수행하고 자동화 테스트/lint/build를 실행한다. 기대 결과: overflow 없이 버튼이 keyboard/focus로 접근된다.

최근 회의 데이터 목록은 [TASK-014.03](../../../product/PRD.md) 경계에 따라 이 작업에서 구현하지 않는다.

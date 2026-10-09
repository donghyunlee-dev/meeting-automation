# 구현 작업 목록

1. 기존 frontend scaffold와 Node engine, package scripts, API client patterns를 확인한다. 재사용 가능한 React/Vite/Vitest 환경이 있으므로 불필요하게 새 scaffold, scripts 또는 dependency를 만들지 않는다.
2. API mock으로 list loading/ready/empty/error 및 재시도 컴포넌트 테스트를 작성한다. 기대 결과: API 응답별 UI가 검증 전 실패한다.
3. 검색 테스트를 작성한다. 기대 결과: 이름/email 부분 문자열 검색, 검색 결과 없음, 검색어 초기화가 검증된다.
4. create/edit form 테스트를 작성한다. 기대 결과: 필드 label, 초기값, 검증 오류, submit 흐름이 검증된다.
5. 타입 지정 Participant API client와 오류 변환을 구현한다. 기대 결과: API-003~005 응답이 UI 표준 타입으로 처리된다.
6. 목록, search, add/edit form을 SCR-012에 연결한다. 기대 결과: 모바일 화면에서 목록과 폼이 접근 가능하다.
7. POST/PATCH 연동과 생성 요청 Key 수명 관리를 구현한다. 기대 결과: 재시도 중복 생성 방지, 성공 목록 갱신, 실패 입력 보존이 테스트된다.
8. 키보드 focus, 화면 폭, 44×44px touch target 및 `npm test`, `npm run lint`, `npm run build`를 확인한다. 기대 결과: SCR-012 수용 기준과 QA 증거가 완성된다.

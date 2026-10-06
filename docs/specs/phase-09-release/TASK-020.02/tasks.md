# 주요 화면 비동기 상태 작업 목록

- [ ] 각 SCR의 Loading/Empty/Error/Ready 적용성을 spec matrix에 맞춰 fixture와 테스트 case ID로 고정한다. 의존: TASK-020.01, TASK-016.03, TASK-017.03 계약 확인. 완료 결과: 구현할 화면 상태 및 비적용 상태가 식별된다.
- [ ] AsyncState 및 error normalization 실패 테스트를 먼저 작성한다. 의존: 상태 계약 표. 완료 결과: 초기 loading/empty/ready/error, stale-data refetch error, 안전한 static error mapping이 실패로 재현된다.
- [ ] 최소 AsyncState UI와 정규화 계층을 구현한다. 의존: 위 red tests. 완료 결과: component tests 통과, 서버 오류 원문이 props/render tree에 포함되지 않는다.
- [ ] Home, Meetings, Meeting Detail, Participants GET 경로를 공통 상태 UI로 연결한다. 의존: 공통 primitive. 완료 결과: zero items, query no-match, 404 및 GET retry 구분 테스트 통과.
- [ ] New Meeting, Recording, End Confirm, Processing, Review를 상태 UI로 연결한다. 의존: query group 연결. 완료 결과: draft/recording 입력 보존, Processing lifecycle 분리, Audio 실패 action 제한 테스트 통과.
- [ ] Share, Complete, Settings 상태를 연결한다. 의존: 화면 상태 UI. 완료 결과: `provider:null` Settings 유도 및 retry 가능한 delivery 외 재전송 방지 테스트 통과.
- [ ] 각 상태 전이의 component/integration tests, lint, build를 실행한다. 의존: 화면 적용 완료. 완료 결과: `test.md` 자동 사례와 정확한 명령/결과가 채워진다.
- [ ] viewport smoke와 수동 개인정보/error-copy 확인 뒤 Evidence를 작성한다. 의존: 자동 검증. 완료 결과: `docs/evidence/TASK-020.02.md`에 route/state/fixture/결과/follow-up이 기록된다.

# History·보안·임시 Audio E2E 작업 목록

> 📌 v1.9.0 변경 계약: TASK-022.06의 최초 연결·양방향 이전·참조 무결성·source 보존·재시작 journal 사례를 회귀한다. release는 계정 없는 설정 UI와 durable 설정을 포함한다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

- [ ] TASK-021.01/021.02와 TASK-014.01~.04, TASK-017.01~.04 API/UI/storage 계약이 구현되어 있는지 확인한다. 의존: Issues #77/#78, #52~#55, #60/#61/#62/#64. 결과: endpoint, route, allowedActions, retention, cleanup assertion 목록.
- [ ] deterministic Clock 및 재시작 가능한 private storage fixture를 구성하고 public object 접근을 거부한다. 의존: Backend API-020~022. 결과: 가상시간 이동, 객체 저장/stream/delete 관찰, 프로세스 재시작 후 만료 조회가 가능하다.
- [ ] History 목록/상세·필터·원문 링크와 반복 GET read-only E2E를 먼저 작성한다. 의존: API-017/018 및 SCR-009/010. 결과: 조회 전후 문서·delivery/session version 및 외부 호출 수가 동일하다.
- [ ] 성공 처리의 IndexedDB ACK chunk 삭제와 Review 진입 private Audio cleanup 시나리오를 작성한다. 의존: API-007 ACK, TASK-017.02 storage port. 결과: 두 저장 영역에 잔여 객체가 없다.
- [ ] 실패 응답에서 허용된 재시도 UI/API-020 연결, 중복/오래된 version 차단, 성공 후 정리를 작성한다. 의존: API-010/020. 결과: 완료 단계 skip 및 재시도 성공 시 Review cleanup 확인.
- [ ] 재시도 실패 후 API-021 native download 및 API-022 DOWNLOADED, 별도 DISCARDED 분기를 작성한다. 의존: TASK-017.03. 결과: 각각 failure-only 문서가 완성되고 Email/Slack delivery는 0이다.
- [ ] 24시간 전후 action/API 경계, TTL reset/pause, sweeper 및 재시작 cleanup을 작성한다. 의존: DEC-020 및 durable expiry 구현. 결과: 만료 즉시 재시도/다운로드 불가, 객체 삭제와 Discarded 확정이 멱등이다.
- [ ] 민감 canary 비노출, `provider:null` 화면 연결, no-egress와 sanitized evidence를 통합한다. 의존: API-001/019, TASK-017.01/.04. 결과: 비밀 값이 없는 scan 결과 및 setup 안내 화면 검증.
- [ ] `npm run test:e2e:history-security`를 2회 실행해 증거를 작성한다. 의존: 전체 scenario. 결과: `docs/evidence/TASK-021.03.md`에 안전한 집계 및 환경/commit 정보만 기록한다.

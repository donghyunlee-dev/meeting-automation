# History·보안·임시 Audio E2E 구현 계획

## 선행 조건 및 소유권

TASK-021.01의 dual-server Playwright harness, TASK-021.02의 API/provider fixture 경계와 `TASK-014.*`, `TASK-017.*` 계약 구현이 선행한다. QA가 통합 scenario와 evidence를 소유한다. FE/API는 이미 정해진 계약을 browser 경로에서 연결하고, private storage/clock/sweeper는 backend test fixture가 제어한다. UI/API 통합 시나리오가 주 deliverable이므로 새 API나 UI를 먼저 설계하지 않고, 기존 계약 표를 fixture로 고정한 뒤 실패-first E2E를 만든다.

## 변경 대상

| 위치 | 책임 |
|---|---|
| `frontend/tests/e2e/history-security.spec.ts` | History read-only, retry/download/discard/expiry action visibility, native download, attendee-send 금지 검증 |
| `frontend/package.json` | `npm run test:e2e:history-security` 명령 추가 |
| `backend/src/test/.../HistorySecurityE2eFixture` | synthetic meeting, deterministic Clock, persistent private-storage fake, failure-stage/sweeper controls |
| `backend/src/test/.../PrivateAudioStorageFixture` | opaque key와 attachment stream만 제공, public access 거부, 삭제 횟수/바이트 결과만 계측 |
| `frontend` test config 또는 test harness | API/browser state 초기화, IndexedDB ACK chunk 시드 및 cleanup 검사 |
| `docs/evidence/TASK-021.03.md` | scenario ID, safe aggregate, cleanup, no-egress/non-disclosure 결과 |

실제 저장소나 public bucket을 만들지 않는다. HTTP 응답은 기존 API 계약을 그대로 쓰고 테스트 전용 시계/저장소 의존성으로 실행한다. Test instrumentation endpoint를 제품 API에 추가하지 않는다.

## 실행 순서

- fixture부터 deterministic Clock, synthetic failure state, in-memory private object store를 준비한다. key는 프로세스 내부에서만 다루고 보고서는 존재 여부/삭제 결과만 기록한다.
- History GET 시나리오에서 API-017 목록/API-018 상세를 재조회하고 검색/필터/원문 링크 동작을 검증한다. 조회 전후 ETag/version, Document/Delivery aggregate 및 Provider 호출 수를 비교한다.
- 성공 경로로 API-007 ACK된 chunk의 IndexedDB 제거와 Review 진입 시 backend private chunks/assembled Audio 정리를 검증한다.
- 실패 경로에서 API-010 allowedActions에 따른 재시도 표시/API-020 호출, 실패 stage resume 및 새 실패의 TTL 재설정을 검증한다. 성공 재시도는 Review cleanup으로 끝난다.
- 재시도 실패 뒤 다운로드/폐기 선택을 각각 수행한다. 다운로드는 native attachment 이벤트 및 완료 확인 후 API-022 DOWNLOADED를 호출한다. 폐기는 API-022 DISCARDED를 호출한다. 양쪽 모두 Document-only이며 delivery count는 0이다.
- deterministic clock을 24시간 경계 전/후로 이동한다. 만료 직후 action/API 거절, sweeper 삭제, 회의록 자동 확정, backend 재시작 뒤 durable expiry cleanup을 검증한다.
- canary scanner는 허용/금지 위치를 명시하고 값은 메모리에서만 대조한다. 결과는 rule ID와 총 건수만 낸다. `TASK-017.04`의 adapter/API/log scanner 하위 테스트를 복제하지 않는다.

## 보안 경계

- e2e profile은 synthetic secret/audio/text와 loopback app만 사용하고 public network egress를 차단한다.
- API-021은 application endpoint를 통해서만 attachment bytes를 전달한다. object key/direct URL을 API/browser/error/log/Evidence에 노출하지 않는다.
- browser 다운로드는 Playwright download artifact로 확인하며 앱 상태, React store, local/session storage, IndexedDB에 전체 Audio/Blob/object URL이 남지 않음을 검사한다. 실제 사용자의 다운로드 폴더/파일은 시험하지 않는다.
- 로그 및 Evidence는 secret/audio/transcript/minutes 문자열을 출력하지 않는다. sanitizer 확인은 synthetic canary에 대해 count만 출력한다.

## 명령과 완료 자료

- `frontend/`: 기존 `npm run test`, `npm run lint`, `npm run build`, `npm run test:e2e`; 신규 deliverable `npm run test:e2e:history-security`.
- `backend/`: `./gradlew clean build` (Windows `gradlew.bat clean build`).
- FE/browser/API/backend/storage fixture를 아우르는 history-security E2E를 2회 clean run하고 fixture/sweeper 상태가 run 간 공유되지 않는지 확인한다.
- Evidence: `docs/evidence/TASK-021.03.md`; 원문 본문, 주소, auth, 키, URL, Audio bytes, transcript 및 회의록 텍스트는 증거에 넣지 않는다.

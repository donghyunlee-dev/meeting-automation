# History·보안·임시 Audio E2E 검증 계획

> 📌 v1.9.0 변경 계약: TASK-022.06의 최초 연결·양방향 이전·참조 무결성·source 보존·재시작 journal 사례를 회귀한다. release는 계정 없는 설정 UI와 durable 설정을 포함한다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

## 격리와 실행

- Frontend: `frontend/`에서 `npm run test`, `npm run lint`, `npm run build`, `npm run test:e2e`; 신규 명령 `npm run test:e2e:history-security`.
- Backend: `backend/`에서 `./gradlew clean build` (Windows `gradlew.bat clean build`).
- Playwright는 TASK-021.01 loopback harness를 재사용한다. API/provider/storage는 synthetic fixture, egress guard, deterministic Clock으로 제어한다. Atlassian, Google, Slack 또는 실사용 Audio에 연결하지 않는다.
- 성공 경로에서 transcript/minutes 화면 데이터는 제품 UI 계약대로만 browser memory에 렌더링될 수 있다. 금지는 log/error/Evidence와 실패 문서 및 보안상 민감한 저장소 key/direct URL이다.

## 자동 E2E 시나리오

| ID | 입력/절차 | 기대 결과 | 증거 |
|---|---|---|---|
| HIST-LIST-DETAIL | 완료된 synthetic meeting을 API-017 목록에서 찾고 API-018 상세, 필터 및 원문 링크를 연다 | 저장된 값만 표시되고 편집/변경 mutation이 없으며 Provider URL은 기존 계약대로 연다 | request verb/count, safe document ID hash |
| HIST-REPEAT-READ | 목록/상세를 반복 GET하고 전후 snapshot 비교 | Session/document/delivery 상태와 version 불변, provider 호출 0 | before/after aggregate 동일 |
| PROVIDER-NULL | provider가 null인 meeting fixture를 표시 | 적절한 연결/설정 화면으로 안내하고 provider를 임의 선택하지 않음 | route/action ID |
| AUDIO-ACK-CLEAN | API-007 ACK 성공 chunk가 있는 browser IndexedDB를 준비하고 완료 Review로 이동 | ACK chunk 삭제, 로컬 녹음 데이터 없음, backend private chunks 및 assembled Audio 삭제 | object count 0, local chunk count 0 |
| AUDIO-FAILURE-RETRY | STT 실패 API-010 응답에 retry 허용, retry click 후 API-020 새 key/current version으로 요청 | 실패 stage부터 재개, 완료 stage skip, Audio 비공개 유지; 성공이면 Review 정리 | stage IDs, safe status, delete count |
| AUDIO-RETRY-TTL | 초기 실패 후 retry 중 clock 이동, retry 실패를 주입 | 진행 중 필요한 Audio 보존/TTL pause; 새 실패시각 기준 `audioExpiresAt` 갱신 | synthetic timestamps, object existence |
| AUDIO-DOWNLOAD-FINALIZE | 두 번째 변환 실패 뒤 다운로드 선택, API-021 attachment가 성공, browser native download complete 후 API-022 DOWNLOADED | 파일 attachment만 내려받고 앱 state에 Blob/object URL 없음; failure-only Document 저장 후 private Audio 삭제 | download event, disposition, delete count |
| AUDIO-DOWNLOAD-GUARD | download 실패/권한 거부 또는 완료 전 finalize 요청 | DOWNLOADED 확정 거부, 사용자 재시도/폐기 선택 가능; Audio 및 failure draft 보존 | safe error code, disposition unchanged |
| AUDIO-DISCARD | 실패 회의에서 사용자가 폐기를 선택하고 API-022 DISCARDED | failure-only Document 확정, Audio/chunks 삭제, attendee email/Slack/share 0 | disposition, delivery count 0 |
| AUDIO-EXPIRY | clock을 24시간 경계 전후로 이동하고 API-020/021 시도 | 경계 전 허용 계약, 만료 즉시 UI action 제거 및 API 거부; sweeper 삭제 및 DISCARDED 확정 | fake-clock instants, denied action, delete count |
| AUDIO-RESTART-SWEEP | 만료 대상 저장 후 Backend fixture 재시작, sweeper 실행 | durable expiry로 객체 삭제 및 회의록 자동 확정; cleanup 반복도 멱등 | restart run ID, delete/finalize counts |
| PRIVACY-CANARY | synthetic secret/audio/transcript/minutes canary를 허용·금지 경계에 주입해 bundle/API/error/log/doc/report 검사 | Secret/key/direct URL은 어디에도 노출되지 않음; 실패 Document/log/Evidence는 raw Audio/Transcript/Minutes 미포함; 값 대신 rule ID/count만 출력 | rule ID와 검출 건수만 |
| NO-ATTENDEE-SEND | 재시도 실패 후 DOWNLOADED, DISCARDED 및 자동 만료 분기 실행 | Document만 확정하고 Email/Slack delivery 생성과 발송은 모두 0회 | delivery rows=0, provider send calls=0 |
| E2E-RESET-REPEAT | 저장소, clock, meeting fixture를 초기화하고 suite를 두 번 clean run | 이전 run의 key, 객체, 카운터 또는 상태가 다음 run에 잔존하지 않음 | 두 run 결과 집계 및 cleanup |

## 수동 QA

- nonproduction synthetic data로 History 상세가 read-only인지 확인하고 null Provider 안내 링크를 눌러 설정 화면 경로를 확인한다.
- 모바일 browser viewport에서 재시도, 다운로드, 폐기 control이 `allowedActions`에 따라서만 표시되는지 확인한다.
- API-021 응답을 browser download UI에서 저장하고 앱이 녹음 bytes/Blob URL을 보관하지 않는지 browser storage inspection으로 확인한다. 실제 회의 Audio, 실제 참석자, 개인 다운로드 폴더 파일은 사용하지 않는다.
- 실패 확정된 문서에 실패 단계 및 disposition이 있고 attendee email/Slack 결과나 Audio 원문이 없는지 synthetic fixture를 사용해 확인한다.

## 판정 및 증거

- 성공: 자동 시나리오 모두 통과, public egress 0, 만료/성공/실패 cleanup 완료, retry/download/discard 조건이 API/화면에서 일치, 실패 경로 attendee send 0.
- 실패: read-only GET이 상태를 변경, 미승인 retry/download 노출, 만료 Audio 접근 성공, public storage/key 노출, 실패 문서/증거에 민감값 포함, attendee delivery 발생 또는 cleanup 비멱등.
- Evidence `docs/evidence/TASK-021.03.md`에는 command/환경/commit, scenario ID/pass count, 상태/errorCode, 호출 횟수, fixture cleanup 집계만 기록한다. raw secret, URL, key, address, audio/transcript/minutes text 및 browser download bytes는 남기지 않는다.

## 🔗 전역 연결 소비자 실제 연동 회귀

회의 생성과 History 구현 이후 서비스 전환→새 회의 생성→이전된 자료 목록·상세 재조회 흐름을 실제 FE/BE로 검증한다. TASK-022.06의 소비자 fixture 합격으로 이 실제 연동 회귀를 대체하지 않는다.

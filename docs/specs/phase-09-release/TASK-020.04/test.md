# Android/iOS 전체 화면 회귀 검증 계획

## 실기기 QA

TASK-018.04의 verified browser support matrix를 먼저 불러온다. Android Chrome phone과 iPhone Safari 각각에서 같은 non-production app commit 및 synthetic fixture를 사용한다. 실제 모델/OS/browser version/mode와 viewport CSS px를 증거에 기록한다. 필요한 실기기/HTTPS build가 없으면 해당 matrix row를 미검증 처리한다.

| ID | 입력/절차 | 기대 결과 | 증거 |
|---|---|---|---|
| DEVICE-SUPPORT | TASK-018.04 matrix의 Android Chrome/iOS Safari 조합 조회 | 검증됨 조합과 이번 기기 run 구분; emulator/PWA/in-app browser를 Safari/Chrome tab과 섞지 않음 | source run ID, model/OS/browser/mode |
| DEVICE-SCREENS | 양 기기에서 SCR-001~012 대표 route 순회 | 주요 content/action visible, route navigation 정상, 각 route 결과 기록 | route/screen ID, fixture ID, sanitized capture |
| DEVICE-SURFACES | SCR-004 confirm, SCR-006 transcript drawer, participant picker 등 실행 | open/close, scroll, action 접근, 이전 screen 복귀 정상 | surface ID, pass/fail |
| DEVICE-NEW-MEETING | synthetic title/template/participants로 새 회의 생성 | validation 및 생성 후 route 정상; personal contact 사용 없음 | synthetic session ID, route |
| DEVICE-PERMISSION | browser microphone permission allow/deny/re-enable | 녹음 시작/오류/설정 복귀 안내 명확, 거절 상태에서 녹음중으로 잘못 표시하지 않음 | permission action/result, screen |
| DEVICE-RECORD-HANDOFF | 각 기기에서 screen foreground의 짧은 synthetic recording 후 종료 | final chunk 수락 후 API-009 1회, 동일 Session의 Processing 연결, upload 이전 Review 이동 없음 | session ID, chunk count/bytes aggregate, request count, timings |
| DEVICE-END-CONFIRM | 녹음 중 종료 sheet에서 cancel 후 다시 열어 confirm | 취소는 녹음을 이어가고 confirm은 stop/handoff 한 번 수행 | state/action 결과 |
| DEVICE-PROCESS-SUCCESS | mock processing success response 사용 | Processing status 다음 Review 접근; 화면 고정/뒤로가기 이상 없음 | stage/status sequence |
| DEVICE-PROCESS-FAILURE | synthetic retryable failure 및 만료/비재시도 fixture | 안내와 retry/download/finalize가 계약대로 나타나고 미허용 action 없음; 실패 기록 attendee에 미공유 | fixture, allowed action, outbound count=0 |
| DEVICE-REVIEW-SHARE | Review edit/confirm → mock document/email/notification → Complete | 수정 상태 유지, 결과 화면 접근; 실패 meeting 기록은 공유하지 않고 일반 success fixture만 mock publish | synthetic IDs, provider/outbound mock result |
| DEVICE-HISTORY-SETTINGS | Meetings/filter/detail, Settings/provider-null, Participants flows | Loading/Empty/Error/Ready와 row/detail/settings links 정상; provider 미선택 자동 선택 없음 | route/state outcomes |
| DEVICE-TOUCH-A11Y | 핵심 action hit target, keyboard/focus availability, TalkBack/VoiceOver status and names spot check | PRD 44×44 주요 target 및 52px primary/48px input 기준 확인, labels/status 알아볼 수 있고 actionable screen reader route 가능 | measurement, screen reader/device/build |
| DEVICE-REFRESH-BACK | 안전한 non-recording screens에서 refresh/back 사용, 녹음 중 동작 제한 안내 확인 | 잘못된 session 생성/중복 mutation 없이 예측 가능한 복귀; 녹음 중 페이지 떠남이 위험하면 UI 안내 | route sequence/request count |
| DEVICE-CLEANUP | 정상 processing 및 실패 fixture test 뒤 QA storage/outbound 확인 | Audio cleanup 또는 격리 object 정리됨; attendee outbound는 계속 0 | cleanup status, object aggregate, outbound count |

## 자동 및 반복 회귀

FE 변경이 없는 상태에서 구현분에 정의된 `frontend/` 명령 `npm run test`, `npm run lint`, `npm run build`, `npm run test:viewport`, `npm run test:a11y`를 실행한다. 누락된 script는 임의로 통과 처리하지 않고 미실행/미구현으로 표시한다. 실기기에서 재현된 failure가 수정될 때는 `TASK-018.04/test.md`의 동일 조건 재검증을 따르고, 별도 regression case와 수정 전/후 build를 Evidence에 남긴다.

## Evidence 및 판정

- 위치: `docs/evidence/TASK-020.04.md`
- 필수: TASK-018.04 source matrix, 기기/OS/browser/mode, app commit, route별 결과, synthetic fixture, recording/request aggregate, screen reader/target check, unresolved Issue, QA cleanup
- 금지: 실제 녹음/파형/Transcript, 개인정보, 개인 email/전화번호, auth header/Secret, provider raw response, public/signed storage URL
- 성공: 필수 Android/iOS 실기기 route/flow가 실행되어 기준을 통과하고 unresolved failure는 차단 이유 및 Issue로 표시되며 미검증 브라우저는 검증됨으로 오인되지 않는다.
- 실패: route unavailable 또는 blocker 미표시, 44px NFR target 미달, 잘못된 handoff/session, retry contract 위반, recording permission 상태 오표시, Audio 실패 attendee 전달, evidence data leak/cleanup 누락.

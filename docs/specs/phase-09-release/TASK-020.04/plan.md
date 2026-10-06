# Android/iOS 전체 화면 회귀 계획

## 선행과 소유권

- TASK-020.01 Issue #73: viewport matrix 및 layout 결함 기준
- TASK-020.02 Issue #74: Loading/Empty/Error/Ready 화면 상태
- TASK-020.03 Issue #75: touch target, keyboard, accessible name/status 기준
- TASK-018.04 Issue #68: 직접 재검증이 끝난 Android Chrome/iOS Safari MIME/browser 지원 조합
- PRD SCR-001~012, NFR-001~003/NFR-007, §21

이 task는 QA 소유다. 별도 자동 suite나 product feature를 만들지 않고 이전 세 task의 구현/자동 검증을 실기기에서 묶는다. 먼저 지원 matrix와 test build를 고정하고, 기기당 화면 route matrix를 진행한 뒤 간결한 meeting journey 및 접근성 spot checks를 실행한다. 재현 결함은 기존 구현 소유자인 FE/BE Issue로 기록하고 여기서는 임의로 수정하거나 fail을 pass로 바꾸지 않는다.

## 준비물

| 항목 | 준비 및 확인 |
|---|---|
| 기기 | 물리 Android phone 1대 이상 + iPhone 1대 이상; model/OS/browser version 기록 |
| 실행 환경 | HTTPS non-production deployed build/commit; Android Chrome tab, iOS Safari tab을 분리 기록 |
| API/provider | dedicated QA account, synthetic roster, mock processing/document/email/notification; outbound attendee message 차단 확인 |
| 저장소 | 격리된 QA object prefix; 성공/failure cleanup 상태를 확인할 QA 접근 경로 |
| test data | 긴 제목, 최소 2명 synthetic attendee, synthetic minutes/Transcript, retryable conversion failure fixture |
| 기준 | TASK-018.04 verified matrix, TASK-020.01~.03 Evidence/config 및 issue 목록 |

어떤 실기기나 HTTPS QA build도 준비할 수 없으면 이를 환경 blocker로 기록하고 해당 조합을 미검증으로 유지한다. Desktop emulation만으로 task 완료를 선언하지 않는다.

## 실행 매트릭스

| 실행군 | 기기 | 포함 흐름 |
|---|---|---|
| DEVICE-ANDROID | 실제 Android Chrome tab | SCR-001~012 대표 route, modal/drawer, touch/layout/state; fresh meeting→short record→process→review/share/history |
| DEVICE-IOS | 실제 iPhone Safari tab | 같은 fixture/route/flow를 별도 Session으로 반복; Safari 권한과 화면 차이를 독립 기록 |
| DEVICE-ACCESS | 양 기기 각각 | 핵심 touch targets, keyboard availability, OS screen reader spot checks, focus/status labels |
| DEVICE-RECOVERY | 각 browser | permission denied/re-enabled 안내, GET/query error, synthetic Processing failed/retry/download/finalize UI; 실패 회의록 attendee outbound 0건 |

모든 화면은 대표 ready 상태와 해당 route에 고유한 주요 상태를 포함한다. 020.02 전체 state matrix 및 020.03 exhaustive keyboard/axe suite는 대체 실행하지 않는다. modal/sheet/drawer는 화면 호출 route에서 열고 닫기까지 한 번 확인한다.

## 구현/API 계약 경계

- Android/iOS browser mode, model, OS, browser version은 run metadata다. OS/browser version을 조건 없이 범용 지원으로 표현하지 않는다.
- 실제 browser run은 기존 PRD API/state/Processing contracts로 처리한다. Test provider는 synthetic fixture만 돌려야 하며 real provider 호출이나 참석자 delivery는 막는다.
- Short recording은 각 기기에서 session 생성, recorder start/stop, 마지막 chunk 수락, API-009 1회 handoff, 동일 session의 Processing 결과로 이어지는 UI integration만 검사한다. Sequence recovery/hash/minutes recognition quality는 다시 인증하지 않는다.
- Processing retry는 backend `retryable`/보존 기한 및 allowed action을 따르고, mutation 불명 결과를 자동 재전송하지 않는다.
- 지원 matrix에는 TASK-018.04 source run ID 및 이번 `TASK-020.04` run ID를 별개 열로 둔다. 이 task의 route UI pass는 오디오 장시간 호환성 pass를 의미하지 않는다.

## 회귀 결함 처리

- 결함마다 device ID(비개인 식별값), OS/browser, commit, route, fixture, 절차, expected/actual, screenshot/DOM 또는 test run ID와 심각도를 적는다.
- 같은 synthetic input/build/단계를 반복해 재현을 확인한 후, 원 소유 영역 FE/BE와 함께 별도 defect Issue 또는 기존 tracking Issue에 연결한다.
- Production data/provider, 개인 녹음 또는 user account를 이용해 결함을 재현하지 않는다. 수정 뒤 조건이 바뀌면 원 run과 수정 run을 분리해 기록한다.

## 결과물

- `docs/evidence/TASK-020.04.md`: Android/iOS 모델·OS·browser·mode·commit, 12-screen 결과, recording handoff aggregate, a11y spot check, defect/follow-up 및 cleanup
- 기존 비회귀 명령은 FE에서 `npm run test`, `npm run lint`, `npm run build`, `npm run test:viewport`, `npm run test:a11y`; 명령이 구현분에 존재하지 않으면 미실행 이유와 대체 범위를 적는다.
- 실제 실기기 동작은 수동 결과와 sanitized screenshots로 남긴다. Browser automation/emulation report는 보조 근거이지 실기기 Evidence 대체물이 아니다.

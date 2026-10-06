# Android/iOS 전체 화면 회귀 검증

## 작업 식별 정보

- 작업: TASK-020.04
- 상위 작업: TASK-020 Mobile UI / Accessibility Polish
- 단계: Phase 9 — Release
- PRD 기준: PRD-MA-001 v1.8.1, 2026-10-06
- 관련 기준: SCR-001~012, NFR-001~003, NFR-007, PRD §21
- 선행 작업: TASK-020.01 Issue #73, TASK-020.02 Issue #74, TASK-020.03 Issue #75, TASK-018.04 Issue #68
- GitHub Issue: [#76](https://github.com/donghyunlee-dev/meeting-automation/issues/76)

## 결과

물리 Android Chrome 및 iPhone Safari에서 PRD 주요 화면과 핵심 사용자 여정을 회귀 검증한다. 브라우저 지원 범위는 TASK-018.04가 실제로 확인한 기기/OS/browser 조합에 한정하고, 이 task에서는 responsive rendering, 비동기 상태, touch/keyboard/accessibility와 짧은 recording-to-minutes handoff가 통합되어 있는지 확인한다.

## 범위

- Android Chrome 및 iOS Safari의 실기기에서 SCR-001~012, relevant modal/sheet/drawer의 주요 상태와 navigation 확인
- 360px 이상 portrait viewport 기준, 020.01 viewport/overflow 이슈 및 020.02 state contract 재확인
- 020.03에서 정한 touch target, keyboard, visible focus, accessible name/status 중 각 기기에서 실제 조작에 필요한 부분을 OS browser에서 spot check
- 새 회의 생성에서 녹음 permission → 짧은 테스트 recording → end confirm 취소/재개 및 종료 → Audio upload/Processing → 성공 Review 또는 synthetic failure recovery → Review/Share/Complete/History 연결을 non-production mock으로 smoke 확인
- 브라우저 권한/새로고침/뒤로가기 등 회의 여정에 영향 큰 경우의 안내 및 안전한 복귀 동작 확인
- 기기 모델, OS build, browser version/mode, viewport, app commit, test environment와 결과를 `docs/evidence/TASK-020.04.md`에 기록

## 브라우저 및 회귀 경계

- PRD §21 우선 브라우저인 Android Chrome과 iOS Safari만 필수다. 실기기 모델/OS/browser version은 테스트 당일 정확히 기록한다.
- 지원됨 판정은 TASK-018.04 support matrix에 직접 `검증됨`으로 나타난 조합에 한한다. 신규 또는 미검증 조합은 관찰 결과를 남기되 지원 보장으로 승격하지 않는다.
- 30/60분 녹음, 화면 잠금·통화·background, interruption/network outage, MIME/Chunk/IndexedDB 복구 심층 검증은 TASK-018.01~.04 소유라 재수행하지 않는다. 여기서는 foreground의 짧은 synthetic recording으로 UI와 end-to-end handoff만 확인한다.
- Android Chrome은 Android device Chrome tab으로, iOS Safari는 iPhone Safari tab으로 실행한다. installed PWA, in-app browser, iOS third-party browser는 다른 실행 환경이며 이번 필수 범위에 섞지 않는다.
- 모바일 WebKit/Chromium desktop emulation은 실기기 결과를 대신하지 않는다.

## QA 환경 및 개인정보

- HTTPS non-production build, dedicated test account/session, synthetic participant/meeting, mock AI/document/email/notification provider와 격리된 disposable storage prefix를 사용한다.
- 실제 attendee에게 Email/Slack 발송을 막고 outbound mock spy/log에서 발송 0건을 확인한다. 변환 실패 테스트는 화면/실패 회의록만 확인하며 실패 문서를 공유하지 않는다.
- Audio는 합성 tone 또는 동의된 중립 QA 문구만 사용한다. Evidence에는 Audio, waveform, Transcript, 실제 이름/email, Secret/auth header, provider body, signed/public storage URL을 쓰지 않는다.
- 성공 처리 후 Audio가 제거되었는지, 실패 처리 test 뒤 격리 QA object가 cleanup 되었는지 cleanup 결과만 기록한다.

## 제외

- API/Backend/FE 기능 재설계 및 새 device/browser support 선언
- 실제 실내 회의 품질, 30/60분 녹음 성능, interruption, network recovery (`TASK-018`)
- 새로운 viewport harness, state/axe/keyboard 자동 suite 개발 (`TASK-020.01~.03`)
- production credentials/provider/attendee를 이용하는 QA

## 완료 기준

- Android Chrome/iOS Safari 실기기 각각에 SCR-001~012 결과와 modal/drawer coverage가 있다. unavailable route는 결함/선행 blocker로 연결하고 통과로 계산하지 않는다.
- 두 기기에서 fresh-meeting부터 short recording 종료와 API-009 handoff까지 session/chunk/request aggregate가 일치한다. 정상 완료 및 synthetic processing failure 화면의 경계가 확인된다.
- 주요 touch interaction이 viewport 안에서 가능한 크기/위치에 있고 keyboard/focus/status 및 OS screen reader spot check가 각 지원 기기에서 기록된다.
- 권한 차단/거절, network/API 안내, loading/error/retry, browser back/refresh 중 재현된 영향이 evidence와 defect Issue로 추적된다.
- TASK-018.04 verified range와 이번 실기기 결과가 구분되며, 직접 실행하지 않은 버전은 미검증 상태로 남는다.
- 개인정보 없는 `docs/evidence/TASK-020.04.md`가 기기별 route/flow matrix, 결과, 결함, cleanup 증거를 가진다.

## 관련 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)
- [화면 viewport 검증](../TASK-020.01/spec.md)
- [화면 비동기 상태 검증](../TASK-020.02/spec.md)
- [접근성 및 입력 검증](../TASK-020.03/spec.md)
- [호환성 지원 범위](../../phase-08-field-validation/TASK-018.04/spec.md)
- [Audio 재시도/정리 규칙](../../phase-07-security/TASK-017.03/spec.md)

# 녹음 제어 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `FR-003`, `FR-004`, `SCR-003`, `API-006`, `TASK-005.02`
- 선행 TASK-005.01 Issue [#19](https://github.com/donghyunlee-dev/meeting-automation/issues/19): Recording view state와 callbacks
- 본 TASK Issue [#20](https://github.com/donghyunlee-dev/meeting-automation/issues/20)
- 후속 TASK-005.03: `{blob,mimeType,recordedAtMs}`를 Session별 저장·순번 부여·복구
- 후속 TASK-005.06/005.08: 업로드 및 Processing API orchestration

## 변경 경계

- Frontend recording controller/hook: MediaRecorder lifecycle, permission, MIME 결정, elapsed clock, 자원 정리
- TASK-005.01 Recording view: 기존 callback을 controller 명령과 연결하고 `elapsedMs`/state를 제공
- Frontend 테스트: browser API/clock을 주입 가능한 경계로 분리해 상태와 cleanup을 검증
- API client/Backend 변경은 없다. uploadPolicy는 API-006 response의 기존 `acceptedMimeTypes`, `chunkDurationSeconds`만 읽는다.

실제 scaffold의 경로와 테스트 도구를 먼저 확인한 뒤 기존 frontend 구조에 배치한다. `MediaRecorder` 전역은 도메인 로직에서 직접 참조하지 않고 좁은 browser adapter로 감싸면 permission, recorder events, monotonic clock을 독립적으로 대체할 수 있다.

## 구현 순서

1. TASK-005.01의 callback과 API-006 `uploadPolicy` 소비 지점을 확인한다. 결과: 상태/명령 및 MIME 입력 위치가 중복 없이 식별된다.
2. browser adapter interface와 controller state/event contract를 정의한다. 결과: 미지원 API도 명시적 결과로 표현된다.
3. 상태 전이·permission·MIME 선택·clock·Blob callback·cleanup 실패 테스트를 먼저 작성한다. 결과: 구현 전 실패하는 회귀 시나리오가 고정된다.
4. MIME 사전 검사, 사용자 action에서의 mic 획득, MediaRecorder lifecycle 및 15초 data callback을 구현한다. 결과: 명시적 event만 UI에 반영된다.
5. TASK-005.01 UI callback에 controller를 연결하고 사용자 오류 안내 및 stop 완료 handoff를 추가한다. 결과: 화면과 recorder가 같은 상태를 표시한다.
6. 전체 자동화 테스트, lint, build를 실행하고 변경 파일/경계를 검토한다. 결과: 아래 검증 명령과 acceptance case 근거가 남는다.

## 검증 명령

저장소 Frontend root에서 `npm run test`, `npm run lint`, `npm run build`를 실행한다. 실제 device microphone와 장시간 지속성은 Phase 8 수동 QA로 분리한다.

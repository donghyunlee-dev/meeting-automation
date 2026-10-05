# 녹음 제어 및 MediaRecorder lifecycle

## 목표

TASK-005.01 Recording 화면의 action callback을 브라우저 마이크와 `MediaRecorder` lifecycle에 연결한다. 마이크 권한 요청, 지원 가능한 MIME 선택, 시작·일시정지·재개·종료, 단조 시계 기반 경과 시간 및 안전한 자원 정리를 제공한다. PRD v1.7.0 (2026-10-05), `FR-003`, `FR-004`, `SCR-003`, `API-006`, `TASK-005.02`를 구체화한다. Issue [#20](https://github.com/donghyunlee-dev/meeting-automation/issues/20).

## 범위

- 지원 브라우저에서 사용자 동작 시점에 `getUserMedia({audio:true})` 요청
- API-006 `uploadPolicy.acceptedMimeTypes`에서 지원되는 첫 MIME 선택
- `MediaRecorder` 생성과 start/pause/resume/stop event 연동
- PRD chunk duration(15초)에 맞는 `dataavailable` Blob 콜백 전달
- 일시정지를 제외한 누적 `elapsedMs` 제공
- 에러 및 종료 시 MediaStream track 정리, 중복 stop 방지
- TASK-005.01의 Ready/Recording/Paused 화면 상태 및 종료 확인 callback 연동

## 비범위

Chunk 영속화·순번 부여·재시작 복구(`TASK-005.03`), 업로드·재시도(`TASK-005.06`), Session 상태 API, Processing 시작(`TASK-005.08`), 실제 스마트폰/회의실 장시간 검증(Phase 8)은 포함하지 않는다. 이 작업은 Backend Session status enum에 `PAUSED`를 추가하지 않는다.

## 동작 계약

- 페이지 진입 시 권한을 묻지 않는다. 사용자가 `onStart`를 선택했을 때만 준비한다.
- `MediaRecorder` 미지원 또는 허용 MIME 중 지원 항목이 없으면 마이크 권한을 요청하지 않고 Ready에 오류 안내를 제공한다.
- 허용 MIME은 `uploadPolicy.acceptedMimeTypes` 순서를 우선순위로 하여 `MediaRecorder.isTypeSupported`가 참인 첫 값 하나를 고른다. 허용 목록의 정확한 MIME 문자열을 recorder 생성자에 전달하고 후속 Chunk callback에도 전달한다.
- 선택 MIME이 정해진 뒤 마이크를 요청한다. 권한 거부/장치 오류는 사용자용 안내로 매핑하고 Ready에 머문다. 원시 브라우저 오류나 오디오 데이터는 로그에 남기지 않는다.
- recorder `start` event에서 UI를 Recording으로 전이한다. PRD/API-006의 `chunkDurationSeconds=15`를 timeslice로 설정하며 각 `dataavailable`의 비어 있지 않은 Blob을 `{chunkId,blob,mimeType,recordedAtMs}` callback으로 TASK-005.03 경계에 전달한다. `chunkId`는 이벤트마다 한 번 생성해 저장 재시도에서도 동일 값으로 유지한다.
- `pause`/`resume` event만 UI 상태를 각각 Paused/Recording으로 바꾼다. 지원되지 않는 pause/resume 또는 잘못된 현재 상태 요청은 안전하게 무시하고 오류 안내를 표시한다.
- 시작은 elapsed를 0으로 초기화한다. `performance.now()` 누적값으로 진행 구간만 더하고 Paused 동안 증가하지 않게 한다. 표시 갱신 주기는 1초이며 각 tick을 screen reader에 반복 공지하지 않는다.
- Recording/Paused 상태에서 종료 확인이 승인되면 recorder를 한 번만 stop한다. 최종 `dataavailable`을 먼저 전달한 후 `stop`에서 timer를 확정하고 stream track을 종료하며 stop 완료 callback을 보낸다. API 처리/화면 완료 동작은 호출부 책임이다.
- 시작 실패, recorder error, 종료, React unmount에서 타이머와 모든 획득 track을 해제한다. stop은 중복 호출에 안전해야 한다.

## 수용 기준

- 시작 버튼 동작 전 마이크 권한 요청이 없고, 시작 동작에서만 요청된다.
- 지원하지 않는 브라우저/MIME는 마이크를 요청하지 않은 채 Ready 오류 상태를 제공한다.
- 권한 거부 및 장치 오류 후 Ready로 복귀하고 오류 원문/오디오를 로그에 남기지 않는다.
- `start`, `pause`, `resume`, `stop` event가 TASK-005.01의 Ready/Recording/Paused presentation 및 버튼 action과 일치한다.
- 경과 시간은 pause 중 멈추고 재개 시 이어지며 종료 후 고정된다.
- 15초 timeslice Blob과 MIME을 각 콜백에 전달하고 종료 시 마지막 Blob을 stop 완료보다 먼저 전달한다.
- 실패·종료·unmount 시 MediaStream track과 interval이 정리되며 종료는 한 번만 실행된다.

## 결정

MIME 순서는 API-006 서버 정책을 따른다. MediaRecorder가 제공하는 실제 브라우저 동작을 UI fixture/mock으로 검증하고 실제 iOS/Android 녹음 품질과 장시간 지속성은 Phase 8에서 검증한다.

# 검증 계획

## 선행 조건

- TASK-005.08 Issue #26: recorder stop, final Chunk 저장, upload flush, API-009, Processing route 계약
- TASK-017.03 Issue #62: processing route/error/download/failure completion UI 계약
- PRD v1.8.1, NFR-001~003/007, Section 21
- Android 실기기, Chrome Stable, HTTPS non-production 배포, QA test Session/roster, 폐기 가능한 storage prefix, 수신자 없는 delivery 설정

## 경계 및 소유권

| 대상 | 소유자 | 확인 결과 |
|---|---|---|
| 기기·브라우저·OS 입력 | QA | OS/Chrome 버전, 화면 잠금/앱 전환/전화 영향 |
| Recorder와 MediaStream | FE + QA | start/pause/resume/stop/error, 실제 MIME, track mute/unmute/ended |
| Browser Chunk 저장 | FE + QA | IndexedDB 순번/byte 현황, final Chunk commit 및 ACK 전 보존 |
| Upload/Backend | FE/BE + QA | API-007/008 수신 sequence와 byte 현황, API-009 접수, 순번 중복/누락 여부 |
| Failure recovery route | FE + QA | upload/processing 오류를 모의하면 TASK-017.03의 안전한 Recovery UI와 retry action 허용 여부 확인 |
| Evidence/cleanup | QA | 비식별 metadata만 기록하고 REVIEW cleanup을 끝내거나 격리 QA object 삭제 |

## 실행 순서

1. 기기·앱·브라우저 조합과 baseline Session 구성을 기록한다. 앱 build와 backend/API version, MIME 후보, upload policy byte cap, 테스트 참석자와 Provider를 이번 실행 동안 고정한다.
2. `MediaRecorder.isTypeSupported()` 후보를 관찰하고 장시간 실행 전에 짧은 시험으로 실제 `recorder.mimeType`을 확인한다. 미지원 MIME은 기록하며 다른 MIME으로 몰래 대체하지 않는다.
3. 화면이 켜진 상태에서 중단 없이 30분 및 60분 녹음한다. 이 실행은 녹음 지속성과 일반 업로드량의 기준이다.
4. 각 30분 시나리오를 분리 수행한다: 화면 잠금 5분, 다른 앱으로 전환 5분, 통제된 수신 전화에 응답해 60초 유지, 모바일 데이터 자동 대체를 막은 상태로 Wi-Fi 외부 연결을 60초 차단한다. 조건을 복원하고 정확한 시각을 기록한다.
5. 매 실행에서 이벤트 timeline, IndexedDB pending 현황, expected/received sequence 수, byte 합계, MIME, 오류, 사용자 안내 결과를 기록한다. Audio bytes와 통화 내용은 캡처하거나 내보내지 않는다.
6. 실제 SCR-004 종료 흐름을 사용한다. stop과 final chunk persist 완료를 기다리고 upload flush/reconcile, API-008 조회, API-009 및 Processing route Session ID를 확인한다. 실제 STT/Minutes 비용 없이 REVIEW와 Audio cleanup을 확인하도록 non-production mock processing adapter를 사용한다.
7. 실패 또는 판단이 모호한 행은 나머지 조건을 고정해 한 번 재실행한다. 첫 결과와 재실행 결과를 함께 보존하고 처음 실패를 덮어쓰지 않는다.
8. `docs/evidence/TASK-018.01.md`에 기기 정보, 실행 명령(있으면), 시각, 비식별 이벤트 요약, 결과 및 후속 Issue를 기록한다. Evidence와 로그에 금지 데이터가 없는지 확인한다.

## 결과 해석 및 후속 작업

- 지연된 `dataavailable`만으로 녹음 손실을 판정하지 않는다. 최종 duration, 콘텐츠를 담지 않는 계수값과 이후 Chunk 무결성을 비교한다.
- 마지막 Blob이 설정된 `maxChunkBytes`보다 크거나, 결과가 잘리거나, track이 끝나거나, 예상하지 않은 stop이 발생하거나, 네트워크 복구가 실패하면 해당 구현 경계의 실패로 기록한다.
- 상세 네트워크 retry, 재시작 및 누락/중복 Chunk 대조는 TASK-018.03 범위다. 이 task는 복구 테스트를 중복하지 않고 사용자 관찰 결과와 sequence 합계만 기록한다.
- 재현성이 OS 전원/배터리, 메모리 압력, Wi-Fi, 화면 상태 또는 통화 경로에 좌우되면 해당 조건을 기록하고 브라우저 일반 동작으로 확대 해석하지 않는다.
- PRD Section 21 Evidence 규칙을 따른다. tested build 식별에 기존 저장소 검사 명령이 꼭 필요하지 않으면 실기기 QA에서 build/test script 실행은 요구하지 않는다.

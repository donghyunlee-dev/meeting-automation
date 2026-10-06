# Android Chrome 녹음 검증

## 목표

지원 대상인 Android 실기기와 Chrome에서 30분 및 60분 녹음의 지속성과, 화면 잠금·앱 전환·전화·네트워크 단절이 Recorder/Chunk 저장/업로드에 미치는 영향을 재현 가능한 수동 검증으로 기록한다. 이 작업은 성능·호환성 관찰 결과를 만들며 기기 동작을 보장하거나 자동으로 우회하지 않는다.

PRD v1.8.1 (2026-10-06), `TASK-018.01`, `NFR-001~003`, `NFR-007`, PRD Section 21, `TASK-005.08`, `TASK-017.03`을 구체화한다. Issue [#65](https://github.com/donghyunlee-dev/meeting-automation/issues/65), 선행 Issue: TASK-005.08 #26, TASK-017.03 #62.

## 범위

- 30분 및 60분 연속 Android Chrome 녹음 baseline
- 같은 기기/브라우저에서 분리 수행하는 화면 잠금, 앱 전환, 통제된 전화 수신, 연결 단절 시나리오
- MediaRecorder/MIME, MediaStream track, visibility, IndexedDB chunk, API-007/008 upload queue 관찰
- 종료 시 final `dataavailable`/Chunk persist/업로드 flush/API-009 처리 시작 순서와 Session ID 일치 확인
- 기기·Android·Chrome·MIME·network 환경 및 재현 절차를 `docs/evidence/TASK-018.01.md`에 기록

## 비범위

- iOS Safari 검증 (`TASK-018.02`)
- 네트워크 단절 뒤 sequence별 복구/중복·누락 증명 (`TASK-018.03` 상세 소유)
- MIME/Chunk 구현 수정과 지원 브라우저 결정 (`TASK-018.04`)
- 회의실 반향·3/5명 STT/diarization 품질 (`TASK-019`)
- 새로운 native app, background recording 권한, 아키텍처 변경
- 실제 사용자/회의 녹음, 개인 통화 내용, 오디오 파일을 Evidence나 외부 도구에 보관

## 환경과 안전 조건

- 물리 Android 단말 1종 이상, 설치된 Chrome Stable 버전, HTTPS non-production app, 전용 QA Session/Participant, 테스트 object prefix 및 test/mock processing provider를 사용한다.
- 실제 사람의 대화 대신 동의된 중립 테스트 문구 또는 시간 표시가 포함된 합성 tone/voice fixture를 재생하고 필요하면 로컬에서만 재생 결과를 확인한다. 전화 시나리오는 별도 QA 기기/테스트 회선으로 통제하고 통화 내용을 녹음하지 않는다.
- 회의 metadata와 참석자 목록을 녹음 전에 선택한다. 실제 참석자에게 메일/Slack 알림을 보내지 않는다.
- session, chunk sequence, MIME, byte length, MediaRecorder event timestamp/state, upload status만 기록한다. Audio, Transcript, 전화번호, Secret, raw response는 Evidence에서 제외한다.
- Session을 `REVIEW`까지 완료해 정상 Audio cleanup을 확인한다. 처리를 완료할 수 없는 경우 non-production storage의 QA object만 정리하고 삭제 결과를 기록한다.

## 시험 판정 규칙

- `dataavailable` interval은 성공 기준으로 고정하지 않는다. Android Chrome 화면 잠금에서는 해당 이벤트가 지연될 수 있다. 화면을 잠갔다 깬 뒤 최종 Audio duration, recorder/track 상태, IndexedDB byte/sequence, 업로드 결과를 종합해 녹음 손실 여부를 판단한다.
- `stop` 시 최종 Blob이 전달되므로 `stop` 완료만 관찰하고 final Chunk persist/ACK 전에 성공 처리하지 않는다.
- MIME별 지원 판정은 실행 당시 `MediaRecorder.isTypeSupported()` 결과와 실제 `recorder.mimeType`/업로드 성공을 함께 기록한다. `MediaRecorder` 존재만으로 codec이 검증된 것으로 보지 않는다.
- 각 방해 시나리오는 한 번에 하나만 적용한다. 재현되지 않은 동작을 브라우저 전체의 보장으로 일반화하지 않는다.
- Chunk byte 길이가 Session upload policy 한도를 넘거나 순번이 빠짐/중복이면 해당 실행은 pass가 아니다. Network recovery 세부 검증은 TASK-018.03에 넘긴다.

## 수용 기준

- Android 기기 모델/OS/Chrome 버전, 앱 commit, 네트워크, MIME, 시작/종료 및 방해 시각이 각 실행마다 기록된다.
- 화면이 켜진 baseline 30분·60분 녹음이 의도치 않은 stop/error 없이 완료되고, 실제 chunk가 저장·업로드된다.
- Screen lock, app switch, incoming call, network outage 각각의 영향이 분리 실행과 재현 단계로 기록된다. 중단이 있으면 loss 범위, 사용자 인지 시점, 마지막 durable chunk, 재개 동작이 증거로 남는다.
- 완료 Session은 예상 chunk 수/서버 received sequence/API-008 결과를 대조한 뒤 API-009 응답 및 Processing route Session ID를 확인한다.
- Evidence에 Audio, Transcript, 통화 내용, Secret, public storage URL 또는 개인식별정보가 없다.
- 발견 결함은 재현 가능한 비민감 결과로 기록하고 TASK-018.04 또는 별도 bug Issue로 후속한다.

## 공식 플랫폼 참고

- [MediaRecorder `dataavailable` event — MDN](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder/dataavailable_event): timeslice 이벤트 간격은 정확한 고정 주기가 아니며, Chrome on Android의 screen lock에서 이벤트가 지연될 수 있다고 설명한다.
- [Document `visibilitychange` event — MDN](https://developer.mozilla.org/en-US/docs/Web/API/Document/visibilitychange_event): 모바일 앱 전환/화면 비가시 상태에서 visibility change를 관찰할 수 있음을 설명한다.
- [MediaRecorder `start()` — MDN](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder/start): stop 전에 최종 dataavailable 후 stop 이벤트가 발생하는 계약을 설명한다.

## 관련 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)
- [PRD Recording 종료 계약](../../phase-03-recording/TASK-005.08/spec.md)
- [PRD Processing 복구 화면](../../phase-07-security/TASK-017.03/spec.md)

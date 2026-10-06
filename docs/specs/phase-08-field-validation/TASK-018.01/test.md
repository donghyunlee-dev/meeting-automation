# 검증 계획

## 자동화 테스트

이 task의 중심 검증은 물리 Android 기기와 Chrome의 manual QA다. 기존 앱에 존재하지 않는 하드웨어 자동화나 command를 새로 추측하지 않는다. Shared MediaRecorder/uploader unit tests가 변경됐다면 저장소에 정의된 기존 명령으로 별도 회귀 실행 결과를 남긴다.

## 수동 실기기 행렬

각 실행의 고정 조건: Android 단말 1대, 모델/Android build, Chrome Stable version, app commit, HTTPS test deployment, Wi-Fi/AP, mic route, battery saver/충전 상태, actual recorder MIME, API upload policy (`maxChunkBytes`, accepted MIME), test Session ID, test provider mode.

| ID | 입력/절차 | 기대 결과 | Evidence |
|---|---|---|---|
| AND-BASE-30 | 화면 켠 상태로 연속 30분 녹음 | 예상치 못한 stop/error 없음, stop 후 final Blob persist, chunks 순서대로 upload, API-009 response 및 route Session 일치 | 시작/종료시각, event summary, sequence/byte counts |
| AND-BASE-60 | 같은 조건에서 연속 60분 녹음 | 30분 baseline과 같은 integrity/전이 조건; chunk가 `maxChunkBytes` 이하 | device matrix, duration, API-008 totals |
| AND-LOCK-30 | 30분 녹음 중 10분에 화면 잠금 5분 후 unlock | record/track 종료, 지연 `dataavailable`, oversized final chunk, audio gap 여부를 구분; 저장/upload 가능한 모든 valid chunk 유지 | lock/unlock timestamps, event+sequence totals |
| AND-APP-30 | 30분 녹음 중 10분에 다른 앱으로 5분 전환 후 복귀 | foreground 복귀 후 Recorder/track/IndexedDB/upload 상태가 관찰 가능하며 사용자가 녹음 지속 여부를 확인 가능 | visibility timestamps, recorder/track summary |
| AND-CALL-30 | 30분 녹음 중 10분에 통제된 수신 전화에 응답하고 60초 뒤 종료 | 통화 전후 mic track/Recorder/data/upload 상태를 기록하고 예상하지 못한 capture 중단 또는 안전한 오류 안내를 판정 | 전화 수신/응답/종료 시각, 이벤트 요약, 통화 내용 미기록 |
| AND-NET-30 | 30분 녹음 중 10분부터 60초 uplink 차단, Wi-Fi/mobile data 자동 대체 없이 연결 복원 | local IndexedDB pending 유지; 연결 복구 뒤 같은 Session의 저장/업로드 상태 확인; processing handoff에 미전송 Chunk가 남지 않음 | network toggle/AP 시각, local/server sequence count |
| AND-STOP-FINAL | 각 실행에서 종료 확인 후 stop/final dataavailable/IndexedDB append/upload/API-009 순서 확인 | final Blob이 저장되고 누락 upload가 0인 뒤에만 API-009를 호출; route ID가 같은 Session | 이벤트 순서, request/route ID, API-008 |
| AND-CLEANUP | non-production mock provider로 REVIEW 진입 또는 QA object 수동 정리 | 정상 pipeline cleanup 뒤 object 0건; cleanup 실패가 있으면 sanitized follow-up 남김 | object inventory count only |

판정은 타임슬라이스/`dataavailable` 횟수만으로 하지 않는다. 전체 녹음 지속성, event state, duration, chunk byte length/순번, API-008 remote receipt, API-009 handoff를 함께 본다. 별도 30분 interruption run은 네 가지 조건 각각을 단독 적용한다.

## 성공 및 실패 판정

- 성공: baseline duration을 완료하고 종료 때 final chunk가 저장되며 API-008 sequence inventory와 expected chunk 수가 일치하고, 업로드 완료 뒤 API-009가 같은 Session을 처리 route로 인계한다.
- 실패: Recorder/track이 예고 없이 종료, 최종 output이 잘리거나 duration이 줄어듦, IndexedDB persist 오류/용량 초과, chunk > `maxChunkBytes`, sequence 누락/중복, upload pending인데 API-009 호출, Session ID mismatch.
- 조건부: `dataavailable`이 지연되었더라도 완전한 output과 크기 제한 내 chunks가 보존되면 단독으로 fail하지 않는다. 지연이 크기초과/누락 또는 사용자에게 알리지 않은 녹음 공백을 만들면 fail한다.
- 네트워크 복구에서 sequence 중복·누락 상세 원인 분석은 TASK-018.03에 연결하되, 이 task의 run 결과는 pass로 바꾸지 않는다.

## Evidence와 정리

- 위치: `docs/evidence/TASK-018.01.md`
- 허용: device model, Android/Chrome/app version, test Session ID, test MIME, start/end/disturbance timestamps, Recorder/track event type, sequence count, total byte length, status code, pass/fail, follow-up issue ID.
- 금지: Audio file/Blob, transcript/recognized words, 실제 통화 내용/전화번호, 개인 이메일, Secret, raw Provider response, storage credential/object URL, unredacted request/log.
- 테스트 Audio는 시간 marker를 포함한 합성·동의된 fixture다. 필요 시 기기에서만 로컬 재생해 gap을 판정하며 파일/파형/음성 텍스트를 Evidence로 복사하지 않는다. REVIEW 완료 후 backend cleanup을 확인한다. 중도 실패이면 isolated QA object를 삭제하고 재시도 여부를 기록한다.
- 각 표 행을 최소 한 번 실행한다. 실패 행은 환경을 고정해 한 번 반복하되 첫 결과와 반복 결과를 함께 보존한다.

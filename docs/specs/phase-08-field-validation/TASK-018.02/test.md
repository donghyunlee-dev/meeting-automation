# 검증 계획

## 자동화 테스트

이 작업의 deliverable은 실제 iPhone/Safari 현장 결과이므로 새 자동화 명령이나 하드웨어 테스트 harness를 가정하지 않는다. 기존 recorder/uploader 코드를 변경하지 않으며 애플리케이션 테스트를 이번 설계 등록 단계에서 실행하지 않는다. 제품 구현 PR에서 기존 코드가 바뀌면 해당 저장소 명령에 따른 회귀를 별도로 수행한다.

## 수동 기기 행렬

각 행의 공통 조건: 물리 iPhone 모델, iOS/Safari 버전, 일반 Safari 탭 여부, 앱 commit, HTTPS API/backend, Wi-Fi/셀룰러 경로, power/battery mode, actual recorder MIME, API upload policy, mock provider, Session ID. 각 행은 별도 Session과 한 번에 하나의 조건으로 실행한다.

| ID | 입력/절차 | 기대 결과 | Evidence |
|---|---|---|---|
| IOS-PRECHECK | HTTPS 일반 Safari 탭에서 권한 및 `MediaRecorder`/MIME/IndexedDB 짧은 녹음 확인 | 권한 허용/거부, MIME 선언, recorder 실제 생성·종료, storage 결과가 분리 기록됨 | 기기/OS/Safari/build/MIME/상태코드 |
| IOS-BASE-30 | 화면 켠 상태 연속 30분 녹음 후 종료 | 예기치 않은 stop/error와 final output을 판단하고 final Chunk 저장/업로드/API-009 결과 기록 | run 시각, duration, 이벤트/sequence/bytes |
| IOS-BASE-60 | 같은 조합 화면 켠 상태 연속 60분 녹음 | 60분 실제 결과와 30분 baseline 차이를 기록, 누락·조기종료를 성공 처리하지 않음 | 기기 조합, 종료 상태, API-008 합계 |
| IOS-LOCK-30 | 30분 run 10분 시점 화면 잠금 5분 뒤 해제 | 잠금 구간의 실행/녹음 지속성 및 복귀 가능 여부를 관찰; 확인 불가 구간은 성공으로 추정하지 않음 | 잠금/해제시각, Recorder/track 요약 |
| IOS-APP-30 | 30분 run 중 5분 다른 앱으로 이동 후 Safari 복귀 | Safari 복귀 성공, visibility/track/recorder 상태 및 저장 가능한 Chunk를 기록 | 전환/복귀시각, sequence/bytes |
| IOS-CALL-30 | 30분 run 중 통제된 QA 전화에 응답해 60초 유지 | 전후 capture/track/error 및 사용자 안내 기록; 통화 내용/번호 없음 | 응답/종료시각, 이벤트 summary |
| IOS-NET-30 | 30분 run 중 60초 uplink 차단 후 원 경로 복구 | local pending이 보존되고 같은 Session의 업로드 가능 상태를 확인; sequence 세부 recovery는 별도 task | 연결변경시각, pending/received count |
| IOS-STOP-FINAL | 각 run 종료 후 저장 및 서버 대조 | `stop` 완료 이전에 API-009 handoff하지 않고 final Chunk persist/upload 완료 후 API-008·API-009 및 route Session ID 확인 | 이벤트 순서, Session ID 동일성, HTTP 결과 |
| IOS-CLEANUP | mock provider로 REVIEW까지 처리 또는 격리 실패 object 삭제 | 성공 Audio 정리 또는 실패 QA object 삭제 완료 | object 수량/status만 기록 |
| IOS-COMPARE | 같은 OS/app/backend/policy 기준 TASK-018.01 Android 결과와 비교 | 관측값과 추정 원인을 분리하고 차이는 재현 가능한 조건으로 표시 | 비교표/후속 Issue |

## 수용 기준 연결

- 환경·실행 정보의 완전성: `IOS-PRECHECK`, `IOS-BASE-30`, `IOS-BASE-60`, 각 interruption 행
- baseline과 방해 조건 결과: `IOS-BASE-30`, `IOS-BASE-60`, `IOS-LOCK-30`, `IOS-APP-30`, `IOS-CALL-30`, `IOS-NET-30`
- 종료/서버 처리 일치: `IOS-STOP-FINAL`
- 민감 데이터 배제 및 정리: 전 행 Evidence 검토, `IOS-CLEANUP`
- Android 비교 및 후속 범위 분리: `IOS-COMPARE`

## 판정과 정리

- PASS는 설정된 시나리오에서 녹음/종료/저장/업로드 계약을 직접 확인한 경우에만 부여한다. `dataavailable` 횟수나 성공한 MIME 검사 하나만으로 pass하지 않는다.
- FAIL에는 예상치 못한 stop, 최종 output 손실/불완전, persist 오류, chunk 크기 초과, API-008 sequence 차이, 미전송 상태 API-009 호출, Session route 불일치, 미복귀/사용자 안내 누락을 포함한다.
- 시스템 종료 등으로 화면이 복귀되지 않으면 측정 불가 시간과 마지막으로 확인된 상태를 기록한다. 그 시간대 녹음이 성공했다고 가정하지 않는다.
- 네트워크 복구 뒤 상세 순번 reconcile은 TASK-018.03에 후속하되 이번 실행의 관측 실패는 pass로 바꾸지 않는다.
- Evidence는 `docs/evidence/TASK-018.02.md`에 기록한다. 오디오/Blob, Transcript/음성문구, 통화 콘텐츠/전화번호, Secret, 개인 이메일, raw response, credential, object URL은 저장하지 않는다. REVIEW cleanup 또는 격리 object 제거 후 수량만 남긴다.

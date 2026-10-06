# 실행 작업

실제 iPhone Safari 수동 검증과 비민감 Evidence 작성을 수행한다. 코드 변경은 이 task에 포함하지 않는다.

## 단계

- 비운영 배포와 테스트 Session을 고정한다. 완료 결과: 수신자 없는 Session, mock processing, 격리 object prefix와 cleanup 방법을 기록한다.
- iPhone Safari 조합을 식별한다. 완료 결과: 기기 모델, iOS/Safari 버전, 앱 commit, HTTPS API 환경, network/power, 마이크 권한을 기록한다.
- 짧은 recorder/MIME 사전 점검을 한다. 완료 결과: `isTypeSupported()`, recorder 생성 결과, 실제 `mimeType`, IndexedDB 사용 가능 여부와 quota, upload byte 정책이 기록된다.
- 화면이 켜진 30분 baseline을 수행한다. 완료 결과: 시작/종료, recorder/track 요약, Chunk sequence/byte 합계, API-008 결과가 기록된다.
- 동일 조합의 화면 켠 60분 baseline을 수행한다. 완료 결과: 30분 run과 독립된 최종 결과 및 종료/업로드 상태가 남는다.
- 화면 잠금 5분 run을 별도 수행한다. 완료 결과: 잠금/복귀 시각, visibility/recorder/track 변화 및 최종 산출 영향이 남는다.
- 앱 전환 5분 run을 별도 수행한다. 완료 결과: 전환/복귀 시각과 Safari 재진입·상태·Chunk 결과가 남는다.
- 통제 QA 전화 60초 run을 별도 수행한다. 완료 결과: 전화 상태 시각과 capture/track 사용자 안내 결과가 기록되고 통화 콘텐츠는 수집되지 않는다.
- uplink 60초 단절 run을 별도 수행한다. 완료 결과: 실제 연결 변경, local pending 보존과 복구 뒤 같은 Session 업로드 상태가 기록된다.
- 각 정상 run을 종료 흐름으로 처리한다. 완료 결과: final Chunk persist 후 API-008 sequence/byte 대조, API-009, route Session ID가 일치한다. 미전송이 있으면 처리 handoff 성공으로 판정되지 않는다.
- mock 처리 REVIEW cleanup 또는 격리 실패 object 삭제를 수행한다. 완료 결과: 잔여 Audio object 0건 또는 실패 삭제 후속 절차가 증거로 남는다.
- 재현이 필요한 실패를 같은 조건에서 한 번 반복한다. 완료 결과: 최초/반복 결과가 나란히 보존되고 원인이 미확인인 부분이 분리 표기된다.
- TASK-018.02 Evidence 및 Android 비교를 작성한다. 완료 결과: 오디오·Transcript·전화내용·Secret·개인정보가 없으며 후속 Issue가 연결된다.

## 의존 관계

- 기기/앱/서버/Session 고정과 MIME 점검은 baseline과 모든 interruption run보다 먼저 완료한다.
- baseline 30/60은 화면 잠금·앱 전환·전화·네트워크 run과 각각 분리한다.
- 모든 run의 종료/업로드 판정은 TASK-005.08 계약을 따른다.
- REVIEW 및 Audio cleanup 확인은 run별 종료/실패 판정 다음에 수행한다.
- TASK-018.03/018.04는 이 task의 실제 결과에 후속한다. 추정값만으로 이슈를 pass 처리하지 않는다.

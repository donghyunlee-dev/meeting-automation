# 녹음 제어 검증 계획

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| REC-CTRL-01 | 화면 mount, 시작 action 전 | `getUserMedia` 호출 0회 | permission adapter spy |
| REC-CTRL-02 | MediaRecorder 미지원 | Ready 오류 안내, 권한 요청 없음 | controller/component test |
| REC-CTRL-03 | 허용 MIME 모두 `isTypeSupported=false` | Ready 오류 안내, 권한 요청 없음 | MIME adapter test |
| REC-CTRL-04 | 목록 `audio/webm`, `audio/mp4`; 첫 항목 지원 | `audio/webm` 선택 및 생성자에 전달 | MIME priority test |
| REC-CTRL-05 | 권한 거부 | 오류 안내, Ready 유지, 열린 stream 없음 | permission rejection test |
| REC-CTRL-06 | 시작 성공 후 recorder `start` event | Recording 표시 및 elapsed 시작 | event/controller test |
| REC-CTRL-07 | Recording에서 pause event | Paused 표시, clock 정지 | fake monotonic clock test |
| REC-CTRL-08 | Paused에서 resume event | Recording 표시, 이전 elapsed부터 누적 | fake clock/event test |
| REC-CTRL-09 | 15초 dataavailable Blob 및 저장 재시도 | 빈 Blob 제외, 안정적인 `chunkId`, selected MIME과 기록 시각을 callback에 전달 | recorder adapter test |
| REC-CTRL-10 | 종료 확인 후 recorder stop | 최종 Blob callback 뒤 stop 완료 callback, track 정지 및 elapsed 고정 | event-order/cleanup test |
| REC-CTRL-11 | recorder error 및 unmount | interval/track 모두 정리, 사용자용 오류만 표시 | cleanup test |
| REC-CTRL-12 | pause/resume/stop 중복 또는 유효하지 않은 상태 명령 | 잘못된 API 호출 없이 상태 일관성 유지 | transition table test |

프로젝트 Frontend root에서 `npm run test`, `npm run lint`, `npm run build`를 실행하고 결과를 기록한다.

## 수동 QA

- 지원 브라우저에서 Ready 진입 후 권한 요청이 없고, 버튼 누른 뒤 브라우저 마이크 prompt가 표시되는지 확인한다.
- 허용/거부 후 화면 안내, 시작·일시정지·재개·종료와 timer 동작을 확인한다.
- 15초 이상 녹음하고 stop 시 마지막 Blob까지 후속 callback으로 전달됨을 개발 도구에서 확인한다.
- 완료와 오류 뒤 브라우저가 microphone track을 놓는지 확인한다.

## Phase 8 릴리스 검증

Android Chrome 및 iOS Safari 실기기, 화면 잠금/앱 전환, 30~60분 지속 녹음, 회의실 품질은 Phase 8에서 검증한다. 이 Task 완료의 자동화 증거로 실기기 품질을 주장하지 않는다.

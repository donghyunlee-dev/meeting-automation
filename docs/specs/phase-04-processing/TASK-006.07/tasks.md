# Processing 화면 연결 작업 항목

1. Frontend route, API client, 공통 `{data}`/error 처리 및 layout 경계를 확인한다. 완료 증거: 구현 대상 파일과 Session ID 전달 경로를 PR에 기록한다.
2. API fixture 기반 stage mapping, REVIEW 이동, 실패 안전성, backoff, 동시 요청 방지, unmount 취소 테스트를 작성한다. 완료 증거: 구현 전 각 주요 동작 테스트가 실패한다.
3. API-010 response/status/stage view model과 조회 scheduler를 구현한다. 완료 증거: `AUDIO_ASSEMBLY`, `TRANSCRIPTION`, `DIARIZATION`, 그리고 100% 처리 후 Minutes 생성 표시가 fixture에 따라 달라진다.
4. SCR-005 stepper, 대기/실패/조회 오류/없는 Session 안내 및 progress accessibility를 구현한다. 완료 증거: 화면 문구·단계·접근성 속성이 계약과 일치한다.
5. polling 중 Bottom Navigation을 숨기고 reduced-motion 및 route unmount 시 timer/request 정리를 연결한다. 완료 증거: terminal/이탈 뒤 추가 GET이 발생하지 않는다.
6. API-009 성공에서 Session ID를 route로 전달하고 API-010이 같은 ID의 `REVIEW`일 때 Review로 한 번 이동한다. 완료 증거: ID 불일치나 부분 처리 응답은 이동시키지 않는다.
7. Frontend 자동화 테스트, lint/build, 통합 및 수동 접근성 QA를 수행한다. 완료 증거: 사용한 실제 package 명령과 결과, 검증 항목을 PR에 남긴다.

모든 구현 항목은 API-010 common snapshot과 Session 상태 계약에 의존한다. 처리 pipeline 자체의 재시작 API/자동 재시도는 구현하지 않는다.

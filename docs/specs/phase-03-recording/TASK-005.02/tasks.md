# 녹음 제어 작업 항목

1. TASK-005.01 callback/state, API-006 response type 및 Frontend scaffold를 확인한다. 완료 증거: 기존 view contract와 uploadPolicy 입력을 가리키는 경로가 계획에 기록된다.
2. Media adapter와 controller의 타입/API를 정의한다. 완료 증거: permission, recorder events, clock, track cleanup을 대체할 수 있는 단일 인터페이스가 있다.
3. 권한 거부, API/MIME 미지원, 상태 전이, 시간, 15초 Blob, stop ordering, cleanup 테스트를 먼저 작성해 실패를 확인한다.
4. 허용 목록 우선순위 MIME 선택과 user gesture 기반 `getUserMedia`/MediaRecorder 시작을 구현한다. 완료 증거: 허용 MIME 없이는 permission request가 없다.
5. start/pause/resume/stop event와 monotonic elapsed clock을 구현한다. 완료 증거: 이벤트 기반 view state, pause 동안 고정되는 누적 시간이 테스트된다.
6. timeslice Blob callback과 final Blob-before-stop callback 순서를 구현한다. 완료 증거: TASK-005.03이 MIME 및 Blob을 수신한다.
7. 실패·종료·unmount cleanup, 중복 stop 방지를 구현한다. 완료 증거: 모든 종료 경로에서 track/timer 해제와 단일 stop이 검증된다.
8. TASK-005.01 화면에 controller state/명령 및 오류 안내를 연결한다. 완료 증거: 화면 action과 실제 recorder event가 같은 상태를 보인다.
9. 자동화 테스트를 통과시키고 `npm run lint`, `npm run build`를 실행한다. 완료 증거: 명령 결과와 변경 범위가 리뷰 기록에 있다.

# 실행 작업

모든 절차는 PRD Section 21, TASK-005.08/#26 녹음 종료 계약, TASK-017.03/#62 복구 화면을 따른다. 녹음 콘텐츠와 통화 내용은 증거로 수집하지 않는다.

## 단계

1. 비운영 환경의 기기/app/backend/Chrome/MIME/upload policy/test provider 조합을 확정한다. 완료 증거: 모든 실행 전에 기기 정보와 안전한 테스트 Session을 기록한다.
2. 지원 MIME 후보와 실제 recorder MIME, HTTPS 마이크 권한, IndexedDB quota/사용 가능 용량을 짧은 사전 점검으로 확인한다. 완료 증거: 장시간 실행 설정과 제한이 기록된다.
3. 화면을 켠 baseline 30분 및 60분 녹음을 실행한다. 완료 증거: recorder event 순서, duration, sequence/bytes, API-008 결과가 저장된다.
4. 화면 잠금 30분 시나리오를 실행하고 10분 시점에 화면을 잠가 5분 뒤 해제한다. 완료 증거: 이벤트 지연/중단 여부와 해제 뒤 녹음·Chunk 결과가 기록된다.
5. 앱 전환 30분 시나리오를 실행하고 중간 5분 동안 별도 앱을 전면에 둔다. 완료 증거: Chrome 복귀 시 recorder/track 상태와 IndexedDB/upload 변화를 기록한다.
6. 통제된 QA 전화를 이용해 수신 전화 30분 시나리오를 실행한다. 완료 증거: 전화 수신/응답/종료 시각, recorder/track 상태와 복귀 동작이 기록되고 통화 내용은 기록되지 않는다.
7. 외부망 단절 30분 시나리오에서 60초 동안 외부 연결을 끊었다 복구한다. 완료 증거: 실제 단절, 로컬 pending 보존, 복구 뒤 현재 Session 처리 시작 가능 여부와 API-008 합계가 기록된다.
8. 각 Session을 실제 종료 흐름으로 마치고 final Chunk 저장/upload 완료 후 API-009 응답 및 route Session ID를 확인한다. 완료 증거: 예상/수신 순번과 서버/Frontend byte 합계가 일치한다.
9. 비운영 mock processing을 REVIEW까지 완료하거나 QA object를 즉시 삭제한다. 완료 증거: 남은 Audio object가 0건이거나 삭제 재시도 결과가 기록된다.
10. 실패/불명확 실행은 변수를 고정해 한 번 재현하고 비식별 Evidence 및 결함 후속정보를 작성한다. 완료 증거: 원본 녹음·통화·Secret이 없는 결론과 후속 Issue가 남는다.

## 의존 관계

- 1~2단계는 모든 실기기 실행의 공통 준비다.
- 3단계 baseline은 interruption 실행과 같은 device/app version을 사용한다.
- 4~7단계는 서로 독립이며 한 실행에는 방해 조건 하나만 적용한다.
- 8~9단계는 각각의 녹음 종료 뒤 수행한다.
- TASK-018.03은 network error 이후의 상세 sequence reconciliation을 이어받는다. TASK-018.04는 여기서 반복 재현된 MIME/Chunk/UI 결함 수정을 담당한다.

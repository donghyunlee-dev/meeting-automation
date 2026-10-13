# Processing Start API 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `FR-006`, `API-009`, `TASK-005.07`
- 선행 TASK-004.04 Issue [#17](https://github.com/donghyunlee-dev/meeting-automation/issues/17): memory-only Session
- 선행 TASK-005.04 Issue [#22](https://github.com/donghyunlee-dev/meeting-automation/issues/22): Chunk 저장소
- 선행 TASK-005.05 Issue [#23](https://github.com/donghyunlee-dev/meeting-automation/issues/23): 수신 sequence 목록
- 본 TASK Issue [#25](https://github.com/donghyunlee-dev/meeting-automation/issues/25)
- 후속 TASK-005.08: Recording 종료 후 Frontend 호출 연결; TASK-006: assembly/STT pipeline

## 변경 경계

- Backend API Controller: path/body/header binding와 response status/envelope
- Application command handler: API-007 첫 성공 Chunk의 Session 전이, API-009 Session state/transition, 실제 sequence/size query, idempotency, async task handoff
- Processing job port: 작업 한 번 enqueue 및 기본 stage 등록
- Domain/API types: 기존 Session state와 API-009 DTO 계약 재사용
- Audio assembly와 후속 AI provider 호출은 변경하지 않는다.

## 구현 순서

1. Session state graph, API-006 uploadPolicy, API-007/008 repository와 async job 기반을 확인한다. 첫 성공 Chunk는 `CREATED → UPLOADING` 전이를 일으키며 API-009는 `UPLOADING`만 받는다. 결과: 허용 시작 조건과 wiring 경계가 명확하다.
2. 첫 Chunk 저장 성공/실패 상태 전이, 처리 중 업로드 차단, validation, sequence gaps/extra, idempotency race/state conflict 테스트를 먼저 작성한다. 결과: 부작용 전 검증 규칙이 고정된다.
3. 첫 Chunk 상태 전이, command validation 및 실제 Chunk metadata query를 구현한다. 결과: Session lifecycle과 declared request가 server storage와 대조된다.
4. Session별 직렬화로 upload와 processing start의 경합을 막고, 처리 시작 key/payload record를 원자적으로 확인해 단일 job을 enqueue한다. 결과: 검증된 sequence 집합이 처리 도중 바뀌지 않고 동일 요청은 한 작업만 만든다.
5. application transition과 202 response를 연결한다. 결과: Session/processing base state와 stage가 조회된다.
6. 오류 envelope 및 queue/storage 실패 rollback 처리와 TASK-005.07에 직접 관련된 Backend 테스트를 확인한다. 결과: 부분 상태 및 이중 작업이 없다.

## 검증 명령

`backend/`에서 PowerShell `.\gradlew.bat test --tests com.meetingautomation.ProcessingStartApiTests --tests com.meetingautomation.AudioChunkApiTests`를 실행한다. 이 두 suite는 API-009 처리 시작, API-007 상태 전이/저장, API-010 응답 및 기존 업로드 회귀를 검증한다. API-010 status 조회로 새 processing 작업의 기본 stage/Session 응답을 확인한다.

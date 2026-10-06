# Mock 기반 핵심 E2E 시나리오

## 작업 식별 정보

- 작업: TASK-021.01
- 상위 작업: TASK-021 End-to-End Acceptance
- 단계: Phase 10 — End-to-End Acceptance
- PRD 기준: PRD-MA-001 v1.8.1, 2026-10-06
- 관련 기준: FR-002~015, SCR-002~006, API-006~014, EXT-001~002, DEC-020
- 선행 작업: PRD TASK-001.05~TASK-020.04; 핵심 interface는 TASK-004.02 #15, TASK-004.04 #17, TASK-005.02~.08 #20~#26, TASK-006.01~.06 #27~#32, TASK-008.01~.03 #37~#39, TASK-009.01~.02 #40~#41, TASK-020.04 #76
- GitHub Issue: [#77](https://github.com/donghyunlee-dev/meeting-automation/issues/77)

## 결과

실제 browser UI, Frontend API client, Backend HTTP API, in-memory session/pipeline과 mock 처리 Provider를 하나의 재현 가능한 E2E 실행으로 연결한다. 합성 회의를 생성하고 Browser recorder를 제어 fixture로 대체해 Audio Chunk를 업로드한 다음 실제 처리 API를 거쳐 Transcript/Speaker/Minutes 초안 확인, 화자 매핑, Minutes 수정 및 Template 교체 후 재생성을 검증한다.

## 주요 흐름

1. browser UI에서 synthetic meeting title, Template, Participant roster를 고른다. API-006은 `Idempotency-Key`, trimmed title, Template ID, non-empty 중복 없는 roster와 timezone/recovery key를 검증한다.
2. test-only browser fixture가 microphone/MediaRecorder lifecycle을 흉내 내고 MIME 정책 안의 결정적 synthetic binary Blob/Chunk를 생성한다. UI는 실제 API-007 upload client로 보내고 API-008 수신 순서/개수를 보여준다. 운영/실제 microphone을 사용하지 않는다.
3. 종료 UI가 recorder stop/final Chunk persist/upload 완료 뒤 API-009를 한 번 호출한다. 미수신 sequence가 있으면 API-009를 호출하지 않고 Processing으로 이동하지 않는다.
4. e2e Backend profile의 non-network test adapters가 audio assemble → STT → diarization → Minutes 생성 순서로 고정된 output을 만든다. Frontend는 API-010 poll로 동일 Session의 `REVIEW`, speaker list, time-ordered transcript, structured minutes를 조회한다.
5. SCR-006에서 Speaker mapping을 저장(API-011)하고 Minutes를 수정(API-012)한다. UI에서 기존 Template 표시와 사용자 confirmation을 거쳐 새 Template으로 재생성(API-013)한다.
6. 재생성 완료 뒤 API-010 latest snapshot으로 조회한다. Transcript와 Speaker mapping이 유지되고 Template/Minutes 결과만 교체된다. test adapters는 STT/diarization의 두 번째 호출을 실패시켜 재생성 중 해당 단계를 다시 부르지 않았음을 확인한다.

이 task는 Draft Review까지가 끝이며 Review Confirm/Document publish/Email/Slack/History security cleanup은 후속 TASK-021.02/.03 소유다.

## 테스트 격리와 계약

- `e2e` Backend profile은 Audio storage, Transcription, Diarization, Minutes generation port에 결정적 test adapter를 주입한다. mock 구현은 실제 Provider/network/credential 호출을 차단하고 QA run 종료에 격리 데이터를 제거한다.
- E2E Browser fixture만 `getUserMedia`와 `MediaRecorder`를 제어해 실제 microphone permission/device codec에 의존하지 않는다. 별도 Browser API compatibility는 TASK-020.04가 실기기에서 확인한다.
- Playwright는 Vite Frontend와 Backend boot app 두 프로세스를 같이 시작한다. Backend readiness는 기존 `/actuator/health`; Frontend API 주소는 `VITE_API_BASE_URL=http://127.0.0.1:8080`; test origin은 `http://127.0.0.1:4173`이다. test profile은 이 origin만 허용한다.
- 실제 API route, JSON/binary body, Idempotency-Key, If-Match version, 응답/상태 전이를 사용한다. 외부 Speech/LLM/Storage API를 직접 호출하거나 API 경로를 Playwright `route.fulfill`로 대체하지 않는다.
- API-013 이후 Transcript segments, speaker mapping, session roster가 전후 동일해야 한다. Minutes generation은 새 Template input으로 다시 호출되어야 한다. API-013은 Audio/STT/diarization을 재호출하지 않는다.
- flaky test 회피를 위해 고정 `sleep`을 쓰지 않고 UI의 상태/요소와 API-010 bounded eventual polling을 기다린다. 각 test는 unique idempotency key와 Session namespace를 사용하고 테스트 전후 app context/storage fixture를 초기화한다.

## 포함 시나리오

- 성공: meeting create → deterministic chunk upload/ACK → process API → `REVIEW` → Speaker mapping → Minutes update → confirmed Template regenerate → latest snapshot invariant.
- 재시도: 첫 STT 요청만 retryable로 실패 → API-010이 `PROCESSING_FAILED`, failed stage, 유효한 `allowedActions`와 최대 24시간 private Audio 보존 가능성을 반환 → 화면에서 사용자가 변환 재시도 → API-020 한 번 → 완료된 Audio assembly는 건너뛰고 실패한 STT 단계부터 재실행해 `REVIEW`까지 완료. 자동 재요청, Audio 재업로드, 완료 stage 재호출은 없다.
- 반복 최종 실패의 Audio 다운로드/폐기/실패 종료는 TASK-017.02/.03이 세부 API/UI를 검증하며 이 Task에서는 retry-to-success를 주 회귀로 둔다. 실패 기록은 참석자 Email/Slack으로 publish하지 않는다.
- API-013 no-STT regression: 첫 처리와 재생성의 deterministic provider guard가 Transcript/Speaker 동일성을 확인하고, Minutes content는 Template에 따라 달라진다.

## 범위 제외

- Document/Email/Slack publish 및 채널 retry (`TASK-021.02`)
- History/details, Secret/audio retention 전체 보안 acceptance (`TASK-021.03`)
- Android/iOS 실기기, actual MediaRecorder codec, 30/60분 및 network interruption (`TASK-018`, `TASK-020.04`)
- 실제 STT/diarization/LLM model quality, 실제 외부 API, persistent DB/Queue 또는 process restart 복구
- API 계약/공통 응답/provider selection/meeting status 변경

## 완료 기준

- `frontend/`에서 `npm run test:e2e`가 FE browser → API HTTP → BE pipeline 순서로 두 시나리오를 실행한다.
- Backend e2e profile이 외부 network 없이 mock audio/AI adapters를 사용하고 readiness/teardown 뒤 QA data가 격리/정리된다.
- Chunk binary size/hash/sequence accepted set과 API-009 요청 수/Session ID가 일치하고 final upload 이전 process call이 0건이다.
- 성공 시 API-006→007→009→010→011→012→013 경로 및 version/snapshot을 확인한다. API-013 뒤 Transcript/Speaker mapping이 고정되고 Minutes만 새로운 Template 결과가 된다.
- retryable 실패에서 원본 Audio가 private test storage에 보존되고 24시간 이상으로 연장되지 않으며, 사용자 명시 API-020 retry 후 failed stage부터 재개한다. 실제 Provider call 및 attendee Email/Slack delivery는 없다.
- `docs/evidence/TASK-021.01.md`에 command, commit, browser, scenario ID, sanitized request counts/sequence aggregate, 결과와 known limit을 기록한다.

## 관련 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)
- [녹음 종료 handoff](../../phase-03-recording/TASK-005.08/spec.md)
- [Processing pipeline](../../phase-04-processing/TASK-006.04/spec.md)
- [Minutes generation](../../phase-04-processing/TASK-006.05/spec.md)
- [API-010 snapshot](../../phase-04-processing/TASK-006.06/spec.md)
- [Minutes save integration](../../phase-05-review/TASK-008.03/spec.md)
- [Template regenerate UI](../../phase-05-review/TASK-009.02/spec.md)
- [Playwright multiple web servers](https://playwright.dev/docs/test-webserver#multiple-web-servers)

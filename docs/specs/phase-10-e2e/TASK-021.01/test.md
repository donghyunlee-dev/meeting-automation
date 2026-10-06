# Mock 기반 핵심 E2E 검증 계획

## 자동화 명령 및 격리

- Frontend project directory: `frontend/`
- Existing: `npm run test`, `npm run lint`, `npm run build`; `backend/`에서 `./gradlew clean build` (Windows: `gradlew.bat clean build`)
- New deliverable: `npm run test:e2e` (Playwright Chromium, Frontend Vite + Backend e2e profile dual web servers)
- Backend readiness: `GET /actuator/health` on `http://127.0.0.1:8080`; UI origin `http://127.0.0.1:4173`; CORS exact test origin only
- `e2e` profile uses in-memory Session/Audio fake and strict deterministic AI ports. Real providers, cloud object store, mail/Slack recipients, real mic/audio are disabled.
- Every Playwright run uses clean Session/store namespace, unique API idempotency keys, bounded condition polling; no fixed sleeps. Plan stage에서 테스트는 실행하지 않는다.

## 자동 시나리오

| ID | 입력/절차 | 기대 결과 | 증거 |
|---|---|---|---|
| E2E-BOOT-ISOLATED | `npm run test:e2e`로 API health + FE ready 대기, fake adapter 상태를 검사 | 둘 다 runner가 시작/종료; e2e profile 외부 provider client 없음, production CORS/config 미변경 | server logs/health status/commit |
| E2E-MEETING-CREATE | synthetic title/template/2 participant fixture로 SCR-002 제출 | API-006 201 Session/roster가 route 및 server에 동일; idempotency key/session ID 존재 | test assertion 및 Session ID |
| E2E-RECORDER-CHUNKS | test MediaRecorder stub이 고정 bytes/MIME chunks 생성; Recording UI로 stop/flush | 각 API-007 binary bytes/hash/length 유효, 연속 0-based sequence, API-008 received set와 expected chunks 일치 | sequence/count/byte/hash aggregate만 |
| E2E-HANDOFF-ONCE | 모든 chunk ACK 후 SCR-004 confirm | API-009 202 한 번, 같은 Session ID, Processing route; 누락 chunk fixture는 process 호출 없이 차단 | API call count/session ID/order |
| E2E-PIPELINE-REVIEW | API-010 polling 완료까지 대기 | fake assembly→STT→diarization→Minutes order, 한 Session의 `REVIEW`, `DRAFT_READY`, 100%, complete transcript/speakers/minutes | safe stage/order/result ids |
| E2E-SPEAKER-MAPPING | synthetic speaker를 선택된 roster participant에 매핑 | API-011 accepted, segment speaker IDs unchanged, mapping/version만 authoritative snapshot에 반영 | before/after mapping IDs/version |
| E2E-MINUTES-UPDATE | SCR-006에서 summary/decision/action item edit 및 save | API-012 full StructuredMinutes, latest If-Match, user change response/API-010 snapshot에 일치 | changed field IDs/version; text body 미기록 |
| E2E-MINUTES-REGENERATE | template 변경 선택+confirmation 후 API-013; completion까지 API-010 조회 | 새로운 Template Minutes가 나타나고 Transcript segments, mappings, Session roster 동일; STT/diarization strict fake는 2nd call에서 fail해 regression 검출 | template IDs, transcript/mapping stable hashes, safe fake guard result |
| E2E-STT-RETRY | 별도 Session에서 첫 STT fake response만 retryable failure로 설정; SCR-005 표시 확인 후 사용자가 재시도 클릭 | API-010 failed stage/`allowedActions`/만료정보가 표시되고 private test Audio 보존; 만료 시각은 마지막 실패로부터 24h 이내; API-020은 사용자 입력 1회만 호출; 완료된 Audio assembly는 skip, 실패 STT부터 성공 후 REVIEW까지 재개 | safe status/expiry window, API call count/order, final REVIEW |
| E2E-NO-PARTIAL-REVIEW | retryable STT 실패 상태에서 API-010/UI 조회 | speakers/transcript/minutes 부분 데이터가 없고 Minutes fake 미호출; Processing failed 안내만 표시 | response field presence assertion 및 fake guard |
| E2E-NO-EXTERNAL-CALL | 두 시나리오 전체 network/adapter invocation 관찰 | 외부 AI/storage endpoint, Email/Slack/Document publish 호출 0; 후속 TASK-021.02 publish는 시나리오에서 호출하지 않음 | allowlisted local request log/strict fake result |
| E2E-RESET-CLEANUP | 성공/retry 시나리오 뒤 test context/storage reset 수행 | 이전 run Session/Audio가 다음 run에 없고 fake fixture 잔류 없음 | cleanup result/count |
| E2E-ARTIFACT-PRIVACY | report/screenshot/trace output 및 committed Evidence를 민감 필드 scan | Audio/Transcript/Minutes raw text, attendee PII, Secret, provider body, signed URL 없음; browser network body artifact 비활성 | output path/config, scan result |

## 회귀 확인

- `frontend/`: `npm run test`, `npm run lint`, `npm run build`, `npm run test:e2e`
- `backend/`: `./gradlew clean build` (Windows `gradlew.bat clean build`)
- E2E를 clean run으로 최소 2회 반복해 Session/Idempotency/Provider counters/async eventual waits가 이전 실행에 오염되지 않는지 확인한다.
- API mutation `If-Match`, API-006/API-009/API-013 Idempotency-Key, stage transitions는 server/client integration assertions로 검사한다. 실제 network failure retry matrix와 delivery channels는 해당 task의 unit/후속 E2E가 소유한다.

## 완료 증거 및 판정

- 위치: `docs/evidence/TASK-021.01.md`
- 기록: FE/BE build commit, Node/Java/Gradle/browser versions, exact commands, scenario IDs, API route/call count, Session/version/stage, chunk count/sequence/aggregate byte/hash, test adapter call order/guard, cleanup, known limits
- 금지: raw Audio/Blob/Transcript/Minutes text, actual attendee name/email, Secret/token, backend raw provider body, private object URL, network HAR/trace with sensitive payload
- 성공: core success/failure 시나리오가 각각 green; API-013이 Audio/STT/diarization을 재실행하지 않고 full Review snapshot 일관성 통과; live external calls 0; run cleanup 완료.
- 실패: missing ACK 뒤 API-009 실행, duplicate Session/job/sequence, REVIEW 전에 partial data 노출, mapping/template가 다른 Session version에 붙음, API-013 후 Transcript/Speaker 변경, provider 재호출, raw sensitive content 또는 fake provider가 live network에 연결됨.

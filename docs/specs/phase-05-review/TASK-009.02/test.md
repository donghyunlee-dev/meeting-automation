# Template 재생성 UI 검증 계획

## 자동화 테스트

| ID | 조건과 입력 | 기대 결과 | 증거 |
|---|---|---|---|
| REG-UI-01 | API-010 current Template가 API-002 목록에 있음 | id 일치 item이 초기 선택되고 Minutes/template provenance가 일치 | component test |
| REG-UI-02 | API-002 loading/error/missing current item | loading/안전 오류/현재 id 안내; 임의 Template 요청 없음; Minutes draft 유지 | query state test |
| REG-UI-03 | 다른 Template 선택/현재 Template 재선택 | pending 값만 변경/원복; API mutation 0회 | selection test |
| REG-UI-04 | confirm 열기 후 취소, Escape, close | pending을 current로 되돌리고 API-013/API-012 호출 0회 | accessible dialog test |
| REG-UI-05 | clean draft, allowed action, confirm 승인 | `{templateId}`, current version If-Match, opaque Idempotency-Key로 API-013 1회 | API client contract test |
| REG-UI-06 | dirty Minutes draft로 confirm 승인 | API-012 save와 API-010 read-after-write 성공 뒤 최신 version으로 API-013 1회; 저장 전 요청 0회 | save-to-regenerate integration test |
| REG-UI-07 | dirty draft 저장 validation/network/412 실패 | API-013 호출 0회; draft와 pending Template 유지 및 save error 처리 | failure barrier test |
| REG-UI-08 | API-013 202 DRAFT_REGENERATION | Review controls 차단, 진행 문구, partial minutes 미표시, 즉시 API-010 query 시작 | processing state test |
| REG-UI-09 | API-010 계속 PROCESSING | polling 1초, 연속 query error 2/4/8/최대10초, 단일 in-flight | fake timer/polling test |
| REG-UI-10 | status REVIEW, matching session/version, success lastOperation/template | minutes/base/cache/template/action이 한 최신 snapshot으로 교체되고 성공 안내 1회 | success reconciliation test |
| REG-UI-11 | status REVIEW, matching failure lastOperation/template | rollback Minutes/provenance가 표시되고 selector가 이전 template로 복원되며 safe 안내 | rollback reconciliation test |
| REG-UI-12 | response sessionId mismatch 또는 version이 request base 이하 | response 무시/재조회; form/cache가 뒤로 가지 않음 | stale response test |
| REG-UI-13 | API-013 400/409/412 | 자동 새 key 재전송 없음; pending/draft 보존, 412는 API-010 latest 조회 | mutation error test |
| REG-UI-14 | POST 네트워크 결과 불명확, API-010은 PROCESSING/DRAFT_REGENERATION | 이미 accepted로 간주하고 기존 same-intent poll; 새 key/중복 POST 없음 | uncertain acceptance test |
| REG-UI-15 | POST 네트워크 결과 불명확, API-010 REVIEW version > base 및 matching lastOperation | terminal snapshot reconcile; 불필요한 POST 없음 | uncertain terminal test |
| REG-UI-16 | route unmount/hidden tab/재진입 | timer/GET 취소 및 중복 없음; 재진입 시 API-010 current 상태로 복구 | lifecycle test |
| REG-UI-17 | 일반 PROCESSING_FAILED/SESSION_NOT_FOUND/matching lastOperation 없음 | 일반 안전 안내/재조회 경로; 성공·rollback을 추측하지 않음 | terminal error mapping test |
| REG-UI-18 | 키보드 only, screen reader, reduced motion, 360px | dialog focus 관리/return, live status label, overflow·필수 정보 누락 없음 | accessibility/browser test |
| REG-UI-19 | API error 및 telemetry capture | Transcript/Minutes/participant email/provider detail/Secret 원문 없음 | logging redaction test |
| REG-UI-20 | UI regeneration API dependency trace | Audio/STT/Diarization API를 frontend에서 호출하지 않음 | network mock assertion |

Frontend test/lint/build 명령은 구현 checkout의 package manifest scripts를 먼저 확인한 뒤 해당 workspace에서 실행한다. 현재 설계 checkout에는 package manifest가 없어 정확한 명령을 아직 지정하지 않는다.

## 수동 UI QA

- 현재 기본 Template을 프로젝트 Template으로 변경하고 cancel하면 Minutes와 서버가 변하지 않는지 확인한다.
- clean form에서 확인을 거친 뒤 202, DRAFT_REGENERATION 화면, 성공 Minutes와 selected Template 갱신을 확인한다.
- Minutes를 수정한 뒤 저장 후 재생성을 진행하고 API-012 성공/read-after-write 이전에 regeneration POST가 발생하지 않는지 확인한다.
- 저장 오류/412에서 editor draft와 pending Template이 보존되고 API-013이 호출되지 않는지 확인한다.
- 재생성 중 route를 벗어났다가 복귀해 polling duplication 없이 상태가 복원되는지 확인한다.
- 실패 fixture에서 이전 사용자 수정 Minutes와 Template이 복구되고 안전 안내가 표시되는지 확인한다.
- keyboard/ESC/focus return, screen-reader busy/status labels, reduced motion, 360px viewport를 확인한다.

## 릴리스 확인

- `TASK-009.03`에서 Backend/Integration이 STT와 Diarization 호출 횟수 0을 검증한다. 본 UI 테스트는 프론트엔드 네트워크 요청이 해당 경로를 호출하지 않는지만 확인한다.
- 화면 error/log에는 API/provider raw message 또는 회의 콘텐츠가 노출되지 않는다.
- production publish는 포함하지 않는다. 실행한 실제 Frontend 명령과 결과 및 QA evidence를 Issue #41에 기록한다.

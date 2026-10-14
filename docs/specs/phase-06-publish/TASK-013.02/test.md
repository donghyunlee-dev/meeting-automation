# 검증 계획

기준: `TASK-013.02`, PRD v1.7.0 (2026-10-05), `SCR-008`, `API-010`, `API-016`, `FR-019`, `FR-027`, Issue [#51](https://github.com/donghyunlee-dev/meeting-automation/issues/51).

## 자동화 검증

`frontend/package.json`의 Vitest, ESLint, TypeScript/Vite 명령을 사용한다. API-010/API-016은 mock response로 검증하고 실제 이메일/Slack 전달은 하지 않는다. 변경 파일의 focused component/API tests와 lint/build만 실행한다. 검증은 DOM accessibility/keyboard assertion과 CSS breakpoint 검사로 제한하며 local service나 별도 Browser QA pass를 시작하지 않는다.

| ID | 준비 및 입력 | 기대 결과 | 증거 |
|---|---|---|---|
| COMPLETE-01 | 저장된 Document와 모든 존재 Delivery SENT | Document/각 Email/Slack row 표시, 완료 요약; `SENT`는 Provider 수락으로만 표현하고 inbox 도착/열람을 주장하지 않음 | Summary selector test |
| COMPLETE-02 | Delivery 중 하나 이상 PENDING/SENDING | `전달 진행 중`, 완료 문구/Retry 없음 | In-progress summary test |
| COMPLETE-03 | SENT 하나와 FAILED 하나 혼합 | `일부 전달 실패`, 독립 row 상태 유지 | Partial result test |
| COMPLETE-04 | 모든 Delivery FAILED | `전달 실패`; 각 row별 `retryable`에 따라 action 다름 | All failed summary test |
| COMPLETE-05 | `deliveries:[]` | 문서 저장 완료만 표시, 채널 성공/미선택 추정 없음 | Empty result test |
| COMPLETE-06 | documentUrl valid http/https | 안전한 외부 문서 링크 제공 | Document link test |
| COMPLETE-07 | documentUrl 누락/invalid 또는 document reference 없음 | URL link 없음; 문서 미저장 시 TASK-010.03 경로 유지 | Missing document reference test |
| COMPLETE-08 | Email Delivery에 recipientParticipantId 있음, API-003 이름 정보 있음/없음 | 수신자 이름 또는 ID fallback 표시; Email 주소 미표시 | Recipient label test |
| COMPLETE-09 | `channel=NOTIFICATION` Delivery 혼합 | Slack 행과 Email row 분리 표시; 하나의 실패가 다른 상태를 변경하지 않음 | Channel isolation test |
| COMPLETE-10 | FAILED + `retryable=true` row | 해당 행에만 Retry action/식별 가능한 accessible name | Retry eligibility component test |
| COMPLETE-11 | `SENT`, `PENDING`, `SENDING`, 또는 `retryable=false` | 해당 행 Retry action 없음 | Retry eligibility negative test |
| COMPLETE-12 | 사용자가 특정 row Retry 클릭, API-016 returns 202 | 해당 row만 PENDING; API-010 polling으로 최종 상태/attemptCount 반영 | Retry integration test |
| COMPLETE-13 | Retry POST timeout/response loss | key를 보존하고 API-010으로 조정; 같은 사용자 동작 replay는 중복 attempt 없음 | Same-key reconciliation test |
| COMPLETE-14 | 이전 API-016 결과 FAILED retryable true로 완료, 사용자가 다시 클릭 | 새 명시 action에 새 key 발급, 한 개의 신규 attempt | New user attempt test |
| COMPLETE-15 | API-016 409/404 또는 API-010 polling 404 | 안전 안내 및 최신 상태 재조회; 다른 row/문서 결과는 보존 | Error recovery test |
| COMPLETE-16 | polling 중 더 오래된 API-010 응답이 늦게 도착 | 최신 Session/Delivery state를 덮지 않음 | Stale response test |
| COMPLETE-17 | API payload, screen, log 검사 | 전체 이메일, webhook URL, OAuth/Secret, Provider raw body가 없음 | Privacy/redaction test |
| COMPLETE-18 | 360px breakpoint, keyboard-only interaction, live-region DOM 확인 | row/status/Retry/link 접근 가능; focus가 polling으로 이동하지 않음 | Focused component/accessibility test |

## 자동화된 화면 접근성 검증

- Component tests에서 Document saved + Email mixed outcomes + Slack result의 mock state와 summary/row를 확인한다.
- Component/API tests에서 retryable failure 한 건만 API-016을 호출하고 이후 API-010에서 해당 행만 바뀌는지 확인한다.
- `retryable=false` outcome에는 Retry가 없고 안전한 중복 방지 안내가 있는지 DOM에서 확인한다.
- API-016 응답 유실 뒤 같은 interaction의 Idempotency-Key 보존 및 polling 재조정을 확인한다.
- invalid document URL, 이름 lookup 실패, no Delivery, all failed, in-progress, keyboard/live-region 접근성을 focused tests에서 검증한다. 별도 browser/service QA는 수행하지 않는다.

## 완료 증거

Frontend focused test/lint/build 명령과 결과, DOM accessibility/keyboard/360px breakpoint assertions, API-016 단건 action 및 민감정보 가림 증거를 Issue #51에 기록한다. local service/Browser QA pass와 실제 Gmail/Slack 메시지 전송은 수행하지 않는다.

## 실행 결과 (2026-10-14)

| 명령 (working directory: `frontend`) | 결과 |
|---|---|
| `npm run test -- --run src/CompletePage.test.tsx src/publishApi.test.ts src/SharePage.test.tsx` | PASS — 3 test files, 34 tests |
| `npx eslint src/CompletePage.tsx src/CompletePage.test.tsx src/publishApi.ts src/publishApi.test.ts src/App.tsx src/SharePage.tsx` | PASS |
| `npm run build` | PASS — TypeScript check and Vite production build |
| `git diff --check` (repository root) | PASS |

Component/API mock tests cover mixed/all-sent/all-failed/no-delivery summaries; safe and missing document links; participant ID fallback and email privacy; retry eligibility and 202 polling; accessible button focus/name/live status; and response-loss key reuse. CSS applies a 420px responsive layout; tests assert keyboard-accessible DOM. Repository policy prohibits local service/Browser QA, so no real provider delivery or rendered-device check was run.

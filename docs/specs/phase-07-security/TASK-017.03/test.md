# 검증 계획

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| RECOVERY-UI-01 | `PROCESSING_FAILED` at `AUDIO_ASSEMBLY`, retryable + chunks | 한국어 upload/assembly failure copy, Retry visible, Download hidden if no assembled audio | stage/action component test |
| RECOVERY-UI-02 | `TRANSCRIPTION`, `DIARIZATION`, `MINUTES_GENERATION` failures | 각 stage의 올바른 한국어 이름, partial data/raw error hidden | stage mapper tests |
| RECOVERY-UI-03 | `retryable=false` + Audio exists | Retry hidden, Download/Finalize only when API actions allow | action matrix test |
| RECOVERY-UI-04 | API-010 includes Retry action and current version | API-020 request has one fresh Idempotency-Key and current If-Match; button locks | mutation/client test |
| RECOVERY-UI-05 | Retry POST succeeds with 202, pipeline succeeds | API-010 refetch, stepper resumes, matching Session REVIEW routes once | integration navigation test |
| RECOVERY-UI-06 | Retry POST times out or returns unknown network outcome | same attempt key is retained; client re-GETs API-010 before another retry; duplicate run not submitted | network reconciliation test |
| RECOVERY-UI-07 | Retry ends in `PROCESSING_FAILED` again | updated stage/retryability/audio expiry/actions render from new API-010 data | query invalidation integration test |
| RECOVERY-UI-08 | `DOWNLOAD_AUDIO` action present | native API-021 attachment link opens; FE doesn't read/retain Audio bytes or build a blob URL | anchor contract test |
| RECOVERY-UI-09 | Download 200 then user confirms saved | API-022 sends `DOWNLOADED`, one finalize action in flight | finalize mutation test |
| RECOVERY-UI-10 | Download fails/returns `AUDIO_NOT_AVAILABLE` | no `DOWNLOADED` finalize; API-010 refresh and safe expiry/error copy | download error integration test |
| RECOVERY-UI-11 | User selects failure finish without download | explicit confirmation then API-022 `DISCARDED` | confirmation/form test |
| RECOVERY-UI-12 | API-022 returns 202 then state completes | refetch and show warning completion/document link; no email/slack/share controls | completion state integration test |
| RECOVERY-UI-13 | API-022 fails `DOCUMENT_FAILED`/network | no success claim; safe guidance and server-allowed finalize retry shown | error state test |
| RECOVERY-UI-14 | `audioExpiresAt` crosses or tab resumes from background | API-010 refresh; actions follow server response, never local countdown alone | fake clock/visibility lifecycle test |
| RECOVERY-UI-15 | `SESSION_NOT_FOUND` after service restart | retry/download hidden; safe explanation and New Meeting path, no auto-session recreation | missing-session test |
| RECOVERY-UI-16 | PROCESSING_FAILED, success, warning, error announced by AT | key state announced once; ticking timer not announced repeatedly | accessibility query/test |
| RECOVERY-UI-17 | 360px viewport, keyboard navigation, reduced-motion | no horizontal overflow, 44px targets, focus/state visible, motion reduced | responsive/a11y test |

## 수동 QA

| ID | 절차 | 기대 결과 | 필요한 증거 |
|---|---|---|---|
| RECOVERY-QA-01 | Android Chrome에서 transient STT failure → Retry → success | Retry action은 명시 동작 1회, Review 이동과 stage updates가 서버 state에 일치 | screen recording/trace IDs, no audio payload |
| RECOVERY-QA-02 | 반복 실패 → Audio 다운로드 → browser 복귀 → 저장 확인 | attachment가 기기에 저장되고 실패 문서가 다운로드 disposition으로 종료 | download file existence, API-022 payload/action screenshot |
| RECOVERY-QA-03 | iOS Safari에서 Audio 다운로드 및 실패 종료 | attachment/download UI가 브라우저 동작에 맞게 표시되고 저장 여부 확인 가능 | device/browser/version and capture |
| RECOVERY-QA-04 | 다운로드 안 함 선택 후 실패 마무리 | 명시 discard confirmation, `DISCARDED`, Email/Slack 전달 없음 | Document URL + zero-delivery evidence |
| RECOVERY-QA-05 | tab background 중 만료 후 foreground 복귀 | API refresh 후 expired actions가 즉시 반영됨 | timestamps + API response |
| RECOVERY-QA-06 | VoiceOver/TalkBack + keyboard + reduced motion | failure heading/action/status 명확, countdown 초마다 낭독되지 않음 | short checklist and captures |
| RECOVERY-QA-07 | PROCESSING_FAILED, HTTP errors, SESSION_NOT_FOUND | 사용자용 안내만 표시, provider body/Audio/Transcript/Secret 없음 | screenshot scan and sanitized logs |

## Release/evidence

- Frontend repository/package scripts are not present in the current checkout; implementation must discover the actual test/lint/build commands before running them.
- Evidence stores fixture IDs, route/status/action, response codes, Session/trace IDs and redacted screenshots only. Do not attach Audio, Transcript, Secret or raw Provider body.
- Actual mobile download behavior is a manual release evidence item for Android Chrome and iOS Safari.

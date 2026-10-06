# History·보안·임시 Audio E2E 검증

## 작업 식별 정보

- 작업: TASK-021.03
- 상위 작업: TASK-021 End-to-End Acceptance
- 단계: Phase 10 — End-to-End Acceptance
- PRD 기준: PRD-MA-001 v1.8.1, 2026-10-06
- 관련 기준: FR-020~030, API-001/010/017/018/019/020/021/022, DEC-017/020, NFR-004/006
- 선행 작업: TASK-021.02 Issue #78; History 계약 TASK-014.01~.04 Issues #52~#55; Audio·보안 TASK-017.01~.04 Issues #60/#61/#62/#64
- GitHub Issue: [#79](https://github.com/donghyunlee-dev/meeting-automation/issues/79)

## 결과

완료 회의의 History 재조회가 읽기 전용임을 확인하고, 정상 처리 및 실패 처리 경로의 민감정보 경계와 임시 Audio 수명주기를 browser-to-API E2E로 검증한다. 처리 실패 시 회의록은 보존하되 Audio 변환 실패를 기록한다. 사용자는 화면에서 재시도할 수 있고, 반복 실패 후 Audio 다운로드 또는 폐기를 선택한다. Audio는 서버 비공개 저장소에서 최대 24시간만 유지하며, 실패 회의록/Delivery는 참석자에게 전송하지 않는다.

## 동작 계약

- History의 API-017 목록 및 API-018 상세 GET은 완료 회의의 저장된 값을 반환하며 반복 조회·검색·필터·상세 열기가 Session, Document, Delivery 상태나 Provider 부작용을 변경하지 않는다. 상세 화면은 읽기 전용이고 원문 Provider URL은 기존 권한/응답 계약을 따른다.
- UI는 `provider: null`이면 기존 연결 선택/설정 화면으로 안내한다. 임의 Provider를 선택하거나 연결/회의 상태를 추정하지 않는다. 참석자의 존재 또는 활성 상태를 새로 도입하지 않는다.
- API 응답은 공통 성공/오류 envelope를 유지한다. API-010 처리 실패 응답은 기존 계약의 stage/progress/errorCode/retryable/audioAvailable/audioExpiresAt/allowedActions만 필요한 화면에서 소비한다. Secret, Audio bytes/object key/public URL, raw provider 오류를 포함하지 않는다.
- 정상 파이프라인의 Review 진입 시 private chunks와 assembled Audio를 삭제한다. Browser는 API-007 ACK를 받은 chunk를 IndexedDB에서 제거하며, 완료 뒤 로컬 잔여 chunk 및 세션 녹음 데이터를 정리한다.
- STT/화자분리/Minutes 실패 시 직전 완료 산출물을 유지하고 assembled Audio만 최대 24시간 비공개로 보존한다. 만료 시각은 UTC `audioExpiresAt`이다. 명시적 재시도 중에는 입력 Audio를 보존하고 TTL을 멈춘다. 재시도 실패는 그 시점부터 TTL을 다시 계산한다. 성공 Review 진입은 즉시 정리한다.
- 재시도 버튼은 API-010 `allowedActions`가 허용할 때만 보이고 API-020은 새 `Idempotency-Key`와 최신 `If-Match`로 한 번 요청한다. 완료된 단계를 반복하지 않고 실패 단계부터 재개한다.
- 사용자가 다운로드를 택하면 API-021이 인증된 attachment 응답으로 스트리밍한다. 성공 응답 확인 후 API-022 `audioDisposition=DOWNLOADED`로 실패 기록을 확정한다. 다운로드 성공 전 `DOWNLOADED` 확정은 거절한다. 폐기를 택하면 `DISCARDED`로 확정한다. 실패 기록에는 회의 메타데이터, 실패 단계/safe code, disposition, 완료시각만 남기고 Transcript/Minutes/Audio/provider 원문은 넣지 않는다.
- 만료 뒤에는 sweep 완료 전이라도 retry/download가 거절되고 해당 action이 제거된다. Sweeper는 재시작 후에도 저장된 만료 정보를 기준으로 객체를 삭제하고 미완료 실패를 `DISCARDED`로 자동 확정한다. 삭제와 확정은 멱등이다.
- 실패 확정/자동 확정은 회의록 Document만 저장한다. Email/Slack delivery 행 생성 및 발송, attendee 공유 action은 0회다.

## 범위와 제외

- 포함: History list/detail/read-only 왕복, null provider 안내, API-010~022 실패 복구 UI/API 연결, 로컬 chunk 및 private Audio 정리, 보존기한/재시작/sweeper, 비노출 canary 회귀, 실패 문서의 무발송.
- 제외: 실제 Provider 서비스 연결, 실제 녹음/개인정보 fixture, provider adapter wire 검증(TASK-021.02), Storage 구현 변경(TASK-017.02), UI 재설계(TASK-017.03), 광범위한 하위 계층 scanner 재구현(TASK-017.04). 이 작업은 해당 계약을 통합 수준에서 소비한다.

## 완료 기준

- `npm run test:e2e:history-security`가 TASK-021.01 browser/backend harness에서 목록·상세·반복 GET을 실행하고 read-only 불변식을 검증한다.
- 성공 Review 및 ACK된 chunk의 정상 정리, 실패 Audio 보존, 재시도 성공/실패, 다운로드/폐기, 24시간 만료/재시작 sweeper를 deterministic clock과 private test storage로 검증한다.
- attachment download가 browser native download로 완료되고 UI application state에 Blob/object URL/Audio bytes를 저장하지 않는다. 만료 또는 권한 거부 때 download/retry UI와 API 요청이 금지된다.
- Secret canary, storage key 및 raw 오류/Audio/Transcript/Minutes는 bundle, API error, log, Evidence와 실패 Document에서 탐지되지 않는다. 민감값 자체 대신 rule ID/count만 보고한다.
- Audio 실패 경로는 Document-only이며 Email/Slack delivery 0건이다. Evidence에는 fixture 식별자, 상태·호출 횟수, safe code, cleanup 결과만 기록한다.

## 관련 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)
- [선행 publish E2E](../TASK-021.02/spec.md)
- [History 읽기 전용 화면](../../phase-07-history/TASK-014.04/spec.md)
- [비공개 Audio 및 종료 API](../../phase-07-security/TASK-017.02/spec.md)
- [Audio 복구 화면](../../phase-07-security/TASK-017.03/spec.md)
- [보안·개인정보 회귀](../../phase-07-security/TASK-017.04/spec.md)

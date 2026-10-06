# Temporary Audio 보존과 변환 실패 종료

## 목표

STT, diarization 또는 Minutes 변환이 실패해도 사용자가 직접 다시 시도하고, 반복 실패 시 원본 Audio를 다운로드하거나 실패로 마무리할 수 있도록 Backend 임시 Audio 수명주기를 정의한다. 실패 마무리 Meeting 문서에는 회의 정보와 변환 실패를 남기며 참석자 Email/Slack 전달은 수행하지 않는다.

PRD v1.8.0 (2026-10-06), `TASK-017.02`, `DEC-020`, `FR-027`, `FR-028`, `NFR-006`, `API-010`, `API-020~022`를 구체화한다. Issue [#61](https://github.com/donghyunlee-dev/meeting-automation/issues/61).

## 범위

- Backend chunk 및 assembled Audio의 성공·실패·재시도·다운로드·정리 수명주기
- `PROCESSING_FAILED` 상태의 retryability, Audio 가용성/만료 metadata 및 허용 action 제공
- 사용자 명시 processing retry를 실패 stage부터 재개
- 원본 Audio binary 다운로드와 24시간 보존 기한 처리
- 사용자가 실패를 마무리하거나 만료 시 회의 메타데이터/실패 기록만 Meeting 문서로 저장
- 변환 실패 종료에서는 Email/Slack 전달을 생성하지 않음
- 비밀/오디오/Transcript 경계를 지키는 정리·로그 정책

## 비범위

- Vercel Blob, Object Storage, DB, Redis, Queue 추가
- Audio, Transcript, Minutes 또는 Provider raw body를 실패 문서에 저장
- 누락/손상 source chunk 재업로드 및 chunk별 다운로드
- 자동 retry, retry 횟수 상한, 참가자별 상태 관리
- 정상 처리의 Review/Confirm/Publish 동작 변경
- 화면 컴포넌트 구현. 사용자 복구 화면은 후속 `TASK-017.03` 범위다.

## 동작 계약

- Meeting metadata, Template, 작성자가 고른 Participant roster는 녹음 시작 전에 Session에 확정되어 있다.
- 정상 pipeline이 `REVIEW`에 진입하면 backend chunk와 assembled Audio를 정리한다.
- Audio assembly 뒤 `TRANSCRIPTION`, `DIARIZATION`, `MINUTES_GENERATION` 변환 stage가 실패하면 Session을 `PROCESSING_FAILED`로 기록한다. 이전 완료 stage 산출물은 보존해 재시도 시 실패 stage부터 재개한다. `MINUTES_GENERATION` retry는 기존 Transcript와 mapping만 재사용한다.
- Audio assembly 실패는 source chunks를 최대 24시간 보존한다. 실패가 retryable이고 모든 expected chunk가 남아 있으면 `RETRY_PROCESSING`으로 assembly stage부터 재개한다. 검증된 assembled Audio가 없으면 `DOWNLOAD_AUDIO`를 제공하지 않고 실패 마무리 시 chunks를 폐기한다.
- Assembly 완료 뒤 발생한 변환 실패는 assembled Audio를 실패 시각부터 최대 24시간 보존한다. retryable 오류에만 `RETRY_PROCESSING`을 제공한다. retryable이 아닌 경우에도 assembled Audio 다운로드/실패 종료는 보존 기간 동안 허용한다.
- 24시간은 `PROCESSING_FAILED` 대기 상태에 적용한다. 사용자가 기한 전에 명시 retry를 시작하면 active processing 동안 입력 Audio/chunks를 삭제하지 않고 대기 TTL을 정지한다. retry가 다시 실패하면 그 실패 시각부터 24시간을 새로 계산한다. retry 성공 시 Review 진입 전에 Audio와 chunk를 정리한다. 각 사용 retry는 새 `Idempotency-Key`와 최신 `If-Match`를 사용한다.
- 다운로드는 서버가 제공한 원본 Audio stream을 browser attachment로 전달한다. Browser가 성공 응답을 받은 뒤 사용자가 저장 여부를 선택한다. 사용자는 저장 확인 후 실패를 `DOWNLOADED`로 마무리하거나 `DISCARDED`로 마무리할 수 있다.
- 실패 종료는 `COMPLETED_WITH_WARNINGS`로 전이하고, Meeting Document에 이미 확정된 meeting metadata, 실패 stage, 안전한 error code, 사용자의 Audio disposition, 완료 시각을 기록한다. Transcript/Minutes 본문/Audio/provider error 원문은 기록하지 않는다.
- 실패 종료는 Meeting Document 저장만 실행한다. Email/Slack Delivery를 만들지 않으며 참석자에게 실패 상태를 보내지 않는다. 정상 변환은 기존 Review/Confirm/Publish 경로를 따른다.
- 미종료 Audio는 마지막 변환 실패 후 24시간에 접근 불가해진다. 만료 처리기가 Audio/chunk를 제거하고 `DISCARDED`로 실패 문서를 저장해 `COMPLETED_WITH_WARNINGS`로 종료한다. Document 저장 실패는 안전 오류를 보존하고 Audio disposition/Session을 유지한다. 사용자가 finalize를 재요청할 때는 새 Idempotency-Key를 사용하고 provider `externalSessionId` 멱등성으로 중복 문서를 막는다.
- 만료 시각은 UTC instant로 저장한다. API는 만료 시각이 지났으면 처리기가 파일을 지우기 전이라도 retry/download를 거부한다. 정리기는 적어도 1분 주기로 기한을 확인하고 삭제를 재수행한다. active retry는 만료 sweep에서 제외한다. 삭제는 idempotent다.
- Session과 job은 메모리 전용이다. Backend 재시작/재배포 뒤 Session 복구 및 Audio retry/download를 보장하지 않는다. Session이 사라지면 기존 API의 `SESSION_NOT_FOUND`를 반환한다.
- 삭제 실패 시 해당 경로를 성공 삭제로 기록하지 않는다. 파일 경로/Audio bytes/Transcript/Secret 없이 `sessionId`, `traceId`, stage, error code, cleanup outcome만 로그에 남긴다. 경로는 서버 생성 ID 하위로 제한하고 symlink 및 경로 탈출을 거부한다.

## API 계약

- `API-010`: `PROCESSING_FAILED`에 `processing:{stage,progressPercent,errorCode,retryable,audioAvailable,audioExpiresAt?}`를 반환한다. 상태별 서버 허용 action만 `allowedActions`에 포함한다.
- `API-020`: `POST /meeting-sessions/{sessionId}/processing/retry`는 `Idempotency-Key`와 `If-Match`를 사용하며 retryable 실패·미만료 Audio 또는 필요한 complete chunks만 허용한다. 이미 완료한 stage를 건너뛰고 실패 stage부터 처리한다.
- `API-021`: `GET /meeting-sessions/{sessionId}/audio/download`는 검증된 assembled Audio가 미만료일 때 binary attachment로 반환한다. 응답 body는 공통 JSON envelope 예외다.
- `API-022`: `POST /meeting-sessions/{sessionId}/processing/finalize-failure`는 `{audioDisposition:"DOWNLOADED"|"DISCARDED"}`를 받는다. 성공 download가 없는 `DOWNLOADED` 요청은 거절한다.
- 만료·파일 부재는 `AUDIO_NOT_AVAILABLE`; 상태 충돌은 `SESSION_STATE_CONFLICT`; stale version은 기존 `SESSION_VERSION_CONFLICT`를 따른다.

## 수용 기준

- 정상 처리 성공은 Review 진입 전에 임시 Audio와 chunks를 제거한다.
- retryable 변환 실패는 failed stage와 Audio 만료 시각을 저장하고 만료 전 사용자 재시도 action을 제공한다.
- `MINUTES_GENERATION` 재시도는 STT/Diarization을 다시 호출하지 않는다.
- non-retryable 변환 실패는 재시도 action 없이 다운로드와 실패 종료를 제공한다.
- 재시도는 완료 stage를 다시 호출하지 않으며 retry 실패 시 만료 시각을 갱신한다.
- 기한 경과 즉시 API가 retry/download를 차단하고 정리기가 임시 Audio/chunks를 삭제한다.
- 성공 download 뒤 `DOWNLOADED`, 사용자가 저장하지 않기로 선택하면 `DISCARDED`로 실패 종료한다.
- 실패 문서에는 meeting metadata와 실패 요약/disposition이 있고 Transcript/Minutes/Audio/raw provider 오류가 없다.
- 실패 종료/만료 시 Email/Slack 호출 및 Delivery row가 0건이다.
- 중복 finalize, 반복 cleanup, download 불가, 잘못된 경로, symlink, 삭제 오류가 안전하게 처리된다.
- Backend restart 후 Session을 찾을 수 없으며 오래된 임시 파일 정리만 수행한다.

## 승인된 결정

- 사용자는 변환 실패 후 화면에서 재시도를 직접 요청한다.
- 실패가 반복되면 사용자는 원본 Audio를 다운로드하거나 변환 실패로 마무리한다.
- 실패 Meeting 문서에는 Audio 다운로드/변환 실패를 기록하되 Email/Slack은 참석자에게 보내지 않는다.
- Processing 실패의 필요한 retry input 및 검증된 assembled Audio 보존 기간은 마지막 실패로부터 최대 24시간이며, 각 명시 재시도 실패 뒤 기한을 다시 계산한다.
- 사용자 결정은 Issue #61에 기록했다.

## 추적성 참고

PRD 기존 TASK-017.01/017.02 trace ID가 FR-028/FR-030에 서로 뒤바뀌어 있었다. 이번 승인된 PRD 갱신에서 TASK-017.01은 Secret 요구사항 `FR-030`, TASK-017.02는 Audio 요구사항 `FR-027/028`을 추적하도록 정정했다.

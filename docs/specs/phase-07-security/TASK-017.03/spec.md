# Processing 실패 재시도와 Audio 복구 화면

## 목표

`SCR-005 Processing`이 API-010의 처리 실패 상태를 안전한 한국어 안내와 사용자 복구 동작으로 연결한다. 사용자는 retryable 실패를 직접 재시도하고, 필요하면 원본 Audio를 다운로드한 뒤 실패를 마무리할 수 있다. 실패 회의는 참석자 Email/Slack으로 전달하지 않는다.

PRD v1.8.1 (2026-10-06), `TASK-017.03`, `SCR-005`, `FR-027`, `DEC-020`, `API-010`, `API-020~022`를 구체화한다. Issue [#62](https://github.com/donghyunlee-dev/meeting-automation/issues/62).

## 범위

- API-010 processing stage/status/error metadata와 `allowedActions`에 동기화된 Processing UI
- API-020 사용자 명시 변환 재시도, 중복 제출 방지, result polling 및 Review 이동
- API-021 browser-native attachment Audio 다운로드와 사용자의 저장 확인
- API-022 `DOWNLOADED`/`DISCARDED` 선택으로 실패 문서 마무리
- Audio 만료 countdown 안내, 만료 시 state refresh, 문서 저장/조회 오류 안내
- 모바일 접근성, 보조기술 알림, reduced-motion 및 민감 내용 비노출

## 비범위

- Backend API, stage retry, Audio retention/cleanup, provider document 처리 구현 (`TASK-017.02`)
- Backend private object storage provider/adapter 구현 (`TASK-017.02`)
- 자동 pipeline 재시도 또는 FE timer만으로 retry/download 가능 여부 판단
- 이메일 수신자 선택, Slack notification 또는 실패 문서 Publish
- Transcript/Speaker/Minutes 편집 및 Review/Confirm UI
- `SESSION_NOT_FOUND` Session을 자동 복구하거나 새 Session으로 대체

## 화면 상태와 동작

- 정상 `PROCESSING`은 기존 stepper를 유지하고, 실제 Backend stage와 일치하는 단계별 완료/현재/대기를 표시한다.
- `PROCESSING_FAILED`에서는 “녹음 변환에 실패했어요” 안내, 실패 stage의 한국어 이름, 짧은 안전한 다음 단계와 서버 만료 시각을 표시한다. `errorCode`, provider message, raw response, Audio bytes/Transcript는 렌더링하지 않는다.
- Recovery button은 `allowedActions`에 포함될 때만 보여준다. `RETRY_PROCESSING`은 retryable 오류와 유효한 input이 있을 때, `DOWNLOAD_AUDIO`는 검증된 assembled Audio가 있을 때, `FINALIZE_PROCESSING_FAILURE`는 실패를 종료할 수 있을 때만 표시한다.
- `RETRY_PROCESSING`을 누르면 새 retry attempt용 `Idempotency-Key`와 최신 `If-Match`로 API-020을 호출한다. 전송 결과가 불명확해 같은 요청을 재전송하면 동일 key를 재사용한다. 요청 중 모든 recovery button을 잠그고 중복 제출을 막는다.
- API-020 `202` 또는 timeout 뒤 API-010을 다시 조회한다. 새 `PROCESSING`이면 기존 stepper/polling으로 돌아간다. Review가 완료되면 route Session ID가 일치할 때만 Review로 이동한다. 다시 실패하면 새 stage, retryability, 만료 시각으로 화면을 갱신한다.
- 보존 중에는 남은 시간을 `audioExpiresAt` 기준으로 표시한다. 로컬 countdown은 설명 전용이고, action eligibility는 서버 `allowedActions`만 따른다. `PROCESSING_FAILED` 대기 중에는 잦은 polling 대신 expiry timer를 유지하며, expiry와 탭 visibility 복귀 때 API-010을 재조회한다.
- `DOWNLOAD_AUDIO`가 허용되면 API-010으로 상태를 최신화한 뒤 API-021 download route를 browser-native navigation/link로 연다. Backend `Content-Disposition: attachment`가 private object를 stream해 기기에 내려보내며 FE에서 Audio를 Blob이나 application state에 올리지 않는다. 응답이 완료된 뒤 화면으로 돌아오면 “기기에 저장했어요” 확인을 요청한다.
- 사용자가 저장 완료를 확인하면 API-022 `{audioDisposition:"DOWNLOADED"}`를 호출한다. 다운로드하지 못했으면 실패 화면으로 돌아가 다시 시도하거나 명시적인 실패 종료를 고른다. Audio를 보관하지 않기로 선택한 경우 API-022 `{audioDisposition:"DISCARDED"}`를 호출한다.
- API-022가 성공하면 `COMPLETED_WITH_WARNINGS` 및 저장된 실패 Meeting URL을 보여준다. Email/Slack을 보내지 않는다는 설명을 제공하고 공유 action은 보여주지 않는다.
- API-010에서 `DOWNLOAD_AUDIO`/`RETRY_PROCESSING`이 사라지면 해당 버튼을 즉시 숨기고 만료/정리 상태를 설명한다. `FINALIZE_PROCESSING_FAILURE`가 허용되면 Audio disposition `DISCARDED`로 종료한다.
- Document finalization 실패에는 raw Provider 오류 대신 안전한 저장 실패 안내와 API-010 재확인/`FINALIZE_PROCESSING_FAILURE` 재시도 경로를 표시한다. 동일 사용자 finalize 재전송은 동일 `Idempotency-Key`; 새 finalize 시도는 새 key를 사용한다.
- `COMPLETED_WITH_WARNINGS`는 실패 종료 결과만 표시하고, `SESSION_NOT_FOUND`는 서버 Session이 유실/만료되었으며 재시도/다운로드를 할 수 없다는 안내와 새 회의 시작 경로를 제공한다.

## 접근성 및 모바일

- 실패 상태는 색상뿐 아니라 아이콘/제목/문장으로 구분한다. 상태 변화는 한 번만 `aria-live="polite"`로 알리고 초 단위 countdown은 반복 낭독하지 않는다.
- 모든 action은 keyboard/focus 순서가 있고 최소 44×44 CSS px touch target, 명확한 접근 가능한 이름, 진행 중/비활성 사유를 제공한다.
- Processing 중 Bottom Navigation을 숨긴다. `prefers-reduced-motion`에서는 단계 애니메이션을 줄이고 의미 있는 text/status는 유지한다.
- 최소 360px viewport와 iOS/Android safe area에서 버튼·failure copy가 가로로 넘치지 않는다.

## 수용 기준

- 실패 stage와 일반 사용자 오류 안내가 올바르게 대응하고 민감/raw error를 노출하지 않는다.
- `allowedActions`와 일치하는 button만 보이며 `PROCESSING_FAILED` retryability, Audio availability, expiry 별 조합을 처리한다.
- API-020은 새 attempt key/최신 version으로 한 번만 실행되고, 성공/실패 응답 뒤 최신 API-010 state로 복귀한다.
- API-021은 browser-native attachment download를 사용하고 FE가 Audio bytes를 Blob/global state/log에 저장하지 않는다.
- `DOWNLOADED`는 사용자의 저장 확인 뒤에만 제출되고, `DISCARDED`는 명시 선택 뒤에만 제출된다.
- API-022 성공 화면은 failure document link만 제공하고 Email/Slack/share UI를 노출하지 않는다.
- 만료/Backend restart state가 API-010으로 새로 반영되며 FE local timer만으로 action을 허용하지 않는다.
- duplicate clicks, POST timeout, 409/412, `AUDIO_NOT_AVAILABLE`, `DOCUMENT_FAILED`, `SESSION_NOT_FOUND`, route unmount/visibility 복귀가 안전하다.
- 360px, keyboard, screen reader, reduced-motion 및 44px target 기준을 만족한다.

## 의존성과 결정

- Backend/API 선행: TASK-017.02 Issue [#61](https://github.com/donghyunlee-dev/meeting-automation/issues/61)
- 처리 polling foundation: TASK-006.06 #32, TASK-006.07 #33
- 사용자가 승인한 결정: 명시 retry, 마지막 변환 실패 기준 24시간 Audio 보존, 사용자 다운로드/실패 종료, 실패 Email/Slack 전달 금지.
- 사용자가 승인한 private object storage, failure 후 최대 24시간 보존, 재시도·다운로드 동작은 TASK-017.02의 API 계약을 따른다. Backend restart 뒤 Session 재개 제한은 유지한다.

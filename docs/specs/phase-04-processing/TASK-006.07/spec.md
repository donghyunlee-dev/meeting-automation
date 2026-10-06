# Processing 화면과 상태 조회 연결

> **계약 갱신:** 이 패키지가 작성될 당시 processing retry API와 retry button은 없었다. 사용자가 승인한 변환 retry, Audio download, 실패 종료 흐름은 [TASK-017.02](../../phase-07-security/TASK-017.02/spec.md) Issue #61과 후속 TASK-017.03이 해당 기준을 대체한다. 조회 polling, API-010 기반 상태 연결, provider 오류 원문 비노출은 계속 유효하다.

## 목표

Processing 화면을 API-010 공통 `{data}` 응답에 연결한다. Backend가 보고하는 단계와 진행률을 보여주고, 처리가 `REVIEW`에 도달하면 해당 Session의 Review 화면으로 이동하며 실패 시 안전한 안내를 제공한다. PRD v1.7.0 (2026-10-05), `SCR-005`, `API-010`, `TASK-006.07`을 구체화한다. Issue [#33](https://github.com/donghyunlee-dev/meeting-automation/issues/33).

## 범위

- API-009 처리 시작 성공 후 Session ID를 기준으로 API-010 polling 시작
- API-010 공통 `{data}` envelope, `sessionId`, `version`, `status`, `processing.stage`, `processing.progressPercent` 소비
- `AUDIO_ASSEMBLY`, `TRANSCRIPTION`, `DIARIZATION`을 화면의 업로드, 음성 변환, 화자 분석 단계에 매핑한다. `status=PROCESSING`, `stage=DIARIZATION`, `progressPercent=100`이면 Minutes 생성 단계가 진행 중임을 표시하고, `REVIEW`의 `DRAFT_READY`는 완료 snapshot으로 취급한다.
- 대기·현재·완료·실패 단계 표시, 명시적 진행률, 기다림 안내 및 Bottom Navigation 숨김
- `REVIEW` 유효 응답 이후 같은 Session의 Review route로 이동
- `PROCESSING_FAILED`, 조회 네트워크 오류, `SESSION_NOT_FOUND`의 안전한 안내 및 복구 경로
- polling 취소, 제한된 exponential backoff 및 화면 이탈/중복 요청 방지

## 비범위

Processing API/Backend pipeline, Review 화면 구현, 처리 재시작 API 또는 자동 pipeline 재시도, Session 생명주기 변경, provider 연결 상태 조회, 참가자 roster 기능은 포함하지 않는다.

## 화면/응답 계약

- API 응답을 공통 `{data}` envelope에서 읽고 API error는 공통 오류 envelope로 분기한다. API-010의 필수 common fields는 `sessionId`, `version`, `status`, `processing:{stage,progressPercent}`다.
- Processing 단계명과 진행 상태는 서버의 현재 snapshot으로부터 계산한다. `progressPercent`가 없거나 범위를 벗어나면 임의의 수치를 만들지 않고 단계 설명만 표시한다. 알 수 없는 stage에는 안전한 일반 처리 문구를 표시한다.
- Review 이전에는 `speakers`, `transcript`, `minutes` 등 부분 결과를 UI에서 읽거나 보여주지 않는다. `status=REVIEW`이고 응답 `sessionId`가 현재 route Session과 일치할 때만 `/meetings/{sessionId}/review`로 이동한다.
- `PROCESSING_FAILED`에서는 `processing.stage`에 해당하는 사용자용 실패 문구와 API-010이 허용한 retry/download/finalize action을 표시한다. 오류 원문, provider response, Audio bytes, Transcript를 표시하지 않는다. pipeline retry는 API-020을 사용하며 API-009 반복 호출은 하지 않는다. 일시적 조회 오류는 polling backoff로 재시도한다.
- `SESSION_NOT_FOUND`는 처리가 만료되었거나 서버에서 사용할 수 없다는 안내와 새 회의를 시작할 수 있는 경로를 제공한다. 해당 Session을 자동 재생성하지 않는다.
- API 상태를 polling하는 동안 Bottom Navigation을 숨긴다. 색상만으로 단계를 구분하지 않고, 단계명/아이콘/텍스트를 함께 제공하며 `prefers-reduced-motion`에서 진행 애니메이션을 줄인다.

## polling 정책

첫 API-010 조회는 API-009 `202` 응답 뒤 즉시 수행한다. 아직 처리 중이면 다음 조회를 1초 후 시작하고, 오류가 연속될 경우 2초, 4초, 8초로 늘려 최대 10초로 제한한다. 유효한 응답을 받으면 오류 backoff를 초기화한다. stage/progress 변경 시 화면을 갱신하되 polling 주기는 1초보다 짧아지지 않는다. 동시에 하나의 조회만 실행한다. `PROCESSING_FAILED`에서는 잦은 polling을 멈추고 `audioExpiresAt` 직후 한 번 재조회하는 만료 timer를 유지한다. 사용자 action 완료 때는 즉시 다시 조회한다. `REVIEW`, `COMPLETED_WITH_WARNINGS`, `SESSION_NOT_FOUND`, route 이탈/컴포넌트 unmount에서 모든 timer와 in-flight 요청을 취소한다. 탭 복귀 시 상태를 즉시 재조회한다.

## 수용 기준

- API-010 snapshot의 stage/status/progress와 stepper가 일치하고 진행률이 접근 가능한 텍스트/진행 요소로 노출된다.
- Review 전에는 partial data가 화면에 없고, 일치하는 Session ID의 `REVIEW` 응답에서만 Review로 한 번 이동한다.
- 실패 stage와 조회 오류는 안전한 한국어 안내로 구분되며 민감한 응답 내용은 표시되지 않는다.
- 처리 중 polling은 제한된 backoff, 단일 in-flight 요청, terminal/unmount 취소를 지킨다.
- Bottom Navigation은 숨겨지고 reduced-motion 환경에서도 핵심 단계/진행 정보를 확인할 수 있다.

## 결정 및 전제

API-010이 Processing 화면의 유일한 상태/결과 source다. 성공 응답의 `status=REVIEW`와 route Session ID 일치를 확인한 뒤에만 이동한다. API-009는 동일 key의 반복 요청에 기존 작업을 반환하고 이 TASK에는 재시작 계약이 없으므로, API-010 조회 재시도와 pipeline 재시도는 서로 구분한다. polling 기본 간격은 API 계약이 고정하지 않으므로 위의 bounded client policy를 사용한다.

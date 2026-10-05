# Minutes 재생성 API 구현 계획

## 기준과 선행 작업

- PRD v1.7.0 (2026-10-05): `DEC-011`, `DEC-012`, `FR-013`, `FR-014`, `API-010`, `API-013`
- 선행 TASK-008.03 Issue [#39](https://github.com/donghyunlee-dev/meeting-automation/issues/39): versioned Review snapshot과 저장 후 API-010 재조회
- TASK-006.05 Issue [#31](https://github.com/donghyunlee-dev/meeting-automation/issues/31): 기존 Transcript 기반 Minutes generation 및 StructuredMinutes 규칙
- TASK-006.06 Issue [#32](https://github.com/donghyunlee-dev/meeting-automation/issues/32): API-010 응답, 상태별 Review data 및 일반 처리 실패
- 결정 TASK-009.01 Issue [#40](https://github.com/donghyunlee-dev/meeting-automation/issues/40): 실패 rollback 후 Review 복귀 선택

## 소유 경계

- Web/API adapter: regeneration request DTO, `If-Match`, `Idempotency-Key`, `{data}` 202 response 및 공통 오류 매핑
- Application: Review snapshot 검증, immutable regeneration command 생성, idempotent acceptance, 작업 완료/실패 orchestration
- Domain: regeneration operation identity, backup된 StructuredMinutes/provenance, success/failure 전이와 version 경합 검증
- Provider port: 기존 `MinutesGenerationProvider`를 재사용; 새로운 provider SDK/domain 의존성은 추가하지 않는다.
- Session repository/store: acceptance와 terminal commit/rollback을 atomic하게 수행하고 이전 Minutes 전체를 memory-only Session에 보관
- API-010 query adapter: optional `lastOperation` metadata와 Review snapshot을 한 version에서 읽는다.
- Error/observability: 고정 safe code, session/trace/stage/outcome만 기록; 사용자 콘텐츠와 provider 원문 제외

## 구현 순서

1. 기존 Session mutation, idempotency 저장, generation port, Template catalog, API-010 DTO 및 error mapper 경계를 확인한다. 결과: 새 도메인 필드와 재사용할 서비스 경계가 코드 위치/타입 기준으로 정리된다.
2. 먼저 실패/성공의 domain 및 API contract tests를 작성한다. 결과: 복구 전이는 이전 Minutes/provenance를 보존하며 stale operation은 mutation하지 않는 RED 증거가 있다.
3. regeneration command를 만들고 REVIEW/action/version/Template/idempotency 입력 검증을 구현한다. 결과: 유효 요청만 202 수락되고 거절은 Session을 변경하지 않는다.
4. API-010에 optional `lastOperation` serialization을 추가한다. 결과: 기존 common fields는 유지되고 terminal regeneration outcome이 같은 snapshot version으로 읽힌다.
5. Provider success 경로에서 결과 검증, template provenance 강제, 원자 Minutes 저장 및 REVIEW 완료를 연결한다. 결과: 유효 결과 전체만 저장되고 중간/잘못된 결과는 보이지 않는다.
6. failure 경로에서 backup Minutes 전체와 Template provenance를 원자 복원하고 REVIEW 전이/안전 outcome을 기록한다. 결과: 실패 뒤 API-010 read에서 편집본이 복구되고 action이 다시 계산된다.
7. concurrency, idempotency replay/conflict, 빈 Transcript, provider/schema/storage 실패 회귀를 추가한다. 결과: 동일 operation 중복 실행, stale rollback, Audio/STT/Diarization 호출이 발생하지 않는다.
8. Backend automated tests와 API-010/API-013 수동 QA를 실행하고 Issue #40에 결과를 기록한다. 결과: 수용 기준별 실행 증거와 변경 파일 요약이 남는다.

## 동작 순서

HTTP/API 검증 → versioned Review snapshot 및 idempotency reservation → atomic PROCESSING/DRAFT_REGENERATION acceptance와 backup → provider command → structured output validation → atomic 성공 commit 또는 backup restore + REVIEW → API-010 versioned read. FE는 별도 TASK-009.02에서 202를 받은 뒤 API-010을 poll하므로 Backend terminal read contract를 먼저 확정한다.

## 변경 후보

- `src/main/java/.../adapter/in/web/` API-013 controller/DTO
- `src/main/java/.../application/` regeneration use case 및 operation orchestration
- `src/main/java/.../domain/` Session regeneration lifecycle와 snapshot/restore
- `src/main/java/.../adapter/out/ai/` 기존 Minutes generation port/adapter 재사용 연결
- `src/main/java/.../adapter/out/session/` atomic versioned mutation/restore
- API-010 response DTO/serializer와 공통 exception mapper
- 관련 Backend unit, application, API integration tests

정확한 패키지/파일은 구현 시 repository 현 구조에서 확인한다. 새 추상 계층은 기존 port 경계가 재사용 불가능한 경우에만 추가한다.

## 검증 방법

Backend: `./gradlew test`, `./gradlew clean build`. API integration에서 202 acceptance, API-010 polling snapshot, 성공/실패 terminal state, 400/404/409/412 응답과 redaction을 확인한다. Audio/STT/Diarization adapter 호출 spy는 모두 0이어야 한다. 명령은 구현 저장소의 Gradle wrapper 위치에 맞춰 실행한다.

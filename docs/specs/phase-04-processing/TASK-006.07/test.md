> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# Processing 화면 및 polling 검증 계획

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| SCR-005-01 | API-009 `202`, 첫 API-010 응답은 `AUDIO_ASSEMBLY` | 즉시 조회 후 업로드 단계가 현재 단계로 보임 | Processing page test |
| SCR-005-02 | `TRANSCRIPTION`, `DIARIZATION` 진행, `DIARIZATION` 100%이면서 `PROCESSING`, 완료 `status=REVIEW` 및 `processing.stage=DRAFT_READY` | 완료/현재/대기 단계와 Minutes 생성/완료 문구가 API-010 snapshot과 일치 | stage mapper test |
| SCR-005-03 | 처리 응답의 progress 값 0/중간/100, 누락 또는 범위 밖 값 | 유효 값만 progress로 표시하고 잘못된 수치는 꾸며내지 않음 | progress accessibility test |
| SCR-005-04 | PROCESSING 응답에 fixture partial Transcript/Minutes 포함 | Review 데이터 미렌더링, Review route 미호출 | data gating test |
| SCR-005-05 | `status=REVIEW`, response `sessionId`가 route ID와 같음 | 같은 Session Review로 정확히 한 번 이동 | navigation integration test |
| SCR-005-05a | top-level `status=DRAFT_READY` | API-010 응답을 invalid로 처리하고 Review로 이동하지 않음 | API mapper test |
| SCR-005-06 | `status=REVIEW`이나 response `sessionId`가 route ID와 다름 | 이동하지 않고 안전 오류를 표시 | navigation guard test |
| SCR-005-07 | `PROCESSING_FAILED` 각 stage와 현재 API-010 snapshot (`allowedActions:[]`, `audioExpiresAt:null`) | 안전한 실패 stage 안내만 표시; recovery action/expiry timer 없음, provider/error 원문 미표시, API-009 재호출 없음 | failure view test; recovery action flow in TASK-017.03 |
| SCR-005-08 | API-010 일시적 네트워크 오류 연속 발생 | 1/2/4/8초, 최대 10초 backoff로 조회하고 응답 성공 시 초기화 | polling scheduler test |
| SCR-005-09 | 조회가 지연되는 동안 timer tick 발생 | in-flight GET은 하나만 유지 | polling concurrency test |
| SCR-005-10 | `SESSION_NOT_FOUND` | terminal 안내 및 새 회의 경로, Session 자동 생성 없음 | missing session test |
| SCR-005-11 | PROCESSING_FAILED (`audioExpiresAt:null`), REVIEW/COMPLETED_WITH_WARNINGS/404 또는 route unmount | failed response에 expiry timer 없음; terminal/unmount 때 polling timer와 in-flight request 취소 | lifecycle cleanup test; expiry lifecycle in TASK-017.03 |
| SCR-005-12 | reduced-motion 선호 및 Bottom Navigation layout | 동작 애니메이션 감소, 단계 text 유지, Bottom Navigation 숨김 | accessibility/layout test |

`frontend/package.json` defines `test` as Vitest. Developer/QA runs only the changed behavior's test files or named tests (for example, `npm test -- --run <focused-test-file>` from `frontend/`); do not routinely run the full Frontend suite. Record the exact selectors used. Keep CI unchanged.

## 통합 및 수동 QA

- API-009 처리 시작 뒤 API-010 stage 갱신을 발생시켜 네 단계 표시와 polling 간격을 확인한다.
- Review 이전에 partial content가 표시되지 않고, `REVIEW` 완료 뒤 올바른 Session의 Review 화면에 한 번 진입하는지 확인한다.
- `PROCESSING_FAILED`, 일시적 조회 실패, `SESSION_NOT_FOUND`에서 안내가 구분되고 raw API/provider 오류가 노출되지 않는지 확인한다.
- 브라우저 route 이탈 및 탭 background/복귀에서 polling 중복 또는 잔존 timer가 없는지 확인한다.
- 키보드 탐색, screen reader progress semantics, 색상 외 단계 표시, reduced-motion, Bottom Navigation 숨김을 확인한다.

## 릴리스 검증

- 배포 전 API-010 common response와 Frontend stage mapper의 호환성을 확인한다.
- API-010 필드 불일치 시 안전한 오류 화면이 나타나며 Session/응답 원문이 로그나 사용자 메시지에 노출되지 않는지 확인한다.

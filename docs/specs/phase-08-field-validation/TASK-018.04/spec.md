# 녹음 호환성 조정과 현장 재검증

## 목표

TASK-018.01~018.03의 실제 Evidence에서 재현된 MIME, Recorder/Chunk, IndexedDB, upload/recovery 또는 사용자 안내 결함을 기존 구조 안에서 수정하고 실패 시나리오를 재검증한다. 재현 증거가 없는 가정상 문제는 구현하지 않는다. 실제 실행된 기기·OS·브라우저·MIME 조합으로 검증 지원 범위와 제한을 정리한다.

PRD v1.8.1 (2026-10-06), `TASK-018.04`, `NFR-001~003`, `NFR-007`, `FR-005`, `API-006~009`, PRD Section 21을 구체화한다. Issue [#68](https://github.com/donghyunlee-dev/meeting-automation/issues/68).

## 범위

- 선행 Android Chrome, iOS Safari 및 Chunk recovery Evidence의 재현 가능한 실패만 결함 원장으로 모아 우선순위/FE·BE·통합 경계를 확정
- 결함마다 regression test를 먼저 작성해 실패를 재현하고, 최소 수정 뒤 단위/계약/통합 회귀 실행
- 영향받는 기기/브라우저에서 기존 run과 같은 입력·fixture·정책으로 현장 재검증
- API-006 `acceptedMimeTypes`/`maxChunkBytes` 정책, API-007 body/hash/ACK, API-008 received sequence, API-009 handoff 계약 및 DEC-020 저장 규칙을 보존
- 기기 모델, OS/browser 버전, 실행 모드, 실제 MIME, 시나리오 결과가 뒷받침하는 범위만 지원 matrix에 표기
- 미해결 결함·재현 한계·미검증 환경·추가 Issue와 `docs/evidence/TASK-018.04.md`를 기록

## 비범위

- TASK-018.01~.03에서 재현되지 않은 상상 결함의 예방적 코드 수정
- 새 네이티브 앱, background recording 보장, 새 DB/object store/queue/아키텍처 도입
- PRD/API 계약 변경이나 지원 브라우저 확대를 근거 없이 약속
- 회의실 STT/diarization 품질 튜닝 (`TASK-019`)
- 실제 회의/통화 audio, Transcript, credential을 test fixture/Evidence로 사용
- 별도의 대규모 브라우저 fleet/cross-version 호환 인증. matrix는 이번 task에서 직접 검증한 조합만 포함

## 결함 처리 원칙

- 입력은 TASK-018.01 `docs/evidence/TASK-018.01.md`, TASK-018.02 `docs/evidence/TASK-018.02.md`, TASK-018.03 `docs/evidence/TASK-018.03.md`의 재현 run/Issue만 사용한다. 해당 Evidence가 준비되지 않았으면 수정 대상을 가정하지 않고 선행 Issue를 확인해 blocking fact를 기록한다.
- 각 항목에는 재현 단계, 기대/실제값, 브라우저 조합, 영향, 관련 계약, 우선순위, owner(FE/BE/integration), regression case, 결과를 남긴다. 입력 현상과 추정 원인은 분리한다.
- 고장 fixture를 같은 build에서 한 번 재현한 뒤 변경 전에 실패 regression test를 먼저 만든다. API/DB/Provider 경계 계약에 변화가 필요하면 이번 task에서 우회하지 않는다.
- 기존 정책 안에서 고칠 수 없거나 저장소/세션 영속성/native capability/PRD 변경이 필요한 문제는 ADR/PRD 및 별도 설계 Issue로 분리한다. 이 범위에서의 수정 완료로 표시하지 않는다.
- 선행 QA에서 차이가 없으면 코드 수정을 억지로 만들지 않는다. 지원 matrix를 직접 검증된 조합으로 정리하고 회귀 suite를 실행한다.

## 지원 범위 matrix

Evidence에 기기 모델, OS/browser build, 실행 모드(Safari tab/Chrome tab 등), API `acceptedMimeTypes`, 실제 `MediaRecorder.mimeType`, Chunk 크기 정책과 run 결과를 기록한다. 각 조합은 `검증됨`, `실패`, `미검증` 중 하나로 기술한다.

- `검증됨`은 필수 baseline/종료/upload 및 해당 조합에서 요구된 interruption/recovery 행이 모두 pass한 명시적 조합에만 사용한다.
- 실제로 실행한 버전만 확정한다. 실행하지 않은 OS/browser 버전, 기기 모델 또는 브라우저 계열로 범위를 추정/보간하지 않는다.
- `실패` 조합의 녹음 시작을 막거나 사용자에게 제한을 보여줄 방법은 기존 `MediaRecorder`/API-006 capability/error boundary 안에서 구현한다. 새로운 allowlist/API를 만들지 않는다.
- 정책 계약 밖 MIME은 선택하지 않는다. MIME 지원 선언, recorder 생성, 실제 chunk, API-007 수락을 각각 확인하고 어느 단계에서 실패했는지 기록한다.

## 수용 기준

- 선행 Evidence의 각 재현 결함이 원인/영향/계약/owner/수정 범위/테스트와 연결되고, 추측성 결함을 구현하지 않는다.
- 선택된 코드 변경은 실패 재현 regression test를 먼저 추가하고 최소 구현 및 관계 회귀로 통과를 증명한다.
- API-006~009와 API-007/008 sequence·hash·byte, API-009 handoff 순서 및 DEC-020 정리가 비회귀다.
- 변경된 시나리오를 영향을 받는 Android Chrome/iOS Safari 기기 조합에서 원 조건으로 재실행하고 최초/수정 후 결과를 함께 보존한다.
- 현장 재검증 후에도 실패하면 pass로 고치지 않고, 사용자 안내와 안전한 종료/재시도 가능성, 미해결 Issue를 보고한다.
- 지원 matrix는 실제 실행된 조합만 `검증됨`/`실패`로 표시하고 다른 조합을 `미검증`으로 남긴다.
- Evidence에 Audio/Transcript/통화내용/Secret/개인정보/public storage URL이 없고 QA Audio/object cleanup이 확인된다.
- 해결 불가 구조 변경 항목은 신규 ADR/PRD 결정 Issue에 연결되며 TASK-018.04의 해결 완료로 오인되지 않는다.

## 관련 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)
- [Android 현장 결과 입력](../TASK-018.01/spec.md)
- [iOS 현장 결과 입력](../TASK-018.02/spec.md)
- [Chunk 복구 결과 입력](../TASK-018.03/spec.md)
- [MediaRecorder/MIME 계약](../../phase-03-recording/TASK-005.02/spec.md)
- [API-007 upload 계약](../../phase-03-recording/TASK-005.04/spec.md)
- [IndexedDB 저장 계약](../../phase-03-recording/TASK-005.03/spec.md)
- [순차 upload/retry 계약](../../phase-03-recording/TASK-005.06/spec.md)

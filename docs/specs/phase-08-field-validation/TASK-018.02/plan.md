# 검증 계획

## 선행 조건

- TASK-005.08 Issue #26: recorder stop, final Chunk persist, upload flush, API-009, Processing route
- TASK-017.03 Issue #62: API-010 상태와 오류별 사용자 복구 화면 계약
- TASK-018.01 Issue #65 및 실제 결과: Android Chrome 비교 기준
- PRD v1.8.1 (2026-10-06), `NFR-001~003`, `NFR-007`, Section 21
- 실제 iPhone, Safari, HTTPS 비운영 배포, QA 전용 Session/참석자, 분리 가능한 object prefix, 비용 없는 mock processing 설정

## 소유권과 경계

| 책임 | 소유자 | 산출 결과 |
|---|---|---|
| 기기 조합과 안전한 Session | QA | 모델/OS/Safari/app/backend/network 상태 및 테스트 대상 식별 |
| MIME/MediaRecorder/MediaStream | FE + QA | MIME 후보/실제 recorder MIME, 권한 및 track/상태 전이 관찰 |
| Chunk 저장/업로드 | FE + QA | IndexedDB sequence/byte와 API-007/008 결과 집계 |
| 종료/Processing 연결 | FE/BE + QA | final Chunk 이후 upload flush, API-009 응답, route ID 검증 |
| 보안과 Evidence | QA | 비민감 실행 Evidence, 수신자 없는 설정, REVIEW cleanup 또는 실패 object 삭제 |

이 작업은 QA 결과와 문서만 산출한다. UI/API 구현을 먼저 변경하지 않는다. 기존 recorder/upload/API 계약을 검증 대상으로 고정하고, 관찰 중 기능 결함이 나오면 TASK-018.04 또는 별도 Issue로 연결해 이번 작업의 범위를 분리한다.

## 실행 순서

- iPhone 모델, OS/Safari 빌드, 앱 commit, HTTPS/backend/API 버전, network, 마이크 경로, power mode, MIME 후보 및 upload 제한을 기록한다. PWA와 Safari 일반 탭을 분리한다.
- 앱이 사용하는 실제 도메인·HTTPS에서 마이크 권한, storage availability/quota, `MediaRecorder` 존재, MIME 후보별 `isTypeSupported()`와 짧은 실제 recorder 생성/종료를 점검한다. API 응답과 실제 성공 여부는 별도 기록한다.
- Android TASK-018.01과 동일한 고정 테스트 fixture, Session 구성, upload policy 및 mock provider 설정을 사용한다. 환경이 다르면 비교 행에 차이를 표시한다.
- 화면을 켠 30분·60분 baseline을 별도 수행한다. 두 run 모두 동일한 명시 조건으로 사용자/브라우저가 중간에 종료되지 않았는지 관찰한다.
- 각각 별도 30분 run으로 screen lock 5분, app switch 5분, 통제된 수신 전화 응답 60초, uplink outage 60초를 실행한다. 자동 네트워크 전환/통신 경로가 있으면 기록하며 완전히 분리할 수 없으면 실행을 무효화하지 말고 실제 경로를 정확히 기술한다.
- 각 상태 변화에서 visibility, recorder/track 이벤트, 조작 가능 여부, IndexedDB pending count/sequence/bytes, upload/API 오류의 timestamp만 기록한다. 원시 로그와 Audio는 보관하지 않는다.
- 정상 종료 계약을 이용해 stop과 final dataavailable을 확인하고, persist 완료 후 upload flush/API-008 대조/API-009/Processing route Session ID를 검증한다. 업로드 pending이면 API-009 처리 성공으로 표시하지 않는다.
- non-production mock processing으로 REVIEW까지 완료하고 Audio cleanup을 확인한다. 완료 불가인 실패 Session은 QA prefix object를 삭제하고 count/result만 기록한다.
- 실패 행은 기기·브라우저·앱·조건을 고정해 1회 재현한다. 최초와 재현 결과를 둘 다 기록하고 Android 비교에서는 관찰과 원인 해석을 분리한다.
- `docs/evidence/TASK-018.02.md`에 무민감 결과를 기록하고, 금지 데이터 검사와 object cleanup을 확인한다. 발견 사항은 TASK-018.03/018.04 또는 결함 Issue로 연결한다.

## 구현 경계와 후속 연결

- 기록 필드는 `spec.md` 수용 기준 및 `test.md` 행렬을 따른다. 새로운 telemetry, 앱 기능, 자동화 명령을 본 작업에서 만들지 않는다.
- 폼/Recorder 동작 변경이 필요하면 TASK-018.04에서 수용 기준과 지원 브라우저 정책을 함께 수정한다.
- 미전송 Chunk의 sequence별 재조정, 중복 재전송 및 손실 복원은 TASK-018.03가 소유한다. 이 작업은 실행 결과의 예상/수신 총계와 증거를 남기되 상세 recovery 로직을 검증했다고 주장하지 않는다.
- 실패 Audio는 TASK-017.02/017.03 계약을 따른다. 실기기 Evidence에 보관하거나 브라우저에서 내려받아 개인 파일로 제출하지 않는다.

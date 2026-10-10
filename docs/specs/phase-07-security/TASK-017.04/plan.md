# 구현 계획

> 📌 v1.9.0 변경 계약: 최초 연결·양방향 변경·부분 실패·source 보존·설정 암호화·Secret 웹 저장소 미보관을 TASK-022와 연계해 검증한다. password 입력 요청을 금지하는 것으로 오해하지 않는다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

## 선행 조건

- TASK-017.01 Issue #60: Backend Secret 경계, DTO/log redaction, synthetic canary 정책
- TASK-017.02 Issue #61: private object storage, API-007/010/020~022, 24시간 expiry 및 삭제 계약
- TASK-017.03 Issue #62: 사용자 retry/download/failure-finalize 화면 및 Email/Slack 미전달
- PRD v1.8.1, DEC-017/020, FR-027/028/030, NFR-004~006

## 검증 대상 및 담당

| 경계 | 담당 | 검증 결과 |
|---|---|---|
| Repository/config scanner | QA + FE/BE | tracked source 및 정해진 build/sample만 검사하고 개인 환경·운영 secret 경로는 제외 |
| FE artifact/public config | FE + QA | 공개 환경 allowlist와 synthetic Secret 부재 |
| Backend API/error/log/incident | BE + QA | DTO allowlist, 오류 안전성, 구조화 로그 및 Admin Slack payload redaction |
| Audio object storage | BE + QA | private access, Backend-only credential, opaque key, expiry, deletion, 재시작 뒤 cleanup |
| 실패 Document 및 전달 | BE + QA | safe failure metadata만 저장하고 attendee Email/Slack 호출이 0건 |
| Evidence | QA | 재현 절차와 pass/fail만 기록하고 canary/콘텐츠/credential 값은 제거 |

## 변경/생성 경계

- `docs/product/PRD.md`: TASK-017.04 검증 범위와 Evidence 경로
- `docs/specs/phase-07-security/TASK-017.04/`: QA SDD 네 문서
- 테스트 구현 시 기존 FE/BE test source, env sample checker, secret boundary tests 및 필요한 storage/document/delivery adapter test fixture를 확장한다. 실제 경로와 명령은 구현 시작 때 저장소 manifest에서 확인한다.
- `docs/evidence/TASK-017.04.md`: 결과 요약, 커맨드, 테스트 환경, sanitized artifact 식별 정보

## 실행 순서

1. 선행 세 TASK의 수용 기준과 현재 API/data/security contract를 검증 matrix로 연결한다. 보안 테스트가 기존 업무 콘텐츠 응답을 과도하게 차단하지 않도록 허용/금지 sink를 구분한다.
2. Canary scanner와 fixture의 출력 안전성 테스트를 먼저 작성한다. 제외 대상 경로는 검사기에 전달하지 않는 구조를 검증한다.
3. FE bundle, Backend DTO/error/log/admin payload 검사를 연결한다. 전용 synthetic values만 쓰고 assertion/report는 value-free로 만든다.
4. Private object storage fake/전용 QA prefix를 사용해 공개 GET 거부, Backend 경유 stream, key/credential 미노출, max-age expiry/delete 및 cleanup 재실행을 검증한다.
5. 정상 성공 Audio 삭제와 processing failure retry/download/finalize·만료 시나리오를 API 및 adapter interaction 수준에서 확인한다.
6. 실패 Document allowlist와 Email/Slack ports의 zero-interaction을 고정 fixture로 확인한다.
7. 실제 provider의 private access/HTTPS/encryption/retention 설정은 값이 아닌 설정 상태와 provider 근거만 확인한다. 운영 자료 대신 전용 non-production store를 사용한다.
8. 구현 저장소에서 실제 test/build/scan commands를 확인해 실행하고 sanitized Evidence를 기록한다. 이 설계 단계에서는 명령을 추측하거나 테스트를 실행하지 않는다.

## 통합 및 종료 기준

- 모든 acceptance criterion이 `test.md`의 자동 또는 수동 케이스에 연결된다.
- 기존 TASK-017.01 검증을 중복 실행하는 대신 필요한 회귀 케이스만 재사용하고, `.02/.03`의 Audio expiry/download/failure-delivery 계약까지 포함한다.
- 결함은 canary 값·실제 콘텐츠 없이 재현 가능한 별도 Issue로 보고하고, 관련 보안 점검은 수정·재검증 전까지 fail로 남긴다.
- `docs/evidence/TASK-017.04.md`에는 실행 commit, environment class, 실제 실행 명령, pass/fail 개수 및 제한사항을 기록한다. 원문 response/log/object key/fixture canary는 첨부하지 않는다.

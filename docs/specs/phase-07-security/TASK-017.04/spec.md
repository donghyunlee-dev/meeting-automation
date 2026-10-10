# 보안·개인정보 회귀 점검

> 📌 v1.9.0 변경 계약: 최초 연결·양방향 변경·부분 실패·source 보존·설정 암호화·Secret 웹 저장소 미보관을 TASK-022와 연계해 검증한다. password 입력 요청을 금지하는 것으로 오해하지 않는다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

## 목표

TASK-017.01~.03에서 정의한 비밀값, 회의 콘텐츠, 비공개 Audio 객체 저장소, 변환 실패 종료 계약을 자동·수동 회귀 검증한다. 운영 값이나 실제 회의 콘텐츠 없이 합성 canary fixture로 증거를 만들고, 결과물에서 민감값을 다시 노출하지 않는다.

PRD v1.8.1 (2026-10-06), `TASK-017.04`, `DEC-017`, `DEC-020`, `FR-027`, `FR-028`, `FR-030`, `NFR-004~006`, API-010/020~022를 구체화한다. Issue [#64](https://github.com/donghyunlee-dev/meeting-automation/issues/64), 선행 Issue는 TASK-017.01 #60, TASK-017.02 #61, TASK-017.03 #62다.

## 범위

- Frontend build artifact, Backend 설정/공개 DTO/API 오류에서 Backend Secret이 노출되지 않는지 검증
- 구조화 로그, 예외·Provider 오류, Admin Slack payload에서 Secret, Authorization, Audio, Transcript, Minutes, PII 원문이 제외되는지 검증
- Backend service credential만 private Audio object에 접근할 수 있고 Browser/API response에 storage credential·object key·public URL이 노출되지 않는지 검증
- 성공, 변환 실패, 재시도, 실패 종료, 만료, Backend 재시작 경계에서 Audio object의 접근·삭제와 실패 Document/email/Slack 억제를 검증
- 검사 입력·출력·Evidence에서 실제 개인 환경 파일과 실제 회의 데이터를 제외

## 비범위

- Secret rotation, 계정 권한 변경, 외부 침투 테스트 및 취약점 진단 계약
- 새 운영 저장소 또는 logging/secret scanning 제품 도입
- 실제 고객 녹음·Transcript·Provider Secret을 이용한 테스트
- Backend Session 복구, API-021 사용자 인증 체계 또는 TASK-017.02 수명주기 구현 변경
- 발견된 구현 결함 수정. 회귀 결함은 민감값 없는 재현정보와 함께 별도 수정 Issue로 등록

## 보안 검증 원칙

- 검사 fixture에는 구별 가능한 synthetic canary만 사용한다. Canary 값은 report, assertion message, screenshot, CI output에 쓰지 않는다. 실패 증거는 규칙 ID, fixture ID, 상태 코드, 파일 경로(민감하지 않은 tracked path), pass/fail만 기록한다.
- 대상은 Git 추적 파일, 제한된 build artifact, API 테스트 응답, 테스트에서 캡처한 로그/알림 payload, 전용 QA object prefix다. 개인 `.env*`, keyring, 배포 Secret store, 운영 로그·저장소·회의 문서는 열거나 dump하지 않는다.
- 성공 API에서 계약상 사용자에게 제공하는 Transcript/Minutes 화면 데이터와 오류 응답·로그·Admin Slack의 금지 노출을 구분한다. 이 task는 기존 Review API 데이터를 제거하지 않는다.
- Audio는 private object storage에 두고 Backend service 경로에서만 읽는다. Browser가 object store에 직접 접근하지 않는다. object store의 공개 접근 차단, HTTPS 전송 설정, provider가 지원하는 저장 시 암호화, 설정된 만료/삭제 정책을 비밀값 없이 확인한다.
- 앱이 관리하는 Audio object는 성공 Review 진입 때, 사용자 실패 종료 때, 또는 마지막 변환 실패 뒤 최대 24시간 만료 시 삭제되어야 한다. Provider가 별도로 보존하는 backup/snapshot의 보존 조건은 provider 설정·약관 근거를 확인하고 Evidence에 비민감하게 기록한다. 이를 앱의 24시간 object expiry와 혼동하지 않는다.
- 실패 Meeting Document에는 회의 metadata와 safe failure/disposition만 허용한다. 실패 경로에서 Email/Slack 전달 호출은 없어야 한다.

## 검증 대상 결과

- 정상: FE 산출물/API config/오류/log/incident payload에서 canary가 검출되지 않고, 검토 화면의 계약상 콘텐츠는 정상 동작한다.
- 변환 실패: storage credential과 object key가 비공개이며 만료 전 API-021만 유효한 Session action을 통해 Audio를 전달한다. API-010 만료 action이 제거되고 cleanup 이후 원본 object가 없다.
- 실패 종료: Meeting Document는 실패 요약과 disposition만 포함하고 Email/Slack recipient delivery는 0건이다.
- 검사 실패: 위험한 출력이나 검사기의 제외 범위 누락이 있으면 보안 점검을 통과 처리하지 않는다. 발견값은 출력 없이 fixture/rule ID만 보인다.

## 수용 기준

- FE production bundle 및 public API config에 Backend Secret canary가 없다.
- API DTO, 공통 error envelope, 일반 log, Admin Slack payload가 명시된 allowlist를 넘지 않는다.
- private object의 직접 공개 읽기와 Browser의 credential/key 접근이 거부되며 Backend API는 상태·만료 규칙을 적용한다.
- 성공·사용자 실패 종료·만료 뒤 앱 관리 Audio object가 삭제되고, 만료 cleanup은 Backend 재시작 및 반복 실행에도 안전하다.
- 실패 종료 문서는 허용된 metadata만 갖고 Email/Slack 전달은 생성되지 않는다.
- 운영 Secret/개인 환경 파일/실제 회의 콘텐츠에 접근하지 않으며 Evidence에 민감값을 기록하지 않는다.
- 자동·수동·배포 설정 검토 결과, 테스트 실행 환경 및 non-sensitive Evidence가 `docs/evidence/TASK-017.04.md`에 기록된다.

## 관련 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)
- TASK-017.01: [Secret 경계 및 로그 마스킹](../TASK-017.01/spec.md)
- TASK-017.02: [비공개 Audio 보존과 실패 종료](../TASK-017.02/spec.md)
- TASK-017.03: [처리 실패 재시도와 Audio 복구 화면](../TASK-017.03/spec.md)

# 검증 계획

## 자동화 검증

Frontend 구현 때 repository package manifest가 선언한 test runner로 component/API integration tests를 수행한다. 현 시점 repository에 frontend manifest가 없으므로 exact command는 구현 전 FE setup 결과로 확정한다. 설계 단계에서는 테스트를 실행하지 않는다.

| ID | 준비/입력 | 기대 결과 | 증거 |
|---|---|---|---|
| SHARE-01 | API-001 `provider:null/configured:false` | Settings 안내/link, Publish blocked, provider 자동 선택 없음 | AppConfig route/component test |
| SHARE-02 | Settings 이동 후 돌아오기 | Review draft 및 recipient/notification 선택 유지 | Router state test |
| SHARE-03 | 설정된 provider와 유효 Review | API-014 먼저 호출, API-015는 아직 미호출 | API interaction ordering test |
| SHARE-04 | API-014 422와 여러 `details.issues` path/code | 관련 mapping/Minutes field에 오류와 navigation 제공; API-015 0회 | Validation mapping test |
| SHARE-05 | API-014 400/404/409/412 | safe 안내 및 규칙별 Review 복구; Publish 0회 | Confirm error mapping test |
| SHARE-06 | API-014 200/new version | 해당 version을 If-Match로 API-015 1회 호출 | Confirm-to-publish flow test |
| SHARE-07 | Submit double click | API-014/API-015 중복 mutation 0건 | Single-flight component test |
| SHARE-07A | API-014 response 유실 뒤 API-010 status 확인 | REVIEW면 동일 confirm fingerprint reconcile; 이미 CONFIRMED면 최신 version으로 명시 interaction을 이어가고 중복 confirm 없음 | Confirm uncertainty reconciliation test |
| SHARE-08 | API-015 202 | `PUBLISHING` progress; saved message/link 미표시; API-010 polling 시작 | Publish progress test |
| SHARE-09 | API-010 계속 PUBLISHING | bounded backoff/pending UI; route leave 시 polling 취소 | Polling lifecycle test |
| SHARE-10 | API-010 lower version가 최신 상태 뒤 도착 | cache/status/document URL 이전 값으로 rollback 안 됨 | Stale response race test |
| SHARE-11 | API-010에 document reference와 https documentUrl | 현재 status가 저장 이후 delivery state여도 저장 완료 및 외부 원문 link 표시, 새 탭 보호 속성 적용 | Saved document view test |
| SHARE-12 | API-010 저장 성공이나 URL 누락/invalid scheme | success state는 유지하고 외부 link는 표시하지 않음; safe refresh 안내 | URL validation test |
| SHARE-13 | API-010 `DOCUMENT_FAILED`, `allowedActions`에 PUBLISH | safe failure와 retry 표시; 명시 클릭 시 latest version/new key로 API-015 1회 | Retry mutation test |
| SHARE-14 | DOCUMENT_FAILED이나 PUBLISH action 없음 | retry button 비활성/미표시 및 latest state 안내 | Action guard test |
| SHARE-15 | API-015 timeout/response 유실 | 새 key blind retry 없음; API-010 reconcile 뒤 authoritative state 반영 | Unknown result test |
| SHARE-16 | Document saved지만 Email/Notification pending | 문서 저장만 완료로 표시하고 채널 성공 주장 없음 | Boundary rendering test |
| SHARE-17 | API issue 및 async status announcement | field/live region, keyboard navigation, 360px viewport 사용 가능 | Accessibility/browser test |

## 통합 및 수동 QA

- Review의 Speaker mapping과 Minutes 오류를 남기고 SCR-007 버튼을 눌러 issue 위치 안내와 API-015 미호출을 확인한다.
- 모든 Review 입력을 보완하고 Submit하여 Confirm response version과 Publish If-Match version이 일치하는지 확인한다.
- Document processing 동안 진행 표시가 유지되고 API-010 success에서 올바른 문서가 새 탭으로 열리는지 확인한다.
- Document 실패 fixture에서 사용자 재시도 후 새로운 idempotency key가 쓰이고 동일 Session 기존 문서를 중복 생성하지 않는지 확인한다.
- provider null/unconfigured에서 Settings 이동 및 돌아오기 이후 선택 상태/draft 보존을 확인한다.
- keyboard-only, screen reader live update, focus order, mobile width를 확인하고 Email/Notification이 끝나지 않았을 때 완료로 오인하지 않는지 확인한다.

## 릴리스 증거

실제 FE test/lint/build 명령과 브라우저 QA 결과를 Issue #45에 기록한다. 외부 Confluence에 문서를 생성하는 검증은 승인된 test space와 자격 증명을 사용하고 production side effect를 발생시키지 않는다.

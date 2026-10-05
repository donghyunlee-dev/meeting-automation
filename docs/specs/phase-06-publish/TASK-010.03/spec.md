# Publish 검증 연결과 저장 결과 표시

## 목표

SCR-007에서 Review를 서버 검증 후 확정하고 API-015 Publish를 시작한다. API-010에서 저장 완료/실패를 조회해 문서 URL을 제공하고 실패 재시도를 연결한다. PRD v1.7.0 (2026-10-05), `SCR-007`, `API-010`, `API-014`, `API-015`를 구체화한다. Issue [#45](https://github.com/donghyunlee-dev/meeting-automation/issues/45).

## 범위와 소유 경계

- Share 화면 입력·Document provider 설정 안내와 Settings 복귀 경로
- API-014 검증 결과 및 성공 후 API-015 접수 순서
- API-010 기반 Publish 진행/저장 완료/문서 실패 화면 상태
- 저장된 `document.documentUrl`을 안전한 외부 링크로 표시
- 명시적 문서 재시도와 중복 click/network uncertainty 방지

Email 수신자 결과 UI는 TASK-011.02, Notification 결과 UI는 TASK-012.02, 전체 Complete 및 delivery 재시도는 TASK-013.02가 소유한다. 이 화면은 각각의 채널 결과를 성공으로 추정하지 않는다.

## Provider 설정 및 Review 검증

- 화면 진입/재활성화 시 API-001 설정을 사용한다. `document.provider=null` 또는 `configured=false`면 provider를 자동 선택하지 않고 Publish를 막으며 SCR-011 Settings 링크를 표시한다. 이동 전 Review snapshot/draft/선택값을 보존하고 돌아왔을 때 복원한다.
- 설정된 Provider 이름은 응답 값으로 표시한다. 화면에 Secret/credential 입력값을 표시하거나 로깅하지 않는다.
- `저장하고 공유`는 API-014 `POST /meeting-sessions/{sessionId}/confirm`을 `If-Match` 현재 Review version과 새 `Idempotency-Key`로 먼저 호출한다.
- 200이면 반환된 새 version으로 API-015를 호출한다. Confirm 422 `REVIEW_VALIDATION_FAILED`이면 `details.issues`의 path/code를 Speaker mapping 또는 Minutes 입력 위치와 연결해 표시하고 문제 위치로 이동시킨다. Confirm 실패 때 API-015는 호출하지 않는다.
- 사용자가 지정한 Email participant IDs 및 Notification 선택만 API-015 body에 담는다. 수신자 사전 선택을 임의 변경하지 않는다.

## Publish 진행 및 결과 계약

- API-015 `202 PUBLISHING`은 저장 완료를 뜻하지 않는다. 버튼 중복 실행을 막고 해당 Session API-010을 조회해 상태를 갱신한다. Polling은 증가 간격과 상한을 적용하고 route를 떠나면 취소한다. 고정 interval은 API 계약이 아니므로 Frontend 설정으로 둔다.
- API-010에 Document reference가 없고 상태가 `PUBLISHING`이면 `문서 저장 중`을 표시한다. API-010에 `document:{documentId,documentUrl}`가 존재하면 이후 상태가 `DOCUMENT_SAVED` 또는 채널 전달 중/완료여도 document saved 결과와 안전한 새 탭 링크를 표시한다. `documentUrl`은 http/https URL만 링크로 렌더링한다. 저장 성공이나 URL이 없거나 유효하지 않으면 저장 성공을 유지하되 링크 대신 안전한 재조회 안내를 표시한다.
- 후속 Email/Notification 결과가 아직 끝나지 않았다면 채널 공유 완료라고 표현하지 않고 Document 저장 완료/채널 전달 진행 중을 구분한다. 개별 결과는 후속 TASK 화면에 맡긴다.
- `DOCUMENT_FAILED`면 안전한 오류 안내와 문서 재시도 action을 표시한다. API-010의 `allowedActions`에 `PUBLISH`가 있을 때만 활성화하며 최신 version으로 새 Idempotency-Key를 만들어 API-015를 다시 접수한다. 재요청은 provider 기존 문서 조회를 다시 수행한다.
- API-001/API-014/API-015 오류는 common envelope의 code/category에 맞는 짧은 안내로 표시한다. Secret, provider 원문, Transcript/Minutes 내용을 메시지에 넣지 않는다.

## 동시성 및 응답 유실

- Confirm 및 Publish mutation은 단계별 single-flight로 실행한다. 동일 화면의 반복 click은 중복 API 요청을 만들지 않는다.
- Confirm 응답 유실 시 API-010을 재조회한다. Session이 아직 `REVIEW`면 동일 confirm idempotency key/request를 재전송할 수 있다. 이미 `CONFIRMED`면 최신 version을 확인하고 사용자의 계속 진행 의사가 유지된 현재 interaction에서만 API-015로 이어간다. Session이 다른 상태면 API-010 화면으로 복구한다.
- Publish response 유실/timeout이면 새 key로 blind retry하지 않고 API-010을 조회한다. `PUBLISHING`/`DOCUMENT_SAVED`/`DOCUMENT_FAILED` 중 현재 결과를 반영한다. 최신 API-010이 여전히 `CONFIRMED`이고 `PUBLISH` action을 제공할 때 사용자가 재시도를 명시적으로 선택하면 최신 version과 새 key로 요청한다.
- API-010 늦은 응답이 현재보다 낮은 version이면 무시해 상태/링크가 되돌아가지 않게 한다.

## 접근성

- validation issue는 field label과 연결된 텍스트 및 화면 reader live region으로 제공한다. 색상만으로 문제를 구분하지 않는다.
- Pending, saved, failure 메시지는 keyboard focus를 잃지 않게 하고, issue navigation 및 Settings/document link는 keyboard로 접근 가능해야 한다.
- 모바일 폭에서 버튼/링크가 가로 overflow 없이 보이고 링크 목적이 명확해야 한다.

## 완료 기준

- 유효 Review는 Confirm 성공 version으로 Publish가 접수되고 202를 저장 완료로 오인하지 않는다.
- 422 validation issue는 알맞은 Review 영역에 표시되며 API-015 호출 0회다.
- API-010 `DOCUMENT_SAVED`는 유효한 Provider URL을 제공하고 저장 실패는 지원된 재시도를 안내한다.
- `provider:null`/미설정은 Settings 안내를 보이고 자동 provider 선택이나 Publish를 하지 않는다.
- 중복 click, version race, 응답 유실, 늦은 polling 결과가 중복 publish/상태 역전을 만들지 않는다.
- 채널 결과는 소유 TASK 전에는 성공으로 표시하지 않는다.

## 연결 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)

# Review Minutes 저장 통합

## 목표

TASK-008.02의 Structured Minutes draft를 API-012에 저장하고 API-010 latest snapshot과 일치시킨다. 저장 요청 전후의 user draft를 분리해 dirty/no-op, in-flight edit, version conflict, 응답 유실에서 입력값을 보존한다. PRD v1.7.0 (2026-10-05), `API-010`, `API-012`, `FR-012`, `TASK-008.03`을 구체화한다. Issue [#39](https://github.com/donghyunlee-dev/meeting-automation/issues/39).

## 범위

- Review page `serverBase`/version과 `draft` 간 dirty tracking
- API-012 전체 Structured Minutes payload와 현재 API-010 version `If-Match` 연결
- 중복 submit 차단 및 저장 중 생긴 추가 편집 보존
- API-012 성공 response, API-010 cache 및 read-after-write reconciliation
- validation/provider/version/network 오류와 safe user feedback
- API-010 재조회 중 stale response로 인한 상태 역전 방지
- Transcript/Speaker data와 `allowedActions`의 같은 Session snapshot 유지

## 비범위

Minutes API/schema validation 자체(`TASK-008.01`), 편집 UI/Drawer 컴포넌트(`TASK-008.02`), Template 변경/재생성, Transcript/Speaker mapping mutation, draft의 reload 이후 영구 복구는 포함하지 않는다.

## 저장 및 draft 계약

- 저장 기준은 API-010 `data.version`, `data.minutes`, `data.allowedActions`다. `UPDATE_MINUTES`가 없거나 API-010 Session이 현재 `REVIEW`가 아니면 Save action을 막고 최신 Review 데이터를 안내한다.
- `draft`가 `serverBase.minutes`와 구조적으로 같으면 Save를 disabled/no-op로 두며 API-012를 호출하지 않는다. 변경된 경우에만 full Structured Minutes를 한 번 보내고 `If-Match: "<serverBase.version>"`을 사용한다.
- 요청 본문은 `serverBase.minutes.templateId/templateVersion`을 고정해 유지하고 사용자가 수정할 수 있는 Section/Action Item 값 전체를 포함한다. Client validation 실패 시 request를 보내지 않고 해당 field 안내를 표시한다.
- Mutation 중 한 요청만 실행한다. API call 시 `submittedSnapshot`을 고정한다. 사용자가 saving 중 편집할 수 있는 UI이면 `currentDraft`는 별도로 보유한다. 버튼 중복 submit은 차단한다.
- API-012 HTTP 200이면 response `{version,minutes}`를 new `serverBase` 및 query cache에 반영한다. `currentDraft`가 `submittedSnapshot`과 같으면 clean으로 전환한다. saving 중 사용자가 새로 편집했다면 current draft를 유지하고 `submittedSnapshot`을 새 base로 삼아 dirty 상태를 계속 표시한다.
- 성공 뒤 API-010 GET/cache invalidation으로 authoritative snapshot을 재조회한다. 들어온 response version이 이미 적용한 version보다 낮으면 버리고 cache/version을 되돌리지 않는다. 같은/더 최신 version이면 API-010의 minutes, speaker mapping, transcript, allowedActions를 함께 적용한다.

## 오류 및 응답 유실 처리

- API-012 `400`, `404`, `409`, `412`, `502` 공통 오류는 safe summary/가능한 field error로 표시한다. 기존 `serverBase`와 사용자 draft를 자동으로 지우거나 덮어쓰지 않는다.
- `SESSION_VERSION_CONFLICT`(412)이면 API-010 latest를 조회해 server base/version을 갱신하고 user draft는 그대로 둔다. Draft가 최신 Minutes와 같아지면 clean 처리하고, 다르면 conflict 안내와 명시적 user 재저장을 요구한다. 자동 `PUT` replay를 하지 않는다.
- request가 전송된 뒤 네트워크 오류/timeout으로 결과가 불명확하면 API-010 GET으로 reconcile한다. latest Minutes가 `submittedSnapshot`과 같으면 저장된 것으로 처리한다. 다르면 latest server base를 적용하지만 현재 draft는 유지하고 사용자에게 확인/재저장을 안내한다. API-010도 실패하면 base와 draft를 보존하고 “저장 결과를 다시 확인” action을 제공한다.
- API-012에는 idempotency key가 없으므로 timeout 후 같은 PUT을 자동 반복하지 않는다. 사용자의 명시적 retry는 조회한 최신 version에서만 새 요청으로 시작한다.
- API-001의 `document.provider=null/configured=false`는 API-012 저장을 막지 않는다. Minutes API는 Session owner ID를 검증하며 Document Provider 호출을 하지 않는다. Action Item owner dropdown이 미설정으로 제한되어 있어도 기존 owner 값을 보존하고 변경 가능한 다른 field 저장은 계속할 수 있다.
- API-010 `SESSION_NOT_FOUND` 또는 Review에서 이탈한 Session은 TASK-006.07 route 안내를 사용한다. 오류 상세/Transcript/Minutes 원문은 일반 alert/log에 넣지 않는다.

## 수용 기준

- Clean form은 API-012를 호출하지 않고 dirty form은 full payload를 current version으로 1회 전송한다.
- 성공 response/read-after-write가 같은 version과 Minutes를 가리키며 API-010 cache가 구버전으로 되돌아가지 않는다.
- Saving 중 변경한 새 편집 내용은 제출 snapshot 성공 뒤에도 unsaved draft로 남는다.
- API validation/provider failure는 저장 전의 committed value와 사용자 draft를 보존한다.
- 412는 latest base를 갱신하되 user draft를 보존하고 자동 PUT replay를 하지 않는다.
- Network response가 불명확하면 API-010으로 reconcile하고, 확인되지 않은 결과를 자동 재전송하지 않는다.
- Provider 미선택 상태에서도 API-012 저장은 가능하고 기존 Action Item owner ID를 null로 지우지 않는다.
- Route의 Transcript/Speaker data와 minutes version은 한 API-010 snapshot에서 일관되게 업데이트된다.

## 결정 및 전제

TASK-008.01의 API-012 full replacement/If-Match contract와 TASK-008.02의 structured local form contract를 연결한다. API-010 `version`/`minutes`가 최종 read source이며, API-012 성공 response는 즉시 base를 갱신하고 API-010이 그 이후의 정합성을 확인한다. API-012가 멱등 key 없이 version precondition을 사용하므로 불명확한 전송은 blind retry하지 않는다.

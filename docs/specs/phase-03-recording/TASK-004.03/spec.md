# New Meeting 입력 및 선택 UI 설계

## 목표

`/meetings/new`에서 회의 제목, Template, 참가자를 입력·선택하는 화면을 구현한다. PRD v1.7.0 (2026-10-05), `FR-002`, `SCR-002`, `API-002`, `API-003`, `TASK-004.03`를 구체화한다. Issue [#16](https://github.com/donghyunlee-dev/meeting-automation/issues/16).

## 범위

- New Meeting 폼과 Template/Participant option 조회 및 표시
- 제목, 필수 Template, Participant 1명 이상 선택 검증
- 미선택 roster 구성원을 picker에서 선택하는 `참석자 추가` action
- field validation, loading/error/empty/retry 피드백
- 유효 폼 입력 `{title,templateId,participantIds,timezone}`를 상위 통합 경계에 전달

## 비범위

Session 생성 `POST API-006`, 중복 요청 방지, Recording navigation, 참가자 신규 생성/관리, app-config 연결 안내, 날짜/시간/예상 길이 입력은 포함하지 않는다. API-006 연결은 `TASK-004.05` 범위다. API-001의 Provider 선택은 Session 생성 요건이 아니므로 이 화면에서 Document Provider 연결 여부를 조회하거나 submit을 막지 않는다.

## 화면 및 데이터 동작

Home `TASK-004.01`의 `/meetings/new` route에서 진입한다. 화면에는 제목 input, Template select, Participant multi-select, `참석자 추가`, 하단 `회의 시작` CTA가 있다. 기본 Template은 `default.md`로 선택한다. 회의명 trim 결과가 비어 있지 않고, Template가 선택되며 Participant가 1명 이상 선택되어야 유효하다.

Company timezone은 API-001의 `company.timezone`, Template은 API-002, Participant는 API-003에서 읽는다. 각 응답은 독립 로딩/오류와 retry를 제공한다. App Config `document.provider` 값은 이 화면의 입력 가능 여부를 제한하지 않는다. 요청 중 해당 selector와 제출을 사용할 수 없도록 하고 기존 폼 draft는 보존한다. Participant 결과가 비어 있으면 선택을 요구하는 안내와 Participants 관리에서 roster를 준비하도록 설명한다. Template 결과가 비면 안전한 불가 안내를 표시하고 submit을 막는다.

`참석자 추가`는 신규 인물 생성이 아니라 미선택 roster member를 기존 API-003 결과에서 선택하는 multi-select picker를 연다. 선택 row에는 name/email을 표시한다. Participant는 선택 ID만 `participantIds`로 전달한다. `회의 시작` 클릭 시 먼저 field 검증을 하고, 유효하면 `{title: trim(value), templateId, participantIds, timezone: company.timezone}`를 상위 callback에 넘긴다. API 호출이나 이동은 하지 않는다.

## 수용 기준

- API-001 company timezone과 API-002/003 데이터를 읽고 query loading/error/empty/retry를 처리한다.
- 회의 제목, Template, 최소 한 명 Participant 검증이 해당 field에 표시된다.
- 미선택 Participant를 추가하고 선택 항목을 해제할 수 있다.
- submit callback이 유효한 표준 payload를 한 번 전달한다. invalid input은 전달하지 않는다.
- API 오류 중에도 기존 사용자의 draft와 선택값이 보존된다.
- 360px viewport, keyboard focus, labels, touch target 및 Bottom Navigation이 UI 기준을 충족한다.

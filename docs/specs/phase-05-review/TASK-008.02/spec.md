# Minutes 편집 및 Transcript Drawer UI

## 목표

SCR-006 Review에서 Structured Minutes를 직접 편집하고 Transcript 원문을 별도 Drawer/Sheet에서 읽을 수 있게 한다. 변경 없음·저장 중·오류 상태를 보여주고 편집 draft를 보존한다. PRD v1.7.0 (2026-10-05), `SCR-006`, `FR-012`, `FR-015`, `TASK-008.02`를 구체화한다. Issue [#38](https://github.com/donghyunlee-dev/meeting-automation/issues/38). 선행 계약은 TASK-007.03 Issue [#36](https://github.com/donghyunlee-dev/meeting-automation/issues/36)과 API-010 Review data다.

## 범위

- Structured Minutes의 summary, discussionPoints, decisions, actionItems, followUps 편집
- Action Item task/owner/dueDate 편집 및 사용 가능한 API-003 roster owner 선택
- 현재 Template 표시는 읽기 전용; Template 변경/재생성은 `TASK-009.02` 범위
- Transcript segment 원문을 별도 Sheet/Drawer로 시간순 표시
- speakerId 기반 이름 표시를 API-010 Speaker mapping과 결합
- local form draft, 변경 없음, 저장 중, 저장 오류 및 입력 오류 표시
- 모바일/키보드/screen reader 접근성

## 비범위

API-012 request, `If-Match` 저장 mutation/재조회(`TASK-008.03`), Template 변경 및 Minutes 재생성(`TASK-009.*`), Transcript 수정, 새 Speaker mapping, 별도 Participant 관리/생성은 포함하지 않는다.

## 편집 계약

- API-010의 Structured Minutes를 edit form 초기값으로 사용한다. `templateId`와 `templateVersion`은 표시만 하고 수정하지 않는다.
- `summary`는 multiline text field 하나로 편집한다. discussionPoints/decisions/followUps는 각각 편집 가능한 text item 목록이며 항목 추가/삭제가 가능하다. 빈 section은 빈 목록으로 저장 가능하다.
- Action Items는 각각 `task`, `ownerParticipantId`, `dueDate`를 가진다. Task text와 Date를 편집하고 owner는 API-003 roster name dropdown 및 `미지정(null)` 선택으로 설정한다. Email은 UI에 노출하지 않는다.
- client validation은 Structured Minutes 계약과 맞춘다. summary는 빈 문자열을 허용한다. list item 및 Action Item task의 공백-only 입력은 field 오류로 표시한다. Date는 비어 있거나 유효 `YYYY-MM-DD`, owner는 roster item ID 또는 null이다. API-012에서 서버가 다시 검증한다.
- API-001 `document.provider=null` 또는 `configured=false`로 roster를 읽지 못하면 Minutes 본문 편집은 계속 허용한다. owner dropdown만 roster 선택 불가 안내와 SCR-011 Settings 링크를 표시한다. 기존 owner ID와 편집 draft를 자동으로 null 처리하지 않는다.
- `templateId`/version과 현재 Speaker mapping은 편집 draft가 포함하거나 수정하지 않는다. API-012 callback용 payload는 기존 metadata와 수정 가능한 content fields를 합쳐 Structured Minutes 전체 객체로 만든다.
- 이 TASK는 save state props/callback을 제공한다. clean form에서는 저장 요청을 만들지 않는다. saving 중 duplicate submit을 막고, error 시 입력값을 유지해 수정/재시도가 가능하다. API-012 성공을 임의로 표시하지 않고 caller의 결과 signal만 렌더링한다.

## Transcript Drawer 계약

- Review에서 `Transcript 원문 보기` action으로 별도 accessible Sheet/Drawer를 연다. 닫기 버튼, Escape, backdrop, focus trap 및 닫은 뒤 trigger로 focus return을 제공한다.
- segment는 API-010의 시간순 snapshot을 유지해 표시한다. time은 `startMs`/`endMs`를 `mm:ss` 또는 한 시간을 넘으면 `hh:mm:ss`로 읽기 쉬운 텍스트로 format한다. 원문 text는 escape된 plain text로 렌더링한다.
- Speaker 표시명은 API-010 `speakers`의 `speakerId`→mapping/label을 기준으로 lookup한다. Participant mapping이 없으면 기존 `Speaker A` label을 표시한다. segment마다 별도 매핑 control은 없다.
- Transcript가 비면 Drawer에 빈 안내를 표시한다. 원문 Transcript를 수정하거나 Speaker mapping을 변경하는 action은 제공하지 않는다.
- Drawer open/close 중 Minutes draft, scroll position, 현재 edit focus를 불필요하게 초기화하지 않는다.

## 수용 기준

- 모든 Structured Minutes editable section을 읽기/편집/항목 추가·삭제하고 API-012 full object shape로 caller에 전달한다.
- metadata는 수정할 수 없고, empty summary/section은 허용되며 빈 항목은 inline validation으로 표시된다.
- 변경이 없으면 save 불가/불필요 상태가 구분되고, saving/error 상태에서 draft가 유지된다.
- Drawer는 시간순 Transcript text, time, speaker label을 표시하고 close/Escape/focus 복구를 지원한다.
- Provider 미선택/미설정은 설정 링크로 안내하면서 Minutes text와 기존 owner/draft를 보존한다.
- 360px 이상 mobile layout, 키보드 조작, 접근 가능한 이름/label/focus/contrast를 지원한다.

## 결정 및 전제

Minutes UI는 API-010의 `StructuredMinutes`와 Transcript DTO를 화면 state로 사용한다. Template identity는 immutable하며 교체/재생성은 후속 TASK-009 UI/API를 통해서만 한다. 현재 Task는 UI와 caller boundary를 제공하고 실제 network 저장은 TASK-008.03에서 연결한다.

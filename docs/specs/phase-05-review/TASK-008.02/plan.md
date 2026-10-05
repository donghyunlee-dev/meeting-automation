# Minutes 편집 및 Transcript Drawer UI 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `SCR-006`, `FR-012`, `FR-015`, `TASK-008.02`
- 선행 TASK-007.03 Issue [#36](https://github.com/donghyunlee-dev/meeting-automation/issues/36): Review Session, API-010 data/mapping presentation 및 저장 오류 후 draft 보존 pattern
- API-010: Structured Minutes, Speaker, TranscriptSegment full response
- API-003 TASK-003.01 Issue [#10](https://github.com/donghyunlee-dev/meeting-automation/issues/10): Action Item owner option `{id,name,email}` roster
- API-001: `document.provider/configured`; 미설정 Provider를 자동 선택하지 않고 SCR-011로 안내
- UI source: `docs/product/ui-design.md` SCR-006, component/accessibility/mobile 기준
- Data source: `docs/product/data-spec.md` StructuredMinutes, Speaker, TranscriptSegment
- 본 TASK Issue [#38](https://github.com/donghyunlee-dev/meeting-automation/issues/38)
- 후속 저장 통합 TASK-008.03: API-012 mutation/read-after-write

## 변경 경계

- Review Minutes editor: StructuredMinutes form state, dirty detection, field-level validation, add/remove rows
- Action Item owner select: API-003 roster query 및 App Config Settings fallback
- Transcript Drawer: API-010 segments/speakers read-only projection, overlay/focus lifecycle
- Form boundary: 전체 Minutes draft와 saving/saved/error props/events를 상위 container에 전달
- API-012 HTTP call, version mutation, retry/conflict reconciliation은 TASK-008.03에서 처리한다.

## 구현 순서

1. Review page state owner, API-010 Minutes/transcript/speakers DTO, API-003/App Config query/cache 및 기존 Sheet/Drawer component를 확인한다. 결과: state/query 공유와 accessible overlay 재사용 경계가 확정된다.
2. fixture로 editor initialization/dirty/no-op/validation/saving/error draft retention 및 Transcript order/speaker join/focus behavior test를 먼저 작성한다. 결과: edit와 Drawer contract가 구현 전 실패 테스트로 고정된다.
3. Minutes structured editor를 구현하고 dynamic lists/Action Items add-remove 및 local validation을 연결한다. 결과: 전체 payload shape와 owner/null/date 필드가 유지된다.
4. API-003 roster와 API-001 provider config를 연결해 Action Item owner option, null provider Settings 안내, 기존 owner/draft 보존을 구현한다. 결과: Provider 미설정이어도 본문 편집이 가능하다.
5. Read-only Transcript Drawer를 구현한다. 결과: segment 원문/시간/Speaker name이 안전하게 보이고 modal keyboard focus가 관리된다.
6. form callback/save state boundary를 연결하되 API request는 만들지 않는다. 결과: 00803이 저장을 주입할 수 있고 실제 server 성공을 위조하지 않는다.
7. `npm run test`, `npm run lint`, `npm run build` 및 mobile/accessibility QA를 수행한다. 결과: automations와 view evidence가 Issue에 기록된다.

## 검증 명령

Frontend 프로젝트의 `npm run test`, `npm run lint`, `npm run build`를 사용한다. API-010/API-003/API-001 fixtures, 360px viewport, keyboard/SR drawer operation 및 Transcript plain text rendering을 확인한다.

# 검증 계획

> 📌 v1.9.0 변경 계약: TASK-022.06과 TASK-004.04 변경 완료 후 API-001의 documentConnectionVersion을 불변 제출 snapshot에 포함한다. 미설정·전환 중에는 제출을 차단하고, DOCUMENT_CONNECTION_CHANGED는 참석자 목록과 설정을 다시 읽어 새 시도/key를 만들도록 안내한다. 동일 연결의 응답 유실 재시도만 기존 snapshot/key를 재사용한다. 계정이나 사용자별 설정은 추가하지 않는다. 상세는 [문서 연결·이전 설계](../../../product/document-setup.md)와 [API 명세](../../../product/api-spec.md#api-006-meeting-session-create)를 따른다.

## 자동화 테스트

| ID | 준비/입력 | 기대 결과 | 증거 |
|---|---|---|---|
| SCR-002-12 | 유효 form submit | API-006에 title/template/participantIds/timezone/recoveryKey, 헤더 Idempotency-Key 전송 | API client/component test |
| SCR-002-13 | request 진행 중 빠른 다중 click | request 1회, CTA disabled/submitting indicator | Component test |
| SCR-002-14 | network timeout 뒤 같은 시도 재전송 | 동일 payload와 동일 두 key 재사용 | Integration test |
| SCR-002-15 | 오류 뒤 사용자가 입력을 변경하고 제출 | 새 logical attempt의 두 key를 발급 | Component/API test |
| SCR-002-16 | API 400/409/500/502 응답 | 안전 메시지 표시, form draft 유지, 성공 navigation 없음 | Error handling test |
| SCR-002-17 | API 201 응답 | sessionId/version/status/uploadPolicy를 Session Context에 저장 | Context test |
| SCR-002-18 | API 201 후 router | `/meetings/{sessionId}/recording`으로 이동 | Router integration test |
| SCR-002-19 | Session 생성 요청에서 App Config의 document.provider가 null | Session 요청 가능; provider 선택 여부로 막지 않음 | Component test |
| SCR-002-20 | 오류 message fixture에 Provider raw body/secret | 화면에는 공통 안전 message만 노출 | Security/UI test |

Frontend 기반 Issue #2의 `npm run test`, `npm run lint`, `npm run build`로 검증하고 evidence를 남긴다. 문서 작성 단계에서는 실행하지 않는다.

## 수동 QA

- New Meeting 유효 폼에서 시작 클릭 후 API-006 payload와 header를 network inspector에서 확인한다.
- 빠르게 연속 클릭해 요청이 하나인지 확인한다.
- 서버가 Session 생성 후 응답을 끊은 경우 재시도 payload/key가 같은지 확인한다.
- 400/409/500/502 응답에서 form 내용은 남고 내부 오류/secret이 보이지 않는지 확인한다.
- 성공 뒤 주소/Session Context에 Session ID와 upload policy가 있고 route에 recovery/idempotency key가 포함되지 않는지 확인한다.

## 릴리스 확인

Recording 화면이 구현되기 전 destination fixture로 route contract를 테스트한다. 실제 MediaRecorder와 upload는 후속 Recording/Chunk task에서 확인한다.

## ⚙️ 연결 변경 회귀

- 미설정 또는 전환 잠금 중 New Meeting 제출은 API-006 호출 없이 설정/진행 안내를 보인다.
- API-001의 connectionVersion을 POST와 불변 snapshot에 포함한다. stale 409 뒤 설정·참석자 roster를 다시 읽고 새 key로 요청한다.
- 동일 연결에서 응답 유실 재시도는 같은 version/payload/key를 유지한다.

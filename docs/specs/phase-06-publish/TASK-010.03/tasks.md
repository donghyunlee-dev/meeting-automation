# 구현 작업 목록

## 사전 확인

- [ ] SCR-007 navigation, Review route state, API client/query cache, Settings return route를 탐색한다. 결과: 상태를 소유하는 컴포넌트와 보존할 interaction 값을 기록한다.
- [ ] API-001/010/014/015 response DTO, API-010 `PUBLISH` allowedAction과 document reference가 TASK-010.02 계약에 포함됐는지 확인한다. 결과: client types와 response examples가 일치한다.
- [ ] Frontend package manifest와 실제 test/lint/build scripts를 확인한다. 현재 설계 시점 repo에는 `frontend/`가 없어 구현 branch에서 runnable project commands를 확보한다.

## 구현 단계

- [ ] Provider 미설정/Settings recovery, API-014 422 field issue mapping, confirm→publish API call order 테스트를 구현 전 작성한다. 결과: 자동 provider 선택/검증 건너뛰기/잘못된 version 사용이 실패한다.
- [ ] API-010 polling `PUBLISHING`/`DOCUMENT_SAVED`/`DOCUMENT_FAILED`, stale response, URL validation, fresh-key retry 테스트를 작성한다. 결과: unknown response를 성공으로 추정하거나 blind retry하면 실패한다.
- [ ] SCR-007에서 API-001 설정을 읽고 Settings 안내를 표시한다. 결과: null/unconfigured 상태에서 Publish가 비활성화되고 Screen navigation 후 selection/draft가 복원된다.
- [ ] Confirm mutation을 API-014로 연결한다. 결과: 422 issue path가 입력 field/section과 연결되고 실패 때 API-015 호출이 없다.
- [ ] Confirm 200 version으로 API-015 request를 연결한다. 결과: body의 user selection을 보존하고 중복 click에도 요청은 한 번이다.
- [ ] 202 응답 뒤 API-010 polling 및 bounded backoff/cancel/stale version guard를 구현한다. 결과: API-010 최신 snapshot이 progress view를 결정한다.
- [ ] `DOCUMENT_SAVED`에서 document URL을 검증해 제공하고 `DOCUMENT_FAILED`에서 allowedAction 기반 retry를 구현한다. 결과: 유효 http/https만 외부 링크가 되고 새 key/current version으로 명시 retry한다.
- [ ] 채널이 미완료인 동안 문서 저장과 공유 완료 표현을 분리한다. 결과: Email/Notification의 아직 없는 결과를 성공으로 표시하지 않는다.
- [ ] 실제 frontend test/lint/build 명령과 브라우저/mobile/accessibility QA를 실행하고 evidence를 Issue #45에 남긴다.

## 검토 완료 조건

- Confirm 없이 Publish로 건너뛰는 경로가 없다.
- Provider null에서 설정된 provider를 임의 선택하지 않는다.
- 새로고침/route 이동/응답 유실로 이전 화면 상태나 링크가 안전하지 않게 바뀌지 않는다.
- 모든 error feedback은 safe code/message만 노출하며 민감한 본문을 표시하지 않는다.

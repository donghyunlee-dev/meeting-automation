# Speaker mapping 저장 통합 검증 계획

관련 Issue: [#36](https://github.com/donghyunlee-dev/meeting-automation/issues/36).

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| MAP-INT-01 | draft가 committed mapping과 동일 | Save disabled/no-op, API-011 호출 없음 | Review form test |
| MAP-INT-02 | dirty 전체 speaker draft와 API-010 version 3 | API-011에 full set 및 `If-Match: "3"` 한 번 전송 | API client contract test |
| MAP-INT-03 | 저장 중 반복 click | in-flight request 하나, disabled/loading feedback | mutation lifecycle test |
| MAP-INT-04 | API-011 HTTP 200/version 4/full mappings | committed base/cache version/mappings 한 commit으로 갱신, draft clean | mutation integration test |
| MAP-INT-05 | 같은 speakerId를 참조하는 다수 Transcript segment | 모든 display label이 새 participantName, segment ID/time/text 불변 | Transcript projection test |
| MAP-INT-06 | API-011 400/404/409/502 | 기존 committed mapping 유지, draft 유지, safe error 표시 | mutation error test |
| MAP-INT-07 | API-011 412 `SESSION_VERSION_CONFLICT` 후 API-010 최신 response | 최신 base/version 로드, draft 보존, 자동 재전송 없음 | version conflict reconciliation test |
| MAP-INT-08 | PUT 성공 응답이 유실되었으나 API-010 GET mapping이 요청과 일치 | 저장 성공으로 reconcile, version 갱신, draft clean | uncertain mutation success test |
| MAP-INT-09 | PUT 응답 유실, API-010 mapping이 요청과 다름 | 최신 base 표시, draft 보존, 자동 PUT 재호출 없음, 명시적 재시도 안내 | uncertain mutation conflict test |
| MAP-INT-10 | PUT 응답 유실 후 API-010 GET도 실패 | base/draft 보존, 조회 재시도 안내, 중복 PUT 없음 | recovery failure test |
| MAP-INT-11 | App Config `document.provider=null` 또는 `configured=false` | API-011 미호출, SCR-011 Settings 안내 유지 | provider guard test |
| MAP-INT-12 | API-010 새 `allowedActions`에서 mapping action 제거 | 저장 차단, latest snapshot 안내, backend에 mutation 보내지 않음 | action gate test |
| MAP-INT-13 | 여러 speaker를 한 번에 바꾸고 저장 | 한 API request로 전부 저장, 부분 response merge 없음 | batch integration test |

Frontend `npm run test`, `npm run lint`, `npm run build`; Backend `./gradlew test`, `./gradlew clean build`를 구현 후 실행하고 결과를 기록한다. 설계 단계에서는 테스트를 실행하지 않는다.

## 통합/수동 QA

- Review에서 둘 이상의 Speaker를 변경해 저장하고 API-010 재조회 후 version, mapping 및 모든 관련 segment 표시가 함께 바뀌는지 확인한다.
- 저장 중 반복 탭, stale version, invalid/removed roster mapping 및 provider 오류를 재현해 기존값과 draft가 유지되는지 확인한다.
- 응답을 drop한 network timeout에서 API-010 reconcile이 성공/불일치/실패하는 각 경우에 자동 PUT 중복이 없는지 확인한다.
- `document.provider=null` fixture에서 Settings 안내를 사용하고 저장 요청이 전송되지 않는지 확인한다.
- mapping 전후 Transcript segment의 id, startMs, endMs, text가 그대로인지 확인한다.

## 릴리스 확인

- API-011 성공 뒤 API-010 cache invalidation/refetch가 새 version을 유지하고 오래된 응답이 최신 cache를 덮지 않는지 확인한다.
- API/Provider 오류 원문과 Transcript/email/Secret이 화면 또는 로그에 나오지 않는지 확인한다.

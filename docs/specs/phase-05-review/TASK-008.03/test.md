# Review Minutes 저장 통합 검증 계획

관련 Issue: [#39](https://github.com/donghyunlee-dev/meeting-automation/issues/39).

## 자동화 테스트

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| MIN-INT-01 | form draft와 API-010 Minutes 동일 | clean state; API-012 호출 0회 | Review mutation test |
| MIN-INT-02 | dirty full StructuredMinutes, API-010 version 7 | API-012 body full replacement, `If-Match: "7"` 한 번 | API client contract test |
| MIN-INT-03 | API-012 HTTP 200/version 8/Minutes response | base/cache version/Minutes 갱신, 같은 draft clean | mutation integration test |
| MIN-INT-04 | API-012 성공 후 API-010 read-after-write version 8 | minutes/status/speakers/transcript/allowedActions가 same snapshot으로 적용 | query integration test |
| MIN-INT-05 | API-012 request in-flight 중 사용자가 draft 수정 | 응답은 submitted snapshot만 committed 처리; 후속 edit는 dirty로 남음 | concurrent user edit test |
| MIN-INT-06 | 저장 중 duplicate click 또는 `UPDATE_MINUTES` action 없음 | 중복 PUT 없음, action 미허용 시 저장 차단 | mutation gate test |
| MIN-INT-07 | API-012 400 `VALIDATION_FAILED` | current server base와 user draft 유지, safe field/general feedback | validation error test |
| MIN-INT-08 | API-012 404/409/502 | prior saved Minutes와 draft 보존; raw error 미표시 | API error mapping test |
| MIN-INT-09 | API-012 412 `SESSION_VERSION_CONFLICT`, API-010 latest가 반환됨 | base/version refresh, draft 보존, PUT 자동 재호출 없음 | version conflict test |
| MIN-INT-10 | PUT response 유실, API-010 Minutes가 submitted payload와 같음 | 성공으로 reconcile, base 갱신, 추가 edit 있으면 dirty 유지 | uncertain success test |
| MIN-INT-11 | PUT response 유실, API-010 Minutes가 submitted payload와 다름 | latest base 적용, draft 보존, 명시적 재저장 안내, blind PUT 없음 | uncertain conflict test |
| MIN-INT-12 | PUT response 유실, API-010 GET도 실패 | base/draft 보존 및 결과 재확인 안내, PUT 중복 없음 | reconciliation failure test |
| MIN-INT-13 | API-010 lower-version GET이 mutation success 뒤 도착 | cache version/minutes를 이전 값으로 되돌리지 않음 | stale query race test |
| MIN-INT-14 | `document.provider=null/configured=false`, existing owner ID 보유 | API-012 Minutes text update 허용, owner 값을 null로 제거하지 않음 | provider independence test |
| MIN-INT-15 | API-010 `SESSION_NOT_FOUND` 또는 상태가 REVIEW에서 이탈 | 새 Review 저장 차단 및 route recovery 안내 | session lifecycle integration test |
| MIN-INT-16 | 성공 저장 및 이후 재진입/GET | API-010 read가 저장된 Minutes를 반환하고 UI base가 동일 | persistence round-trip test |

Frontend `npm run test`, `npm run lint`, `npm run build`; Backend `./gradlew test`, `./gradlew clean build`를 구현 후 실행하고 결과를 기록한다. 설계 단계에서는 테스트를 실행하지 않는다.

## 통합/수동 QA

- Review에서 서로 다른 Minutes section을 바꾸고 저장해 API-010 재조회 후 모든 필드가 보존되는지 확인한다.
- 저장 진행 중 값을 다시 편집해 첫 응답이 이후 draft를 덮지 않는지 확인한다.
- stale version, invalid body, provider-independent error, network response drop을 재현해 값 보존/재조회 안내를 확인한다.
- API-010 재조회 성공/불일치/실패와 version 역순 도착을 확인해 cache가 최신 snapshot을 유지하는지 확인한다.
- Provider 미설정 상태에서 owner select 안내가 보여도 다른 Minutes text update와 기존 owner ID가 유지되는지 확인한다.

## 릴리스 확인

- API-012 성공과 API-010 requery의 `version`이 일치하고 Transcript/Speaker mapping은 바뀌지 않았는지 확인한다.
- API error 원문, Transcript/Minutes text, Participant email 및 Secret이 로그에 남지 않는지 확인한다.

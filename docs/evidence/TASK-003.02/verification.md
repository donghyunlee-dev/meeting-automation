# 🧪 참가자 생성 검증 기록

## 📌 완료 정보

| 항목 | 확인 결과 |
|---|---|
| TASK / Issue | TASK-003.02 / [Issue #11](https://github.com/donghyunlee-dev/meeting-automation/issues/11) |
| PR | [PR #89](https://github.com/donghyunlee-dev/meeting-automation/pull/89) |
| 테스트·리뷰·QA head | `83fd9459ad46db1497583f95615570c053ac12ff` |
| master 병합 commit | `1314cfb2e327ad225ffb9400c823831ad1b99344` |
| 병합 시각 | 2026-10-09 07:32 KST |
| 다음 개발 대상 | TASK-003.03 참가자 수정 PATCH |

## 🛠️ 구현과 설계 정합성

`POST /api/v1/participants`는 name/email을 trim하고 검증한 뒤 선택된 Provider의 Participants 하위 페이지를 생성한다. 성공은 201 및 `{id,name,email}`, 잘못된 입력이나 누락된 Idempotency-Key는 400, 같은 키의 다른 정규화 입력은 409이다. Notion과 Confluence 저장 형식은 목록 조회 계약과 일치한다. 구조 누락은 422, Provider 실패는 안전한 502 응답으로 변환한다.

단계별 ParticipantCreationProvider를 추가했다. 실제 저장소에 기존 멱등성 구성요소가 없어 생성 전용 동시성 coordinator를 구현하고 설계 문서를 정정했다. 같은 키의 동시 요청은 한 번 생성하며 성공과 최종 오류를 재사용한다. 저장 결과를 알 수 없는 POST 실패는 자동 재시도하지 않는다. 단일 프로세스 메모리 범위이며 재시작 이후 키 복원은 보장하지 않는다.

## ✅ 자동화 검증

실행 위치는 backend/, JDK 25.0.3과 프로젝트 Gradle Wrapper다. CMD TTY에서 canonical TEMP/TMP를 사용했다.

```cmd
set TEMP=C:\Users\donghyunlee\AppData\Local\Temp && set TMP=C:\Users\donghyunlee\AppData\Local\Temp && gradlew.bat test
set TEMP=C:\Users\donghyunlee\AppData\Local\Temp && set TMP=C:\Users\donghyunlee\AppData\Local\Temp && gradlew.bat clean build
```

- 최초 API 테스트는 구현 전 POST endpoint 부재로 여섯 assertion이 실패했다.
- Confluence 생성 사전 GET transport 오류 회귀 테스트는 수정 전 retryable assertion이 실패했고 수정 후 통과했다.
- 최종 head 전체 **104 tests / 11 suites / 0 failures / 0 errors / 0 skipped**, clean build 8 tasks PASS. 리더와 QA가 동일 head의 XML 결과를 확인했다.
- 두 Provider의 생성 요청·생성 후 목록 조회, 입력 검증, 키 재전송·충돌·동시 단일 생성, 불명확한 생성 실패 재사용, 안전한 오류와 사전 GET 일시 실패를 확인했다.

## 🔍 GitHub 리뷰

[Confluence 사전 GET transport 오류](https://github.com/donghyunlee-dev/meeting-automation/pull/89#discussion_r4224751621)의 P2 지적을 수정했다. POST 전에 실패한 안전한 GET은 retryable true이며 생성 POST는 호출하지 않는다. 실제 POST의 불명확한 실패는 retryable false를 유지한다. 모든 리뷰 스레드가 해결됐고 [최종 head 리뷰 PASS](https://github.com/donghyunlee-dev/meeting-automation/pull/89#pullrequestreview-5463627126)를 확인했다.

## 🌐 로컬 QA

[QA PASS 댓글](https://github.com/donghyunlee-dev/meeting-automation/pull/89#issuecomment-6070345265)은 동일 head를 확인했다. 최종 clean build jar를 실행해 PID 24828, http://127.0.0.1:18080 및 시작 로그를 확인했다. NOTION 선택, 빈 root/token override로 외부 생성 없이 점검했다.

| 확인 | 결과 |
|---|---|
| GET /actuator/health | 200 / UP |
| GET /api/v1/participants | 422 / 안전한 구조 누락 오류 |
| GET /api/v1/integrations/health | 200 / NOTION 선택 상태, rootAccessible false |
| 잘못된 이름·이메일 또는 누락된 키 | 400 / VALIDATION_FAILED, field/code만 반환 |
| 유효한 trim 입력 및 새 키 | 422 / DOCUMENT_STRUCTURE_NOT_FOUND |
| 같은 키·동일 정규화 입력 재전송 | 동일한 422 결과 재사용 |
| 같은 키·다른 입력 | 409 / IDEMPOTENCY_KEY_CONFLICT |
| MockMvc / mock-provider 연동 | 설정된 성공 201·Provider 저장·멱등성·오류 수용 기준 PASS |
| Browser | N/A — UI 변경 없음 |
| 실제 인증된 Notion/Confluence | 미실행 — 자동화 수용 기준에 포함되지 않으며 실제 서비스 생성 검증 통과를 주장하지 않음 |

승인된 CMD TTY와 HTTP 실행 경로를 사용했다. QA 완료 후 graceful shutdown 로그를 확인하고 서비스를 종료했다.

## 🔗 병합과 정리

리더가 OPEN/base master/최종 head, 두 Gradle Wrapper CI SUCCESS, 모든 리뷰 스레드 해결 및 동일 head의 테스트·리뷰·QA를 확인했다. checkpoint의 QA SHA를 검증된 head로 갱신한 뒤 gate-merge를 통과해 병합했고 GitHub MERGED 상태와 실제 병합 SHA를 확인했다. Issue #11은 completed로 닫고 진행 라벨을 제거했다. PRD 행은 DONE, 다음 커서는 TASK-003.03이다. 완료 기록을 master에 push하고 원격 내용을 확인한 뒤 다음 작업을 시작한다. 기존 사용자 변경은 보존했다.

Provider 생성 형식은 2026-10-09 확인한 [Notion Create a page](https://developers.notion.com/reference/post-page)와 [Confluence Page API](https://developer.atlassian.com/cloud/confluence/rest/v2/api-group-page/)를 따른다.

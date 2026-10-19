> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# 구현 작업 목록

기준: `TASK-013.01`, PRD v1.7.0 (2026-10-05), `FR-019`, `FR-027`, `API-010`, `API-016`, Issue [#50](https://github.com/donghyunlee-dev/meeting-automation/issues/50).

## 사전 확인

- [x] API-010 Delivery store/model 및 Publish attempt 상태 전이를 조사한다. 완료 증거: 상태 소유 객체와 원자 갱신 위치를 기록한다.
- [x] 공통 Idempotency-Key 저장/재생 경계를 조사한다. 완료 증거: 동일 키 replay와 payload 충돌의 기존 규칙을 기록한다.
- [x] Email/Notification dispatcher 및 `retryable` 공급 원천을 조사한다. 완료 증거: 채널별 재시도 입력이 단건인지 확인한다.
- [x] Session/Delivery의 memory-only 수명과 process 종료 동작을 조사한다. 완료 증거: API-016 복구 한계를 기록한다.

## Backend: 테스트 우선 구현

- [x] API-016 controller/application 테스트를 작성한다. 기대: Session/Delivery 소유권, 필수 key, 공통 오류 envelope가 명세와 일치한다.
- [x] 재시도 허용/거절 테스트를 작성한다. 기대: `FAILED && retryable=true`만 허용; SENT, retryable false, PENDING/SENDING은 거절한다.
- [x] idempotency 테스트를 작성한다. 기대: 같은 key+같은 delivery는 기존 202 재생, 같은 key+다른 delivery는 충돌한다.
- [x] 동시 재시도 race 테스트를 작성한다. 기대: 서로 다른 key 중 단 하나만 FAILED→PENDING 전이를 얻고 Provider 호출은 한 번이다.
- [x] 시도 횟수 테스트를 작성한다. 기대: Provider invocation 시작 때 한 번 증가하며 API-010 조회에 같은 값이 보인다.
- [x] 최소 retry use case/endpoint를 구현한다. 기대: 승인된 하나의 Delivery만 지정 channel Adapter로 다시 전송한다.
- [x] Email 재시도에서 participant ID 기준 최신 주소를 다시 읽는 테스트를 작성한다. 기대: 수정된 주소로만 재호출되고 주소는 history/API에 남지 않는다.
- [x] Notification 재시도에서 현재 meeting webhook만 사용하는 테스트를 작성한다. 기대: Admin incident webhook/다른 Delivery 호출 0회다.
- [x] 명시 거절/교정 가능한 오류와 timeout/응답 유실의 retryable 분류를 검증한다. 기대: 명시적 사용자 요청 이외 자동 반복이 없다.
- [x] API-010 serialization 및 secret/PII redaction 테스트를 작성한다. 기대: 공통 Delivery 필드만 노출된다.

## 완료 확인

- [x] 정상/실패/mixed channel/중복 key/동시 요청을 포함한 Backend 테스트 증거를 기록한다.
- [x] API-016은 선택한 단일 row만 재시도하고 Email·Slack 다른 row와 Document 결과는 유지되는지 확인한다.
- [x] Java 25/Gradle Wrapper로 변경 및 직접 영향 테스트만 `test --tests <FQCN>` 선택 실행하고, 실제 명령과 Issue #50 증거를 기록한다. 전체 Backend 테스트 suite는 실행하지 않는다.

## 자동화 검증 결과

- Java 25, 작업 디렉터리 `backend`: `./gradlew.bat test --tests com.meetingautomation.MeetingSessionPublishApiTests --tests com.meetingautomation.MeetingSessionStatusApiTests` — 총 26건 통과(클래스별 20건/6건), 실패 0건.
- `git diff --check` — 통과. 검증한 코드 head: `487ad173ee30a25f4248e505fdbfbfef32781cff`.

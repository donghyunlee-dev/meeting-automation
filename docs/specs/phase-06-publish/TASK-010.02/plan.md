# 구현 계획

> 📌 v1.9.0 변경 계약: TASK-022.04/.05의 공통 문서 codec과 활성 connection snapshot을 사용한다. 전환 lock 중 Publish를 차단하며 export/import와 같은 metadata/본문 round-trip 계약을 따른다. 이전으로 전달을 재발송하지 않는다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

## 기준

- PRD v1.7.0, 2026-10-05
- 요구사항: `FR-016`, `API-015`, `EXT-003`
- 선행 작업: TASK-010.01, Issue #43 (Review를 `CONFIRMED`로 전이)
- 작업 Issue: [#44](https://github.com/donghyunlee-dev/meeting-automation/issues/44)
- 설계: [spec.md](./spec.md), [tasks.md](./tasks.md), [test.md](./test.md)

## 소유 경계와 의존성

Backend API/use case와 Session publish orchestration이 이 작업의 소유다. API-015가 처리 접수만 반환하는 계약이므로 API/controller 경계는 요청/header 검증과 공통 envelope를 담당하고, application service가 atomic Session 전이와 비동기 Document 작업을 시작한다. `DocumentProvider`는 TASK-002.01 Port를 사용하며 실제 선택 Provider는 기존 설정을 따른다. 현재 배포 설정의 Confluence는 Cloud REST API v2 및 Basic 인증(email/API token)이며 인증 방식은 이 workflow에서 직접 구현하지 않는다.

Email/Notification 구현은 각각 TASK-011.01/TASK-012.01이 소유한다. 이번 작업은 채널 옵션을 publish record에 보존하고 Document 저장 성공 후 전달 workflow에 연결할 명확한 handoff를 제공한다. TASK-013.01은 개별 delivery 재시도를 소유한다. 새 영속 저장소/Queue 도입은 금지한다.

## 변경 대상

- API-015 request DTO/controller: payload/header/body 검증과 `202` 공통 envelope
- Publish use case/Session aggregate: state/action/version 조건 검사, idempotency fingerprint, 동시 접수 보호
- Document publish worker/orchestrator: immutable snapshot, metadata 및 provider 조회/생성 순서
- Session/API-010 response mapper: publish 상태, document reference, 안전한 오류 결과
- Backend unit/integration test fixtures: provider fake 및 concurrency/replay 증거

구현 시작 때 실제 Backend package/class 구조와 빌드 명령을 확인하고 기존 패턴에 맞춰 파일 위치를 정한다. PRD 경로에 없는 DB/queue/package를 전제하지 않는다.

## 구현 순서

먼저 API-015의 입력/접수 계약과 Session transition을 고정한다. Worker는 그 accepted immutable snapshot을 소비하므로 접수 경계가 선행돼야 한다. 그다음 provider dedupe를 구현하고 문서 성공/실패를 API-010 상태로 귀결시킨다.

1. API/session/provider 경계의 현재 구현과 공통 response/error/idempotency helper를 확인하고 변경 지점을 기록한다.
2. API-015 validation 및 Session 상태/version/idempotency 결과의 실패 테스트를 먼저 작성해 기존 구현의 누락을 확인한다.
3. API acceptance/replay/conflict 동작을 최소 구현하고 202 envelope 및 atomic transition을 연결한다.
4. Publish snapshot에서 provider command/metadata를 만들고 기존 문서 lookup 우선, 부재 시 create를 구현한다.
5. Document ref 저장과 `DOCUMENT_SAVED`/`DOCUMENT_FAILED` 전이를 API-010에 연결한다. 실패 시 downstream delivery가 시작되지 않도록 보장한다.
6. 같은 Session 다른 key 및 동시 요청/Provider lookup ambiguity 사례의 회귀 검증을 보완한다.
7. Backend 지정 검증 명령과 수동 API QA를 수행하고 비민감 evidence를 Issue #44에 기록한다.

## 인터페이스 및 불변식

- API-015 request/response 및 오류는 [spec.md](./spec.md)의 고정 계약을 따른다.
- `CreateMeetingCommand.metadata.externalSessionId`는 Session ID와 같다.
- provider `findMeetingBySessionId` 오류 시 새 문서를 생성하지 않는다.
- Session 상태/version 조건 변경은 한 번만 적용된다. accepted replay는 worker를 중복 예약하지 않는다.
- downstream Email/Notification은 Document 참조 저장 성공 이벤트 이후에만 실행할 수 있다.
- 사용자 콘텐츠/secret/provider 원문은 로그와 오류에 노출하지 않는다.

## 검증 접근

자동화 대상은 API request/error contract, Session CAS/state/version, idempotency replay/key conflict, provider lookup/reuse/create/failure, no-downstream-on-failure 및 API-010 최종 결과다. 실행 명령은 Backend build 설정을 확인해 implementation에서 고정한다. 수동 QA는 [test.md](./test.md)에 적힌 API 시나리오를 따른다.

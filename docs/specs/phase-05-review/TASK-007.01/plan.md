# Speaker mapping API 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `DEC-009`, `DEC-010`, `FR-010`, `API-011`, `TASK-007.01`
- 선행 TASK-006.06 Issue [#32](https://github.com/donghyunlee-dev/meeting-automation/issues/32): API-010의 Review Session, version, speakers/transcript snapshot 및 `allowedActions`
- 선행 TASK-003.01 Issue [#10](https://github.com/donghyunlee-dev/meeting-automation/issues/10): 표준 Participant roster `{id,name,email}`와 active provider의 `ParticipantListingProvider`
- 본 TASK Issue [#34](https://github.com/donghyunlee-dev/meeting-automation/issues/34)
- Data contracts: `docs/product/data-spec.md` Session/Speaker/TranscriptSegment 및 provider ID
- API contracts: `docs/product/api-spec.md` 공통 오류 envelope, `If-Match`, API-011

## 변경 경계

- Backend Controller/API DTO: PUT path, `If-Match`, 전체 mappings body, `{data}` response
- Application use case: REVIEW/allowed action 확인, mapping/full-roster validation, atomic Session mutation 및 optimistic concurrency; commit 시 state/action을 version 비교보다 먼저 확인
- Domain Session: Speaker `participantId`만 변경하고 version 증가; Transcript segment와 Minutes는 직접 수정하지 않음
- Outbound port: `DocumentProviderResolver`에서 active `ParticipantListingProvider`를 선택하고 Participants 구조 및 roster를 조회해 선택 ID와 응답 이름을 검증
- Common exception mapper: `SESSION_NOT_FOUND`, `SESSION_STATE_CONFLICT`, `SESSION_VERSION_CONFLICT`, `PARTICIPANT_NOT_FOUND`, `PARTICIPANT_LIST_FAILED`, `DOCUMENT_STRUCTURE_NOT_FOUND`, `VALIDATION_FAILED`
- Frontend/Speaker dropdown 및 mapping 저장 통합은 변경하지 않음.

## 구현 순서

1. Session snapshot/version mutation boundary, API-010 `speakers`/`transcript` shape, `DocumentProviderResolver`/`ParticipantListingProvider` path와 공통 error mapper를 확인한다. 결과: 한 mutation 안에서 검증·저장할 경계와 roster 조회 의존성이 확인된다.
2. 정상 replacement, null clearing, empty speakers, duplicate/unknown speaker, outside/missing roster participant, state/version conflict, provider failure, concurrency/privacy tests를 먼저 작성한다. 결과: 부작용 전 규칙이 고정된다.
3. request/header validation과 전체 mappings/roster validation을 구현한다. 결과: 잘못된 항목 하나라도 있으면 Session을 바꾸지 않는다.
4. version compare-and-set로 모든 Speaker mapping을 원자 적용하고 API-011 response mapper를 구현한다. 결과: 성공 시 version 한 번 증가 및 full ordered mappings를 반환한다.
5. API-010 재조회로 같은 version의 Speaker reference와 segment join을 검증하고, 로그/error redaction을 확인한다. 결과: 해당 화자의 모든 segment 표시가 동일 mapping을 소비한다.
6. Backend focused 자동화 테스트와 오류·보안 회귀 검증을 수행한다. 결과: 변경 코드와 직접 관련된 테스트 class selector의 결과를 남기고 CI 설정은 변경하지 않는다.

## 검증 명령

Backend root에서 아래 변경 관련 테스트 selector만 실행한다. 전체 Backend suite와 `clean build`는 Developer/QA 검증으로 실행하지 않고 GitHub Actions CI 설정은 유지한다. Mapping 저장 뒤 API-010을 재조회해 version과 Speaker/segment reference consistency를 integration 검증한다.

```powershell
& .\gradlew.bat test --tests com.meetingautomation.SpeakerMappingApiTests --tests com.meetingautomation.api.session.SpeakerMappingServiceTests --tests com.meetingautomation.api.session.MeetingSessionStoreTests --tests com.meetingautomation.MeetingSessionStatusApiTests --tests com.meetingautomation.transcription.DiarizationResultAdapterTests --tests com.meetingautomation.api.processing.ProcessingPipelineTests
```

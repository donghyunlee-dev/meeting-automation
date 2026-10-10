# DocumentProvider 공통 계약 및 계약 테스트 작업 목록

> 📌 v1.9.0 변경 안내: 이 문서는 완료된 TASK-002.01의 당시 구현/검증 이력이다. Backend env로 선택·credential을 고정하는 계약과 초기 구조 생성 제외 범위는 새 TASK-022에서 변경한다. 현재 구현 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)와 TASK-022 패키지다. 일반 Health/탐색은 계속 읽기 전용이며 명시적 초기화만 구조를 생성한다. 기존 DONE/검증 증거는 보존한다.

## 작업 식별 정보

- 작업: `TASK-002.01`
- 선행 조건: Issue #5 완료
- GitHub Issue: [#6](https://github.com/donghyunlee-dev/meeting-automation/issues/6)

## 테스트 우선 구현 단계

### 선행 기반 확인

- [ ] 선행 Issue #5 완료와 Backend Gradle/JUnit 5 기반을 확인한다. 결과: 계약 테스트를 실행할 수 있다.
- [ ] Backend package 관례 및 API/Data/Integration 문서의 Document 계약을 대조한다. 결과: 기존 표준 타입을 재사용하며 새 업무 필드를 추가하지 않는다.

### 실패 테스트 작성

- [ ] `DocumentProviderContractTest`에 Root와 직속 `Meetings`/`Participants` 발견 및 누락 구조 사례를 작성한다.
- [ ] Meeting 요약/상세 읽기, Participant 생성/수정, Meeting 생성 참조의 표준 DTO 필드를 assertion으로 고정한다.
- [ ] Provider 실패 fixture에서 구조 누락 `DOCUMENT_STRUCTURE_NOT_FOUND`, 문서 미존재 `MEETING_NOT_FOUND`, 참가자 목록 실패 `PARTICIPANT_LIST_FAILED`, 기타 작업 실패 `DOCUMENT_FAILED` 및 공통 운영 분류 `DOCUMENT_FAILURE`, 원문/Secret 비노출을 검증한다.
- [ ] 의도적으로 불완전한 fake provider에 계약 모음을 실행한다. 결과: 테스트 로딩/컴파일은 성공하고 명세 assertion이 실패한다.

### 최소 구현

- [ ] `ProviderHealth`, `DocumentStructure`, `MeetingSummary`, `MeetingDocument`, `MeetingDocumentRef`, 표준 Participant와 명령 타입을 추가한다. 결과: API/Data 모델과 필드가 일치한다.
- [ ] EXT-003 signature의 `DocumentProvider` Port를 추가한다. 결과: Provider SDK import 없이 컴파일된다.
- [ ] 구조 누락과 Provider 실패 매핑을 구현한다. 결과: 구조 오류는 `DOCUMENT_STRUCTURE_NOT_FOUND`, 외부 작업 실패는 `DOCUMENT_FAILED`/`DOCUMENT_FAILURE`로 분류된다.
- [ ] 불완전 fake를 계약을 만족하는 결정적 fixture로 완성한다. 결과: 공통 계약 테스트의 모든 assertion이 통과한다.

### 통과 확인과 증거

- [ ] 대상 계약 테스트와 `./gradlew test`를 실행한다. 결과: 통과한다.
- [ ] `./gradlew clean build`를 실행한다. 결과: 종료 코드가 0이다.
- [ ] 의존성을 확인한다. 결과: Provider SDK 및 금지된 저장 인프라가 추가되지 않는다.
- [ ] 테스트/로그에 Provider 원문, Page raw JSON, Secret이 없는지 점검한다.
- [ ] 명령, 결과 및 비민감 자료를 `docs/evidence/TASK-002.01.md`에 기록한다.

## 중단 조건

- [ ] DTO 필드 또는 오류 매핑이 PRD/API/Data/Integration 계약과 충돌하면 기존 코드를 임의로 확장하지 말고 Issue에 결정 질문을 남긴다.
- [ ] 후속 Adapter 구현에만 필요한 Vendor 특화 기능이 나오면 공통 Port에 추가하지 않는다.

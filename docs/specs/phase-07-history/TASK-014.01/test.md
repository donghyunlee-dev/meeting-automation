# 검증 계획

> 📌 v1.9.0 변경 계약: 활성 전역 connection과 TASK-022.04의 문서 codec을 사용한다. 전환 완료 후 이전 Provider cache와 ID를 재사용하지 않는다. 일반 History의 목록 제한을 자료 이전 completeness 판정으로 사용하지 않는다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

## 자동화 테스트

Java 25 및 Gradle Wrapper로 변경 API와 직접 영향받는 provider codec 계약 테스트만 실행한다. Provider 경계는 fake provider 또는 mock HTTP fixture를 사용한다. 저장소 정책에 따라 전체 Backend suite, `clean build`, 로컬 서비스, 실제 계정 Provider 호출 및 별도 수동 acceptance/QA를 실행하지 않는다.

| ID | 입력/조건 | 기대 결과 | 증거 |
|---|---|---|---|
| API017-LIMIT-DEFAULT | `limit` 생략, 105개 fixture | 최신순 최대 100개가 `{data:{items}}`로 반환 | `MeetingListApiTests` |
| API017-LIMIT-BOUNDARY | `limit=1`, `limit=100`, 0 또는 101 | 각각 최대 1개/100개, 범위 밖은 400 `VALIDATION_FAILED` | `MeetingListApiTests` |
| API017-LIMIT-FORMAT | 비정수 query | 400 `VALIDATION_FAILED` | `MeetingListApiTests` |
| API017-EMPTY | Meetings child에 문서 없음 | 200 `{data:{items:[]}}` | `MeetingListApiTests` |
| API017-SORT | 서로 다른 offset 표현의 meetingAt 및 동일 instant의 서로 다른 offset/documents | 실제 시각 내림차순, 동률 documentId 오름차순 | `MeetingListApiTests.listUsesNewestRealMeetingTimeOrdersRosterAndAppliesLimit`, `MeetingListApiTests.equalMeetingInstantsWithDifferentOffsetsUseDocumentIdAscendingTieBreak` |
| API017-PAGINATION | 두 Provider의 여러 cursor/page, 여러 회의 문서 | manifest가 양쪽 provider의 모든 child page를 소비 | `DocumentTransferAdapterTests.manifestExhaustsMultipleCursorsForBothRootsAndPreservesRevision` |
| API017-DUPLICATE | 동일 meeting child ID가 페이지 간 중복 | history list는 한 decoded record만 반환하며 strict `readManifest()`는 기존대로 오류 | `DocumentTransferAdapterTests.historyListDeduplicatesMeetingIdsAcrossPagesButStrictManifestStillRejectsThem` (Notion/Confluence) |
| API017-CROSS-ROOT | participant와 meeting child ID 충돌 | history list 전체를 실패시킴 | `DocumentTransferAdapterTests.historyListStillRejectsCrossRootIdCollisionAndInvalidRosterReference` |
| API017-PARTICIPANTS | 문서의 participantIds 순서와 roster 순서가 다름 | `{id,name}`를 문서 지정 순서로 반환 | `MeetingListApiTests` |
| API017-DOCUMENT-URL | URL 누락 또는 비 HTTP(S) | `documentUrl=null` | `MeetingListApiTests` |
| API017-METADATA-FAILURE | required participant reference 누락 | 502 `DOCUMENT_FAILED`; 전체 응답 실패 | `MeetingListApiTests` |
| API017-PROVIDER-FAILURE | manifest 조회 실패 또는 codec page/metadata 오류 | 502 `DOCUMENT_FAILED`; 부분 목록 없음 | `MeetingListApiTests`, `DocumentTransferAdapterTests.invalidMarkersPagesReferencesAndCursorsFailWholeManifest` |
| API017-PRIVACY | 응답 생성 중 Secret/email/회의 body가 fixture에 존재 | 응답에 포함되지 않음 | `MeetingListApiTests` |

## 실행 결과

- 날짜: 2026-10-14.
- Java: OpenJDK 25.0.4.
- 명령: `./gradlew.bat test --tests com.meetingautomation.MeetingListApiTests --tests com.meetingautomation.document.DocumentTransferAdapterTests` (Backend 디렉터리에서 실행).
- 결과: `MeetingListApiTests` 7 passed, `DocumentTransferAdapterTests` 37 passed; 총 44 passed, 0 failed.
- 별도 `compileJava`/`compileTestJava`는 실행하지 않았다. focused `test --tests`가 Backend 및 선택 테스트를 컴파일한다.
- `git diff --check`: passed.
- 최초 실행 시 기본 `JAVA_HOME`이 설치되지 않은 JDK 17 경로를 가리켜 wrapper가 시작되지 않았다. 설치된 Java 25 경로를 프로세스 환경에 지정한 후 위 focused command가 통과했다.

## 릴리스 확인

- Root leader가 PR/CI/review를 확인하고 `docs/evidence/TASK-014.01/verification.md`에 최종 병합 증거를 기록한다.
- 검증 범위는 위 focused classes다. 별도 로컬 서비스 및 실제 Provider의 수동 pagination 확인은 저장소 지침상 수행하지 않는다.

## 🔗 전역 연결 소비자 실제 연동 회귀

이 작업은 resolver가 활성 global connection에서 Provider를 선택하고, TASK-022.04 codec이 양쪽 Provider의 child pagination과 manifest metadata를 처리하는 것을 focused API/adapter tests로 확인한다. 실제 전환 후 소비자 smoke test는 별도 acceptance/QA 정책에 따라 이 task에서 실행하지 않는다.

# 🧪 문서 서비스 기본 구조 초기화 검증

> TASK-022.02 · PRD v1.9.0 · QA 진행 상태: PASS

## 🔗 구현·수정·병합

- 작업 Issue: [#97](https://github.com/donghyunlee-dev/meeting-automation/issues/97)
- 구현 PR: [#104](https://github.com/donghyunlee-dev/meeting-automation/pull/104), tested head `a3ba3f5b56a5d547db3fb36557d131253328efa2`, merge `b9e472074a0dcd246b4442becdbc896b5ae8e540`
- Live QA에서 발견한 Confluence 검색 호환성 수정: [Issue #105](https://github.com/donghyunlee-dev/meeting-automation/issues/105), [PR #106](https://github.com/donghyunlee-dev/meeting-automation/pull/106), tested head `4cdae25ecee14021255cced9b6c6cf44e420d784`, merge `47ec4dce9b916f70a679f0a785ed0a244b67a409`
- PR #106 review: [현재 head 검토](https://github.com/donghyunlee-dev/meeting-automation/pull/106#pullrequestreview-5482741739) · blocking finding 없음
- 두 PR 모두 `master` 대상이다. PR #106의 backend CI와 Gradle Wrapper 검사가 통과했다.

## ✅ 코드 검증

- Windows Oracle JDK 25.0.3에서 `backend/`의 `.\gradlew.bat clean build --no-daemon --console=plain` 성공: 210 tests, 실패·오류·skip 0. 수정된 JAR는 Java 25 기준이다.
- `.\gradlew.bat test --tests com.meetingautomation.DocumentBootstrapProviderTests --no-daemon --console=plain` 성공: Provider 회귀 29 tests.
- QA agent가 정확한 head `4cdae25ecee14021255cced9b6c6cf44e420d784`에서 API(4), use-case(16), Provider(29) suites를 다시 실행했다: 총 49 tests, 실패·오류·skip 0.
- Browser QA는 backend-only 작업이라 N/A다.

## 🌐 승인된 live Provider QA

사용자가 테스트용 Notion·Confluence API를 이 개발 검증에 사용하도록 승인했다. 비밀 값, 계정 이메일, 페이지 ID 및 개인 문서 내용은 출력하거나 이 증거에 저장하지 않았다.

### Confluence

- Java 25의 수정된 정확한 JAR에서 `/actuator/health`가 `UP`, 설정 API가 초기 `UNCONFIGURED`를 반환했다.
- `windsoft` 사이트의 별도 global 테스트 space에서 제품 API draft 저장 및 연결 테스트 성공: 인증·space 접근 확인.
- 최초 초기화 operation이 `SUCCEEDED`; 설정은 `READY`, active 연결이 생기고 draft는 제거됐다.
- Confluence에서 다시 읽어 `Meeting Automation` root 한 개와 그 직속 `Meetings`, `Participants` 각각 한 개를 확인했다. 모두 같은 테스트 space에 있고 두 child 모두 같은 root를 가리킨다.
- 명시적으로 기존 root 재사용을 요청한 두 번째 initialization도 성공했고 새 페이지 없이 active root를 보존했다. 같은 completion 요청을 같은 idempotency key로 즉시 재전송했을 때 두 응답이 같은 operation ID를 반환했다.
- 유효하지 않은 space ID의 읽기 테스트는 거절됐으며 기존 `READY` active 연결은 그대로 유지됐다.
- live QA 중 초기 root 검색의 `title =` 쿼리는 HTTP 400, escaped exact phrase를 사용한 `title ~` 검색은 HTTP 200을 반환해 결함을 재현했다. 수정은 escaped phrase 검색과 후속 정확한 제목 비교를 사용한다. 이 형식은 [Atlassian Confluence Cloud CQL 문서](https://developer.atlassian.com/cloud/confluence/advanced-searching-using-cql/)에도 기술되어 있다.

### Notion

- 사용자가 지정하고 연결을 허용한 `회의록` 페이지 아래에 QA 전용 부모를 만들고, 제품 API의 Notion draft·읽기 테스트·초기화로 `Meeting Automation`과 직속 `Meetings`, `Participants`를 생성했다.
- Notion에서 각 페이지를 다시 fetch해 부모 계층과 bootstrap marker를 확인했다.
- 별도 `회의록` 직속 hierarchy에 대해서도 기존 root 재사용 및 completion 재전송을 검증했다. Notion draft가 `PREPARED`가 되고 이미 active였던 Confluence 연결은 바뀌지 않았다.
- 추가 페이지는 제목과 초기화 복구용 marker만 포함한다. 회의록이나 개인 콘텐츠를 읽거나 변경하지 않았다.

## 📋 결과

`BOOT-NOTION`, `BOOT-CONFLUENCE`, `BOOT-REUSE`, `BOOT-DUPLICATE`, `BOOT-RECOVERY` fixture, `BOOT-ACTIVATION`, 제품 API live creation/reuse, idempotent replay와 권한 거절 경계를 확인했다. 최초 live QA에서 발견된 Confluence 검색 결함은 Issue #105 / PR #106으로 수정·검토·QA 후 병합했다. 모든 필수 개발 검증이 통과했으며 TASK-022.02는 DONE으로 처리한다.

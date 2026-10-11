# 🧪 문서 서비스 기본 구조 초기화 검증

> TASK-022.02 · PRD v1.9.0 · QA 진행 상태: BLOCKED

## 🔗 구현과 병합

- Issue: [#97](https://github.com/donghyunlee-dev/meeting-automation/issues/97)
- 구현 PR: [#104](https://github.com/donghyunlee-dev/meeting-automation/pull/104)
- 테스트·리뷰·QA 대상 head: `a3ba3f5b56a5d547db3fb36557d131253328efa2`
- 리뷰: [현재 head 검토](https://github.com/donghyunlee-dev/meeting-automation/pull/104#pullrequestreview-5482109173) · blocking finding 없음
- 병합 commit: `b9e472074a0dcd246b4442becdbc896b5ae8e540`
- 병합 시각: 2026-10-11 15:08 KST

## ✅ 코드 검증

- Windows Oracle JDK 25.0.3에서 `backend/`의 `.\gradlew.bat clean build --no-daemon --console=plain` 성공: 210 tests, 실패·오류·skip 0. 생성 JAR class-file major version은 69다.
- PR CI의 Backend tests and build 및 Gradle Wrapper 검사 두 건이 동일 head에서 통과했다.
- PR #104의 API, use-case, Provider fixture suites를 다시 수행: 49 tests, 실패·오류·skip 0. Notion/Confluence 생성·기존 구조 재사용·중복·복구·활성화 경계를 mock으로 확인했다.
- Windows Java 25.0.3에서 정확한 JAR를 시작했다. `/actuator/health`는 `UP`; `GET /api/v1/document-setup`은 HTTP 200, ETag `"0"`, version 0, `UNCONFIGURED`, active/draft/operation 없음, `canCreateMeeting=false`를 반환했다.
- 허용 Origin과 합성된 존재하지 않는 draft ID로 초기화 guard를 호출했다. 응답은 HTTP 404 `DOCUMENT_DRAFT_NOT_FOUND`; 요청 전후 모두 version 0 `UNCONFIGURED`였고 외부 Provider adapter 호출이나 저장 변경은 없었다. 모르는 operation 조회는 HTTP 404 `DOCUMENT_OPERATION_NOT_FOUND`였다.
- 브라우저 QA는 backend-only 작업이라 N/A다. Render 배포와 영속 디스크 QA는 이 로컬 개발 task의 범위에 없다.

## ⏳ 남은 필수 QA

`docs/specs/phase-02-document-setup/TASK-022.02/test.md`가 요구하는 Notion 및 Confluence 테스트 공간의 실제 초기 구조 생성·기존 루트 재사용 QA는 아직 수행하지 않았다. 이 실행의 로컬 서비스에는 Provider draft/credential이 설정되지 않았다. 개인 workspace에 새 페이지를 쓰지 않았고, 연결된 Notion 또는 Atlassian connector를 통한 콘텐츠 변경도 하지 않았다.

따라서 fixture 기반 코드 QA와 안전한 로컬 API QA는 통과했지만 실제 Provider QA는 BLOCKED다. 사용자가 지정한 전용 Notion/Confluence 테스트 위치와 해당 위치에 bootstrap 페이지를 생성·재사용하도록 허용하면 이 검증을 이어서 완료할 수 있다. 어떤 토큰, 개인 페이지 내용, 회의록 본문도 이 Evidence에 기록하지 않는다.

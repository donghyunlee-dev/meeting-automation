# 작업 목록

## 사전 조건

- TASK-014.01 및 DocumentProvider 공통 계약이 병합되어 있다.
- Backend Java 21 / Gradle 설정에 따른다.
- 상세 조회·매핑·오류 경로는 fake Provider/HTTP fixture로 개발한다.

## 구현 단계

- [ ] `DocumentProvider.getMeeting` contract 테스트를 먼저 보완한다: 정상 상세, 없는 ID, metadata/body 파싱 오류, 권한/네트워크 오류가 구분되는지 실패 기준을 고정한다.
- [ ] Provider Adapter 테스트를 작성한다: Structured Minutes/Transcript 재구성, metadata 및 Participant ID/name 순서, 외부 URL, pagination/nested content 처리.
- [ ] Adapter가 Page body 및 metadata를 표준 `MeetingDocument`로 변환하도록 구현한다. Provider Not Found만 `MEETING_NOT_FOUND`로, 다른 실패는 기존 Document 오류로 매핑한다.
- [ ] Application service와 `GET /api/v1/meetings/{documentId}` Controller를 구현한다. 응답 DTO에서 Participant email/raw provider fields를 제외한다.
- [ ] API 테스트로 `{data}` envelope와 200/404/422/502 응답, read-only 특성, 본문/Secret 로그 비노출을 검증한다.
- [ ] Backend 관련 테스트와 빌드를 실행하고 `docs/evidence/TASK-014.02.md`에 명령 및 비민감 결과를 기록한다.

## 완료 확인

- 완료 기준 각각에 자동화 사례가 있다.
- PRD/API/Data/Provider port와 표준 DTO 필드가 일치한다.
- `git diff --check`와 변경 파일/로그 검토를 통과한다.

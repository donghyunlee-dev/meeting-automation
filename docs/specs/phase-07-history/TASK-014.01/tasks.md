# 작업 목록

> 📌 v1.9.0 변경 계약: 활성 전역 connection과 TASK-022.04의 문서 codec을 사용한다. 전환 완료 후 이전 Provider cache와 ID를 재사용하지 않는다. 일반 History의 목록 제한을 자료 이전 completeness 판정으로 사용하지 않는다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

## 사전 조건

- Backend는 PRD DEC-004 기준 Java 25와 Gradle Wrapper를 사용한다.
- `TASK-002.01` 표준 DocumentProvider 계약과 선택된 Provider Adapter, `TASK-010.02` Meeting 저장 형식이 준비되어 있어야 한다.
- 구현 테스트는 변경 및 직접 영향 코드에 한정해 `test --tests <FQCN>`으로 실행한다. 전체 Backend suite, `clean build`, 로컬 서비스 및 별도 수동 acceptance/QA는 실행하지 않는다.

## 구현 단계

- [x] API-017 service/controller 테스트를 먼저 작성하고 실패를 확인했다. 기본 limit, 범위 오류, 빈 결과, 최신순·동률 기준, 정렬 후 limit, ID 중복, 부분 결과 차단, 민감 데이터 비노출을 검증한다.
- [x] TASK-022.04 문서 codec의 Adapter 계약 테스트를 재사용해 Notion/Confluence 전체 child cursor pagination 및 metadata 검증을 확인한다.
- [x] 활성 global connection에서 선택된 Provider를 읽고, codec manifest 전체를 읽은 뒤 ID를 dedupe하고 metadata/roster를 조립한다. 실제 meetingAt 내림차순과 documentId 오름차순 tie-break 후 limit을 적용한다.
- [x] API-017 Controller와 공통 `{data:{items}}` envelope, 표준 validation/provider 오류 응답을 연결했다.
- [x] HTTP 테스트로 정상·빈 목록·Provider/필수 metadata 오류와 민감 데이터 비노출을 확인했다.
- [x] Java 25 focused tests 39개와 `git diff --check`를 실행했다. 정확한 명령 및 범위는 [검증 계획](./test.md)에 기록했다.

## 완료 확인

- 구현 결과 및 focused 테스트 결과가 [검증 계획](./test.md)에 기록되어 있다.
- `git diff --check` 및 변경 파일 검토가 통과한다.
- API-017 및 Data/DocumentProvider 계약과 구현 DTO가 일치한다.

- TASK-022 전환 후 실제 소비자 검증은 별도 로컬 서비스/수동 acceptance를 금지하는 저장소 정책에 따라 이 focused 구현 단계에서 수행하지 않는다. 자동화 검증은 활성 connection resolver 및 두 provider의 TASK-022.04 codec pagination 계약을 확인한다.

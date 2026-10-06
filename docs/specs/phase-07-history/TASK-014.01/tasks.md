# 작업 목록

## 사전 조건

- Backend Java 21 / Gradle 기반 프로젝트에서 진행한다.
- `TASK-002.01` 표준 DocumentProvider 계약과 선택된 Provider Adapter, `TASK-010.02` Meeting 저장 형식이 준비되어 있어야 한다.
- 이 계획은 문서 설계 산출물이다. 구현 시 저장소에 설정된 Gradle test/build 절차와 테스트 우선 순서를 따른다.

## 구현 단계

- [ ] API-017 service/controller 테스트를 먼저 작성한다: 기본 limit, 1/100 경계, 잘못된 형식·범위, 빈 결과, 최신순·동률 정렬, limit 적용을 검증한다. 초기 실패가 계약 차이 때문인지 확인한다.
- [ ] Adapter 테스트를 작성한다: Provider child cursor pagination, 페이지 사이 최신 문서 선택, 중복 ID 제거, participant 이름/순서 매핑, metadata 및 페이지 오류를 검증한다.
- [ ] Controller query 검증과 service 정렬/limit을 구현한다. 공통 응답 envelope와 표준 오류 코드를 재사용한다.
- [ ] 선택된 Adapter의 MeetingSummary 목록 조립을 완성한다. participant roster는 요청당 한 번 조회해 ID map을 사용하고, 필수 데이터 오류는 표준 Provider 오류로 변환한다.
- [ ] HTTP 통합 테스트를 작성/연결해 정상·빈 목록·Provider 실패의 200/200/502 계약과 민감 데이터 비노출을 확인한다.
- [ ] Backend 관련 단위 및 통합 테스트와 빌드를 실행하고 `docs/evidence/TASK-014.01.md`에 명령, 환경, 결과를 비민감 정보로 기록한다.

## 완료 확인

- 모든 단계의 테스트 결과가 [검증 계획](./test.md)에 기록되어 있다.
- `git diff --check` 및 변경 파일 검토가 통과한다.
- API-017 및 Data/DocumentProvider 계약과 구현 DTO가 일치한다.

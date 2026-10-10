# Confluence Cloud Adapter 작업 목록

> 📌 v1.9.0 변경 안내: 이 문서는 완료된 TASK-002.03의 당시 구현/검증 이력이다. Backend env로 선택·credential을 고정하는 계약과 초기 구조 생성 제외 범위는 새 TASK-022에서 변경한다. 현재 구현 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)와 TASK-022 패키지다. 일반 Health/탐색은 계속 읽기 전용이며 명시적 초기화만 구조를 생성한다. 기존 DONE/검증 증거는 보존한다.

구현 순서는 [구현 계획](./plan.md), 상세 동작과 경계는 [명세](./spec.md), 검증 절차는 [테스트 계획](./test.md)을 따른다. 모든 단계는 `TASK-002.01` (#6) 계약에 의존한다.

## Backend 구현

- [ ] 설정 DTO/properties에 `CONFLUENCE_BASE_URL`, `CONFLUENCE_ACCOUNT_EMAIL`, `CONFLUENCE_AUTH_TOKEN`, 숫자형 `DOCUMENT_ROOT_ID`를 연결한다. 결과: 필수값 누락·잘못된 URL/Page ID는 configured=false 또는 기존 설정 검증 계약에 따라 안전하게 처리하고 값 자체는 출력하지 않는다.
- [ ] Confluence HTTP client를 fakeable한 경계로 둔다. 결과: 단위 테스트에서 네트워크 없이 status/header/body를 공급할 수 있다.
- [ ] Basic 인증 생성과 요청을 테스트 우선으로 구현한다. 결과: `email:token` UTF-8 Base64 Authorization header가 전송되고 비밀값은 logger/예외에서 제거된다.
- [ ] API v2 direct-children `_links.next`/`Link` pagination을 테스트 우선으로 구현한다. 결과: opaque next URL을 same-origin에서 순서대로 요청하고 반복 링크 또는 종료 응답 후 호출을 멈춘다.
- [ ] Page 타입과 정확한 제목을 필터해 공통 `DocumentStructure`를 구성한다. 결과: `Meetings`, `Participants` 각 하나일 때 ID만 표준 DTO로 반환한다.
- [ ] 빈/누락/중복 구조 오류를 매핑한다. 결과: 자동 생성 없이 `DOCUMENT_STRUCTURE_NOT_FOUND`가 일관되게 발생한다.
- [ ] provider status 및 network error를 `ProviderHealth`, `DOCUMENT_FAILED`/`DOCUMENT_FAILURE`로 정규화한다. 결과: 응답 본문과 credentials는 오류 메시지에 없다.
- [ ] Adapter를 공통 계약 테스트에 연결한다. 결과: 같은 계약 suite가 Cloud fake를 대상으로 실행된다.
- [ ] `./gradlew test`, `./gradlew clean build` 및 의존성 diff 검사를 실행한다. 결과: 전체 회귀 통과, Provider SDK 및 금지 DB/Redis dependency 미추가.
- [ ] `docs/evidence/TASK-002.03.md`를 작성한다. 결과: 실행 명령, 테스트 결과, 환경 설정 키 이름과 비민감 증거가 기록된다.

## 완료 결과

- [ ] 정상/빈 구조/권한/pagination/비밀 비노출 검증이 모두 통과한다.
- [ ] 구현 PR에 연결 가능한 변경 파일, 명령 결과 및 알려진 제한이 evidence에 기록된다.

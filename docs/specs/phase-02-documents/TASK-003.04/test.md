# 검증 계획

## 자동화 테스트

| ID | 준비/입력 | 기대 결과 | 증거 |
|---|---|---|---|
| SCR-012-01 | 목록 API pending | 최초 조회 indicator, 중복 콘텐츠 없음 | Component test |
| SCR-012-02 | API `{items:[]}` | Empty 안내와 추가 action | Component test |
| SCR-012-03 | API 목록 2건 | name/email와 수정 action 표시 | Component test |
| SCR-012-04 | 검색어 name/email 일부 문자열 | 일치 행만 표시, 대소문자 무관 | Component test |
| SCR-012-05 | 일치 행 없는 검색어 | 검색 결과 Empty와 검색 초기화 action | Component test |
| SCR-012-06 | GET API 오류 후 재시도 성공 | 안전 오류 표시 후 목록 Ready | Component/API mock test |
| SCR-012-07 | create 폼 유효 입력 | POST `{name,email}`와 Idempotency-Key 전송, 성공 목록 반영 | Component/API mock test |
| SCR-012-08 | create 응답 대기 중 같은 제출 재시도 | 같은 Key로 한 결과 재사용 | API client/component test |
| SCR-012-09 | create 입력 오류/API 실패 | 필드 오류 또는 안전 오류를 보여주고 입력값 유지 | Component/API mock test |
| SCR-012-10 | row edit, name-only/email-only 수정 | PATCH는 변경 필드만 보내며 성공 응답으로 row 갱신 | Component/API mock test |
| SCR-012-11 | 수정 400/404/502 오류 | 오류 종류에 맞는 안전한 복구 안내, 폼 값 유지 | Component/API mock test |
| SCR-012-12 | viewport/keyboard 검사 | 44×44px target, visible focus, label/error association, 모바일 overflow 없음 | DOM/accessibility 및 browser QA |

현재 앱이 존재하지 않아 검증 명령은 구현 시 확정한다. 프로젝트에 test/build script가 없으면 이 Task에서 설정한 뒤 해당 script를 실행하고 증거를 남긴다.

## 수동 QA

- Settings에서 Participants 화면 진입 후 정상/빈/오류 목록을 확인한다.
- 이름/email 각각으로 검색하고 검색어를 지워 복구한다.
- 새 roster record를 생성하고 이름만/email만 수정해 목록에 반영되는지 확인한다.
- 생성 요청의 네트워크 응답 전 재시도에서 같은 Idempotency-Key가 전달되는지 확인한다.
- 모바일 폭, 키보드만 사용한 순서, 오류 메시지의 입력 필드 연결, focus ring을 확인한다.

## 릴리스 확인

비밀 값이나 Provider 원문이 화면에 노출되지 않는지 확인한다. 실제 Notion/Confluence credentials 통합 점검은 Backend Provider 작업 릴리스 QA와 별도로 한다.

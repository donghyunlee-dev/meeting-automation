# 🔎 최초 실행 연결 위저드와 공용 설정 화면 검증 계획

> TASK-022.03 · PRD v1.9.0
> GitHub Issue: [#98](https://github.com/donghyunlee-dev/meeting-automation/issues/98)

## 🧪 자동 검증

| 사례 | 준비·입력 | 기대 결과 |
|---|---|---|
| WIZARD-ENTRY | UNCONFIGURED/READY/STORAGE_UNAVAILABLE App Config | 미설정의 회의/참석자 route 차단, READY 정상 진입, 저장소 오류 복구 안내 |
| WIZARD-GUIDE | Notion/Confluence 선택과 뒤로 이동 | Provider별 필드·권한 가이드 표시; 다른 Provider의 이전 입력 제거 |
| WIZARD-TEST | 입력 오류·테스트 실패·성공·10분 만료 | 필드 오류와 재시도, TESTED인 동일 revision만 완료 가능, 입력 변경 시 결과 무효 |
| WIZARD-FINISH | 초기화 202·진행·완료·실패 fixture | 준비될 페이지 구조 확인, 중복 클릭 방지, 완료 뒤 config/roster 재조회 |
| WIZARD-RESUME | 진행 중 새로고침·다른 브라우저·revision 충돌 | credential 재노출 없이 public 상태 복원, 같은 operation 조회, 412 재읽기 |
| WIZARD-SECRET-A11Y | 비밀번호 입력·360px·keyboard | 웹 저장소/URL/console에 키 없음, focus/label/error 연결, 가로 넘침 없음 |

Fixture는 synthetic 문서/참석자를 사용하고 외부 Provider 요청은 mock한다. 각 spec 수용 기준과 같은 사례 ID를 사용한다. 구현 전 테스트를 먼저 실패시키고 변경 후 관련 회귀를 실행한다.

## 💻 명령

frontend에서 `npm test`, `npm run lint`, `npm run build`를 실행한다. 통합 QA는 로컬 FE/BE의 정확한 PR head를 확인한 뒤 수행한다.

## 👀 수동 QA

360px 모바일 viewport에서 선택→가이드→입력→테스트→완료를 실제 로컬 Backend와 확인한다. 브라우저 종료 후 재접속과 접근 가능한 error/focus를 확인한다. 실제 녹음 실기기 검증은 이 위저드 작업의 합격 조건이 아니다.

## 📋 증거와 합격 조건

각 사례 결과, 명령/exit code, head SHA, 안전한 화면/HTTP 요약, 검증 불가 조건을 docs/evidence/TASK-022.03/verification.md에 기록한다. 필수 사례 미충족은 DONE이 아니며 provider token/email/Transcript/Minutes를 Evidence에 넣지 않는다. 제품 계정과 원본 삭제가 추가되지 않았는지 확인한다.

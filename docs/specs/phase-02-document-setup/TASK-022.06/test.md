# 🔎 문서 서비스 변경과 자료 이전 화면 통합 검증 검증 계획

> TASK-022.06 · PRD v1.9.0
> GitHub Issue: [#101](https://github.com/donghyunlee-dev/meeting-automation/issues/101)

## 🧪 자동 검증

| 사례 | 준비·입력 | 기대 결과 |
|---|---|---|
| CHANGE-ENTRY | READY Settings에서 변경 선택 | Provider 선택부터 같은 위저드 재진입; 테스트 중 source 표시·활성 상태 유지 |
| CHANGE-CHOICE | COPY_ALL 기본 선택·START_EMPTY 명시 확인 | 복사 범위와 원본 보존 안내, 선택에 맞는 API-028 한 번 요청 |
| CHANGE-PROGRESS | counts·단계·FAILED·INTERRUPTED·RECONCILIATION_REQUIRED | 안전한 상태·재개/취소 안내; 불명확한 생성 자동 재시도 없음 |
| CHANGE-REFRESH | 진행 중 refresh·다른 브라우저·중복 클릭 | 같은 operation을 조회, 중복 switch 방지, revision 충돌 재읽기 |
| CHANGE-INTEGRATION | 최초 Notion/Confluence 및 양방향 전환 | 설정/참석자 API 실통합과 Provider 복사본 read-back 검증, 새 roster 조회 및 후속 History 소비자 계약 fixture 검증 |
| CHANGE-GATE | 전환 lock·오래된 New Meeting form | 참석자 실제 쓰기 차단과 새 roster 조회, 후속 Session/History 상태 gate 계약 fixture 검증, 로그인 화면 없음 |
| CHANGE-ACCESSIBILITY | 360px·keyboard·부분 API 장애 | 영역별 오류/재시도·focus·상태 알림, 키 저장·재노출 없음 |

Fixture는 synthetic 문서/참석자를 사용하고 외부 Provider 요청은 mock한다. 각 spec 수용 기준과 같은 사례 ID를 사용한다. 구현 전 테스트를 먼저 실패시키고 변경 후 관련 회귀를 실행한다.

## 💻 명령

frontend에서 `npm test`, `npm run lint`, `npm run build`를 실행한다. 통합 QA는 로컬 FE/BE의 정확한 PR head를 확인한 뒤 수행한다.

## 👀 수동 QA

리더가 정확한 PR head의 로컬 FE/BE를 시작한 뒤 QA agent가 설정/참석자 화면의 최초 연결·양방향 변경·중단 재개 흐름과 Provider 복사본을 검증한다. 아직 없는 회의 생성/History endpoint의 실통합은 합격 조건이 아니다. 실제 양 Provider credential 부재는 해당 integration QA BLOCKED로 기록한다. 이 작업 완료 전 PR #95를 병합하지 않는다.

## 🔗 후속 실제 연동 검증

- TASK-004.04/004.05: API-006과 New Meeting의 READY/미설정/전환 중/stale version 실제 요청 및 동시 생성·전환 gate.
- TASK-010.02: 실제 Publish가 활성 snapshot/동일 codec/전환 lock을 소비하는지 검증.
- TASK-014.01/014.02: API-017/018이 이전된 target ID·본문·참조를 조회하고 source cache를 재사용하지 않는지 검증.
- TASK-021.03: 위 기능이 모두 구현된 후 전환→새 회의 생성→History 재조회 회귀를 실제 FE/BE로 검증.

이 필수 후속 검증을 해당 작업의 테스트 문서와 Issue에 기록하고, TASK-022.06에서 완료했다고 주장하지 않는다.

## 📋 증거와 합격 조건

각 사례 결과, 명령/exit code, head SHA, 안전한 화면/HTTP 요약, 검증 불가 조건을 docs/evidence/TASK-022.06/verification.md에 기록한다. 필수 사례 미충족은 DONE이 아니며 provider token/email/Transcript/Minutes를 Evidence에 넣지 않는다. 제품 계정과 원본 삭제가 추가되지 않았는지 확인한다.

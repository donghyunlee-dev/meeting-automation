# 🔎 전역 문서 연결 설정 저장과 연결 테스트 검증 계획

> TASK-022.01 · PRD v1.9.0
> GitHub Issue: [#96](https://github.com/donghyunlee-dev/meeting-automation/issues/96)

## 🧪 자동 검증

| 사례 | 준비·입력 | 기대 결과 |
|---|---|---|
| SETUP-EMPTY | 유효 저장 경로·암호화 키와 빈 저장소 | version 0, UNCONFIGURED, provider null, setup.required true; 제품 로그인 없이 상태 조회 |
| SETUP-PERSIST | 두 Provider draft 저장 뒤 프로세스 재생성 | 정규화한 선택·자격 증명·revision 복원, 저장 파일에 평문 token/email 없음 |
| SETUP-STORAGE | 키 누락, 쓰기 실패, 손상 파일, 복호화 오류 | STORAGE_UNAVAILABLE 및 저장 거절; 기존 파일 보존; 빈 설정으로 초기화하지 않음 |
| SETUP-CONCURRENCY | 같은 revision의 두 draft 요청·동일 멱등 키 재전송 | 한 변경만 성공, 오래된 요청 412, 다른 payload 409, restart 뒤에도 결과 재사용 |
| SETUP-TEST | Notion/Confluence 인증 성공·401/403·잘못된 부모 | 정확한 읽기 테스트 결과; 페이지 쓰기 0건; 원문 오류/credential 미노출 |
| SETUP-SECRET | 키 입력·URL credential·사설 IP·허용하지 않은 Origin | 입력 검증/Origin 거절; GET 응답·로그·영속 평문에 Secret 없음; 쓰기 미검증은 UNVERIFIED |

Fixture는 synthetic 문서/참석자를 사용하고 외부 Provider 요청은 mock한다. 각 spec 수용 기준과 같은 사례 ID를 사용한다. 구현 전 테스트를 먼저 실패시키고 변경 후 관련 회귀를 실행한다.

## 💻 명령

backend에서 Windows `.\gradlew.bat test`, `.\gradlew.bat clean build`; macOS/Linux `./gradlew test`, `./gradlew clean build`를 실행한다.

## 👀 수동 QA

실제 Provider read-only 연결 테스트는 제공된 테스트 workspace에서만 실행한다. Render 영속 디렉터리와 재배포 복원 검증은 배포 환경이 준비되기 전 BLOCKED로 기록하며 자동 fixture로 대신 PASS 처리하지 않는다.

## 📋 증거와 합격 조건

각 사례 결과, 명령/exit code, head SHA, 안전한 화면/HTTP 요약, 검증 불가 조건을 docs/evidence/TASK-022.01/verification.md에 기록한다. 필수 사례 미충족은 DONE이 아니며 provider token/email/Transcript/Minutes를 Evidence에 넣지 않는다. 제품 계정과 원본 삭제가 추가되지 않았는지 확인한다.

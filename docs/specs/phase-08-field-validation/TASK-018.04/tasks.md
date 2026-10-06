# 실행 작업

## 단계

- 선행 Evidence 세 파일과 Issue #65~#67 상태를 확인한다. 완료 결과: 각 run 경로와 현장 수행 여부가 확인되고 없는 증거를 완료로 가정하지 않는다.
- 발견 결함을 재현 run, expected/actual, OS/browser/MIME, 영향, API/UX 경계, owner, 우선순위가 있는 register로 정리한다. 완료 결과: 이번 변경과 별도 결정 필요 항목이 구별된다.
- 가장 높은 위험 결함의 기존 build 재현과 minimal regression test를 먼저 작성한다. 완료 결과: 수정 전 test가 실패하고 fixture가 콘텐츠/개인정보를 담지 않는다.
- 작은 FE/BE 수정으로 재현 결함을 해결한다. 완료 결과: 관련 test는 통과하고 API-006~009 및 저장/ACK 정책 계약이 유지된다.
- 나머지 선택 결함을 각각 test-first 순서로 수정한다. 완료 결과: 한 회귀 test가 하나의 관찰된 결함을 확인하고 관계 테스트가 비회귀다.
- 전체 automated test/lint/build 및 API contract regression을 실행한다. 완료 결과: 저장소에 실제 정의된 명령과 commit별 결과가 Evidence에 남는다.
- 영향 받는 Android Chrome/iOS Safari 조합에서 최초 실패 조건 그대로 현장 재검증한다. 완료 결과: 성공/미해결이 초기 결과와 짝을 이루며, 다른 조합을 추정하지 않는다.
- 브라우저 support matrix와 제한/오류 안내를 최종화한다. 완료 결과: 실제 검증 조합만 확정하고 untested 조합은 미검증으로 기록한다.
- 추가 아키텍처/PRD/API 결정 항목을 별도 ADR/Issue로 연결한다. 완료 결과: task 해결 범위 밖 항목이 추적 가능하다.
- `docs/evidence/TASK-018.04.md`와 QA object cleanup을 확인한다. 완료 결과: commit, test, device run, browser matrix, unresolved issue, 민감데이터 배제와 정리 상태가 남는다.

## 의존 관계

- Evidence review와 현장 수행 확인은 defect register 및 코드 수정 전에 수행한다.
- 재현 가능한 실패와 regression test가 있어야 코드 수정이 시작된다.
- FE/BE 독립 수정의 관련 자동화 검증을 각각 통과한 뒤 모바일 end-to-end 재검증을 수행한다.
- Browser support matrix는 모든 영향 시나리오의 재검증 결과 후 작성한다.
- 미해결 계약/아키텍처 사항은 완료 표기 전 별도 decision Issue와 연결한다.

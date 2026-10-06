# Android/iOS 전체 화면 회귀 작업 목록

- [ ] TASK-018.04 verified device/browser matrix와 TASK-020.01~.03 Evidence를 모으고 non-production build/mock services를 고정한다. 의존: TASK-020.01~.03, TASK-018.04. 완료 결과: Android Chrome/iOS Safari 각각의 run sheet 및 safe test account 준비.
- [ ] 양 실기기에서 SCR-001~012 route 및 해당 modal/drawer를 순회한다. 의존: run sheet. 완료 결과: screen/state별 pass/fail/not-run과 route evidence ID.
- [ ] Android Chrome에서 short synthetic meeting flow를 실행한다. 의존: route matrix 준비. 완료 결과: create→permission→record→stop/upload→API-009→processing→review/share/history route 확인, 참가자 outbound 0건.
- [ ] 같은 short flow와 독립 fixture를 iOS Safari에서 반복한다. 의존: Android run 기록 및 공통 QA environment. 완료 결과: 동일 metric, Safari 차이/제한 기록.
- [ ] 권한 오류, processing success/failure recovery, read-only lists/details, settings, participants와 responsive/layout/accessibility spot checks를 확인한다. 의존: 각 기기 core flow. 완료 결과: 실패 복구 경로와 touch/screen reader 결과가 run별 기록.
- [ ] 재현 결함을 원인 추정과 분리해 등록/연결하고, test objects를 정리한다. 의존: 전체 run. 완료 결과: 결함 Issue 및 cleanup 증거, 실제 수행하지 않은 조합은 미검증 표시.
- [ ] 변경 없는 전체 suite 및 release regression 결과를 기록한다. 의존: 결함 disposition. 완료 결과: `docs/evidence/TASK-020.04.md`가 기기 matrix/route/flow/evidence/run commit을 담는다.

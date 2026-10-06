# 구현 작업

모든 변경은 TASK-017.01/#60, TASK-017.02/#61, TASK-017.03/#62 및 PRD v1.8.1 계약에 따른다. 합성 canary만 사용하고 canary 값을 출력하지 않는다.

## 단계

1. 저장소 FE/BE manifest와 기존 scanner/build/test 경로를 확인하고 보안 요구사항→테스트 ID matrix를 만든다. 완료 증거: 실제 test source 위치·명령·검사 제외 경로가 기록된다.
2. scanner scope/normal/violation fixture 테스트를 먼저 작성한다. 완료 증거: tracked 대상만 읽고 개인 `.env*`·운영 store를 제외하며 위반 출력에도 canary 값이 없다.
3. FE public env allowlist와 production artifact canary 검사를 연결한다. 완료 증거: synthetic Backend secret이 bundle에 들어가면 검사가 실패하고 원문 값은 report에 없다.
4. Backend DTO/error/log/Admin Slack allowlist 및 redaction regression tests를 작성한다. 완료 증거: synthetic credential·Audio·Transcript·Minutes·PII·Provider body가 금지 sink에서 검출되지 않는다.
5. private object storage 설정/adapter security tests를 작성한다. 완료 증거: public access, Browser credential/key, 임의 Session object 접근을 거부하고 Backend API streaming만 허용된다.
6. object retention/cleanup lifecycle tests를 작성한다. 완료 증거: 성공·사용자 종료·만료 후 app object 삭제, max 24h 차단, retry lease, restart 후 expired object sweep이 검증된다.
7. failure Document 및 delivery suppression tests를 통합한다. 완료 증거: 문서 allowlist 밖 콘텐츠가 없고 Email/Slack 호출 및 attendee delivery가 0건이다.
8. provider deployment settings와 HTTPS/private/encryption/backup retention 근거를 비밀값 없이 확인한다. 완료 증거: 접근 모드·region·보존 설정과 적용 범위가 기록된다.
9. repository-defined focused security checks 및 FE/BE 관련 회귀 build/test를 실행한다. 완료 증거: 정확한 명령/commit/환경과 sanitized pass/fail summary가 남는다.
10. `docs/evidence/TASK-017.04.md`를 작성하고 최종 diff/출력에서 민감정보가 없는지 확인한다. 완료 증거: evidence 문서에 canary, raw response/log, Audio, Transcript, Secret이 없다.

## 의존 관계

- 1~2단계 결과가 정해진 뒤에만 scanner 변경 및 출력 안전성 테스트를 시작한다.
- 3~4단계는 코드/설정 경계 테스트이며 Audio object integration을 기다리지 않아도 병렬 작성할 수 있다.
- 5~7단계는 TASK-017.02/.03 통합 계약을 필요로 한다.
- 8단계는 배포에 사용할 private object storage provider가 선택되어 non-production 설정에 연결된 뒤 완료한다.
- 9~10단계는 모든 자동 및 수동 검증을 마친 뒤 수행한다.

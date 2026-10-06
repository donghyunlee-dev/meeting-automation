# 작업 목록

## 사전 조건

- TASK-001.05 env sample checker 및 FE/BE build layout이 존재한다.
- Backend error/log mapping과 public API DTO가 안정되어 있다.
- 모든 test fixture는 synthetic canary만 사용한다.

## 구현 단계

- [ ] 포함/제외 경로 tests를 먼저 작성한다: source/template/build/API/log 포함, 개인 .env 및 운영 Secret store 제외.
- [ ] Normal/violation fixtures로 credential-like canary가 sample/build/API/log에 남지 않음을 검증한다.
- [ ] Existing Node checker를 제한된 범위에서 확장하거나 focused verification command를 추가한다. 발견값 대신 file/rule만 보고한다.
- [ ] FE public env allowlist/build scan과 API DTO allowlist/error details tests를 연결한다.
- [ ] Fake Provider/API/processing/Email/Notification/Admin failures를 실행해 logs에서 Secret/Audio/Transcript/Minutes/email 원문이 없는지 검사한다.
- [ ] HTTPS deployment config check를 추가한다. localhost HTTP fixture를 production HTTPS 증거로 오인하지 않는다.
- [ ] FE/BE security tests/build를 실행하고 docs/evidence/TASK-017.01.md에 비민감 결과를 기록한다.

## 완료 확인

- 검사 범위와 제외 경로가 문서화되어 있다.
- Synthetic canary positive/negative tests가 결정적이다.
- 결과 출력과 Evidence에 실제 Secret/PII가 없다.
- diff check, status 및 artifact review를 통과한다.

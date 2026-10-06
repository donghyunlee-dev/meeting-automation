# 실행 작업

## 단계

- TASK-019.01 Issue/Evidence에서 원격 3인 baseline, config hash, scorecard revision 및 환경을 확인한다. 의존: 직접 선행의 remote Evidence. 완료 결과: 5인 run 전 재현 가능한 comparison 기준을 식별한다.
- 5인 Korean scripted dialogue와 ground truth를 준비하고 독립 검수한다. 의존: 고정 3인 script/metric definition. 완료 결과: five anonymous voices, text, intervals, version/hash가 freeze된다.
- 3인 run과 같은 room/device/OS/browser/MIME/provider/app/API/prompt 환경을 맞춘다. 의존: fixture freeze 및 remote baseline. 완료 결과: run sheet와 불일치 조건 표가 완성된다.
- 세 개 voice playback position과 거리/level을 설치·기록한다. 의존: 동일 phone/room 배치. 완료 결과: 3인과 5인 위치의 조건 차가 수치/설명으로 남는다.
- 고정 fixture/config로 세 개 독립 Session을 녹음/처리한다. 의존: run sheet와 config hash 확인. 완료 결과: run ID, actual MIME, processing outcome이 확인된다.
- 동일 CER/WER/DER scorecard로 각 5인 결과를 계산한다. 의존: 결과 Review payload와 frozen reference. 완료 결과: 지표/분모/run variation이 확인된다.
- Reviewer가 다섯 Speaker를 다섯 QA Participant에 매핑한다. 의존: 표준 speaker list 및 익명 mapping key. 완료 결과: correct/duplicate/wrong/unmapped completion rate를 계산한다.
- API-011 뒤 API-010 snapshot에서 전체 segment propagation을 확인한다. 의존: mapping update 성공. 완료 결과: speakerId별 Participant 표시와 version이 일치한다.
- 3인/5인 comparison table과 settings deviation을 만든다. 의존: 5인 aggregate 및 원격 3인 baseline. 완료 결과: metric delta 및 해석 제한이 분리된다.
- failure Audio cleanup/no attendee delivery 및 fixture cleanup을 확인한다. 의존: 각 Session outcome. 완료 결과: object/action/message count만 남는다.
- TASK-019.03가 재사용할 baseline Evidence를 작성한다. 의존: 비교표/정리 확인. 완료 결과: 음성·대화내용 없이 source run/config/fixture hash를 추적할 수 있다.

## 의존 관계

- Remote 3인 baseline, scorecard revision과 exact provider config는 fixture recording 전 완료 조건이다.
- Fixture/room setup이 고정된 후에만 세 번 반복 run을 시작한다.
- Review speaker mapping은 processing output과 정답 mapping key가 확인된 뒤 수행한다.
- 비교/튜닝 근거 작성 전에 3인/5인 지표와 condition deviation을 모두 계산한다.
- Cleanup/Evidence는 모든 run 결과 freeze 뒤 수행한다.

# Minutes 재생성 pipeline 회귀 검증 작업

1. API-013 application/async worker 경로, API-009 initial pipeline 경로, DI composition root, 기존 Spring integration test와 job completion helper를 찾는다. 완료 결과: 실제 route-to-worker integration seam 및 test 실행 위치가 기록된다.
2. 외부 Provider/Audio storage 없이 Session Review fixture, Transcript/mapping, Template catalog와 결정적 `MinutesGenerationProvider` fake를 구성한다. 완료 결과: test fixture가 개인정보/secret 없이 생성된다.
3. 계측 가능한 assembly/audio read, `TranscriptionProvider`, `DiarizationProvider`, Minutes generation ports를 spy로 연결한다. 완료 결과: test가 각 port의 count/order를 assertion하고 호출 내용을 출력하지 않는다.
4. API-013 정상 202 이후 worker completion을 결정적으로 기다리는 integration test를 작성한다. 완료 결과: generator 호출과 세 Audio/STT/Diarization port의 0회가 함께 검증된다.
5. provider failure, 동일 idempotency replay, duplicate worker delivery tests를 작성한다. 완료 결과: 실패 rollback/중복 방지와 regeneration 전 구간의 Audio/STT/Diarization 0회가 증명된다.
6. API-010 terminal snapshot, version 증가, Transcript/Speaker 불변 및 provider error log redaction을 assertion한다. 완료 결과: success/failure 모두 task00901 API-010 contract와 일치한다.
7. API-009 initial processing positive control로 test spies/wiring이 실제 사용되는지 확인한다. 완료 결과: Audio assembly → STT → Diarization의 각 1회 호출/순서가 확인된다.
8. backend module integration tests와 `./gradlew test`, `./gradlew clean build`를 실행한다. 완료 결과: command/작업 위치, timeout 정책, test report 및 각 시나리오 call count를 Issue #42에 남긴다.

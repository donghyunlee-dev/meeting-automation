# 실행 작업

순서는 기존 TASK-005.03~005.06 계약을 전제로 한 실기기 현장 검증이다. 각 작업의 결과는 증거로 확인하며 recorder/uploader 코드는 변경하지 않는다.

## 단계

- 비운영 QA deployment, Android Chrome 및 iOS Safari 실기기, fault injection 경로와 cleanup 권한을 확인한다. 완료 결과: 양쪽 기기/build와 장애 주입 방법이 명시된다.
- 고유 sequence/`chunkId`/크기/hash가 있는 합성 Chunk 집합과 기대 집합을 생성한다. 완료 결과: payload 자체를 저장하지 않고 검증 manifest만 남긴다.
- 일부 수신/일부 missing/일부 ACK된 local 집합을 설정하고 정상 reconcile한다. 완료 결과: API-008 우선 조회, missing-only 전송, 직렬 순서, remote ACK 후 선택 삭제를 확인한다.
- PUT 수락 후 응답 유실 시나리오를 실행한다. 완료 결과: 동일 key/body 재시도 또는 API-008 조정 뒤 sequence당 하나의 server object와 동일 hash/byte가 확인된다.
- network/timeout/408/429/5xx retryable 시나리오를 오류별로 실행한다. 완료 결과: 1/2/4/8/16초 retry delay와 최대 다섯 번 retry 후 수동 retry 안내가 관찰된다.
- 4xx validation/conflict와 `SESSION_NOT_FOUND`를 실행한다. 완료 결과: 자동 retry가 없고 local pending 폐기 및 처리 handoff가 발생하지 않는다.
- 중간 sequence ACK 직전/직후 실패와 page reload/browser reopen을 수행한다. 완료 결과: 확인된 ACK만 삭제하고 재접속 뒤 API-008부터 다시 대조해 missing만 보낸다.
- quota/IndexedDB transaction 실패를 재현한다. 완료 결과: 저장/전송을 성공으로 표시하지 않고 pending 보존 상태/오류 안내/지원 조치 결과를 기록한다.
- 각 실패 run에서 API-009 gate를 확인한다. 완료 결과: 미전송/미확인 pending이 존재하는 동안 API-009가 호출되지 않는다.
- Android/iOS 최종 수신 집합을 기대 sequence/hash/byte와 대조한다. 완료 결과: 누락 0, 중복 0, sequence당 한 가지 payload 또는 실패 근거가 기록된다.
- QA object와 synthetic IndexedDB Session을 정리한다. 완료 결과: 정리 후 object 수량 0 또는 격리된 삭제 후속 상태가 남는다.
- 비민감 Evidence와 브라우저별 결과를 작성한다. 완료 결과: 재현 가능한 절차, 처음/재실행 결과, 후속 Issue와 데이터 무노출을 검토한다.

## 의존 관계

- 기기, deployment, synthetic fixture, fault injection은 모든 실행보다 먼저 준비한다.
- 정상 reconcile 기준 결과가 retry/응답유실/재시작 run보다 먼저 완료되어야 한다.
- 각 failure mode는 동일 Session fixture로 변수를 하나씩 바꿔 독립 수행한다.
- API-009 gate는 정상/실패 run 각각 종료 시 확인한다.
- Android와 iOS 결과는 같은 fixture 정의와 API policy를 사용하되 OS별 실기기는 분리한다.

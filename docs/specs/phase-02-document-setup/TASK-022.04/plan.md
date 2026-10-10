# 🛠️ 문서 서비스 간 표준 자료 export와 import 구현 계획

> TASK-022.04 · PRD v1.9.0 · 선행: TASK-022.02
> GitHub Issue: [#99](https://github.com/donghyunlee-dev/meeting-automation/issues/99)

## 🧩 변경 범위와 책임

DocumentTransferPort, 양 Provider 표준 문서 codec/metadata mapper, MeetingDocument/StructuredMinutes/Transcript, 참가자 참조 재작성, mock 계약 fixture

Backend가 Port/유스케이스/Adapter와 unit·계약 테스트를 소유한다. 공개 API와 durable 상태를 먼저 완성해 후속 UI가 임의 상태를 만들지 않게 한다.

## 🔌 인터페이스

readManifest는 pagination을 완료하고 source revision/digest를 반환한다. exportMeeting은 schemaVersion 검증을 수행한다. importParticipant/importMeeting은 생성 payload에 transferKey·canonical digest를 기록한다. 기존 target가 같은 키·같은 digest인 경우만 재사용한다. participant ID map으로 mapping/owner 참조를 변경하고 externalSessionId를 보존한다.

## 🧭 구현 순서

- spec의 수용 기준별 실패 fixture와 테스트를 먼저 추가하고 실패 원인을 확인한다.
- DocumentTransferPort, 양 Provider 표준 문서 codec/metadata mapper, MeetingDocument/StructuredMinutes/Transcript, 참가자 참조 재작성, mock 계약 fixture에 필요한 최소 변경을 적용한다. 다른 진행자의 수정이나 기존 검증 기록을 되돌리지 않는다.
- 상태/멱등/오류/보안 회귀를 통과시키고 공통 명세와 실제 요청·응답을 대조한다.
- 선행/후속 계약을 연결하고 read-only Health, Secret 조회 미노출과 원본 보존을 확인한다.
- 현재 head의 테스트·리뷰·적용 가능한 QA를 마친 뒤 리더가 master 병합과 Evidence를 확정한다.

## 🔎 검증

[test.md](./test.md)의 각 사례를 수행한다. 구현·테스트 명령·정확한 SHA·수동 QA 결과는 docs/evidence/TASK-022.04/verification.md에 기록한다. 불가능한 실제 credential/배포 검증은 BLOCKED로 표시한다.

# iOS Safari 녹음 검증

## 목표

실제 iPhone의 Safari에서 장시간 녹음과 모바일 상태 변화가 Recorder, 로컬 Chunk 보관, 업로드 및 처리 시작에 미치는 영향을 재현 가능한 수동 QA로 확인한다. 결과는 실행 환경에 한정해 기록하고, 특정 iOS/Safari 조합의 동작을 보편적 보장으로 표현하지 않는다.

PRD v1.8.1 (2026-10-06), `TASK-018.02`, `NFR-001~003`, `NFR-007`, PRD Section 21을 구체화한다. Issue [#66](https://github.com/donghyunlee-dev/meeting-automation/issues/66). 선행 계약은 TASK-005.08 Issue #26 및 TASK-017.03 Issue #62이며, Android 비교 기준은 TASK-018.01 Issue #65다.

## 범위

- 물리 iPhone과 Safari에서 화면 켠 상태 30분·60분 녹음 baseline
- 분리 실행하는 화면 잠금/잠금 해제, 다른 앱 전환/복귀, 통제된 전화 수신, 네트워크 단절/복구
- 실행 당시 Safari의 `MediaRecorder`, MIME 지원, `MediaStreamTrack`, `visibilitychange`, IndexedDB Chunk 및 API-007/008 업로드 관찰
- 종료 시 final `dataavailable` → Chunk persist → 업로드 완료 → API-009 → Processing route 계약 확인
- 기기/OS/Safari 버전, 앱 commit, 환경, 재현 단계, 비민감 결과와 QA 정리를 `docs/evidence/TASK-018.02.md`에 기록
- 같은 실행 조건의 Android Chrome 결과와 차이 요약. 원인을 추정할 수 없으면 관찰 사실과 미확인 사항을 분리

## 비범위

- Android Chrome 재검증 (`TASK-018.01`)
- 네트워크 복구 뒤 sequence별 재전송·중복/누락 분석 (`TASK-018.03`)
- MIME/Chunk/안내 구현 수정 및 지원 브라우저 범위 결정 (`TASK-018.04`)
- 네이티브 앱, 백그라운드 녹음 보장, 권한 우회 또는 새 핵심 아키텍처 추가
- 실제 회의/통화 녹음, 오디오·Transcript·민감 로그를 Evidence나 외부 도구에 보관

## 시험 환경과 안전

- 최신 지원 물리 iPhone 1종 이상, 실행 당시 안정 버전 iOS와 내장 Safari, HTTPS 비운영 앱, 전용 QA Session/참석자, 폐기 가능한 storage prefix를 사용한다. 실행 때 모델명, iOS 버전, Safari 표시 버전 또는 User-Agent 식별값을 기록하며 브라우저 자동 업데이트 여부도 남긴다.
- PWA 홈 화면 실행, in-app browser, 다른 iOS 브라우저는 Safari 실행과 혼합하지 않는다. 별도 실행할 경우 별도 조합으로 기록한다.
- 실제 대화 대신 동의된 중립 문구 또는 합성 tone/voice fixture를 재생한다. 통화 시나리오는 별도 QA 회선으로 통제하며 통화 내용/전화번호를 수집하지 않는다.
- 회의 metadata와 테스트 참석자를 녹음 전에 정하고 실제 참석자 Email/Slack 전달을 비활성화한다. Provider는 비용 없는 비운영 mock processing을 사용한다.
- 허용 Evidence: 실행 식별자, 기기/OS/Safari/app 버전, MIME, Recorder/track 이벤트 종류와 시각, Chunk sequence/개수/총 byte, HTTP 상태, 사용자 화면 결과.
- 금지 Evidence: Audio/Blob/파형, Transcript/인식 문구, 통화 내용·전화번호, Secret, 개인 이메일, raw API/Provider 응답, credential, object URL.
- 종료 후 mock processing을 `REVIEW`까지 완료해 정상 정리를 확인한다. 실패한 run은 격리된 QA object를 제거하고 삭제 결과만 기록한다.

## 시나리오와 판정

각 방해 조건을 별도 Session에서 한 번에 하나만 적용한다. 30분 방해 시나리오에서는 시작 10분 뒤 조건을 5분 적용하되 전화와 네트워크는 각각 60초 적용한다. 화면 잠금 뒤 앱이 중단되거나 Safari가 foreground로 복귀하지 못하면 이를 실패로 기록하고, 별도의 복구 실행에서 결과를 덮어쓰지 않는다.

| ID | 조건 | 관찰할 결과 |
|---|---|---|
| IOS-BASE-30 / IOS-BASE-60 | 화면 켠 상태 30/60분 연속 녹음 | 의도치 않은 stop/error, 최종 duration, 저장/업로드 완료 |
| IOS-LOCK-30 | 30분 녹음 중 5분 화면 잠금 | 잠금/복귀 시각, recorder/track 변화, final output/Chunk 영향 |
| IOS-APP-30 | 30분 녹음 중 5분 다른 앱 전환 | Safari 복귀 가능 여부, visibility/track/recorder, 로컬 pending 변화 |
| IOS-CALL-30 | 30분 녹음 중 QA 수신 전화 응답 후 60초 유지 | 전화 전후 track mute/ended, Recorder 이벤트와 사용자 안내 |
| IOS-NET-30 | 30분 녹음 중 외부 연결 60초 차단 후 복구 | local pending 보존 및 복구 뒤 같은 Session 업로드 상태 |
| IOS-STOP-FINAL | 각 run 종료 | final Chunk persist, API-008 sequence, API-009와 route Session ID 순서/일치 |
| IOS-CLEANUP | mock processing 또는 실패 cleanup | 정상 완료 후 Audio 제거 또는 실패 object 삭제 확인 |

`dataavailable` 간격만으로 녹음 성공을 판정하지 않는다. 전체 duration, MediaRecorder 상태, track 상태, IndexedDB 순번/byte, 서버 API-008 수신, 종료 API-009 결과를 함께 비교한다. iOS 시스템이 페이지 실행을 중지해 Safari로 복귀하지 못하면 확인되지 않은 녹음 구간을 성공으로 간주하지 않는다.

`MediaRecorder.isTypeSupported()`는 해당 시점 User-Agent의 선언 결과로 기록하고, 실제 recorder 생성 성공, `recorder.mimeType`, 최종 Blob, 업로드 수락 결과도 별도로 기록한다. MIME 선언만으로 장시간/최종 파일 호환성을 보장하지 않는다. 시간 단위 `timeslice` 이벤트 주기는 정확한 주기 기준으로 사용하지 않는다.

## 수용 기준

- 각 실행에 기기 모델, iOS/Safari/app/backend 버전, commit, network, MIME, Session ID, 시작/종료 및 방해 시각이 기록된다.
- 화면 켠 baseline 30분·60분 run 결과와 종료 상태가 각각 기록되고, 오류/조기 종료는 성공으로 표시되지 않는다.
- 화면 잠금, 앱 전환, 전화, 네트워크 단절의 결과가 독립 재현 단계 및 Recorder/track/Chunk 관찰과 함께 기록된다.
- 각 종료 run에서 final Chunk persist 이후 업로드 결과와 API-008 expected/received sequence를 비교하고 API-009 및 route Session ID를 확인한다. 미전송이 남으면 처리 route 성공으로 판정하지 않는다.
- Evidence에 오디오, 음성 내용, 전화번호, Secret, 개인식별정보 또는 공개 저장 URL이 없다. QA object는 REVIEW cleanup 또는 격리 삭제로 정리된다.
- Android 비교표는 직접 관찰한 조건/차이/재현 결과만 담고 원인을 근거 없이 확정하지 않는다.
- 재현된 결함은 비민감 Evidence와 후속 Issue를 연결하고, 상세 chunk 복구는 TASK-018.03, 수정/지원 범위 결정은 TASK-018.04로 이관한다.

## 근거 자료

- [MediaRecorder — MDN](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder): 지원 여부 검사, MIME, 이벤트, stop 이후 마지막 Blob 계약 및 호환성 표를 실행 시 참고한다.
- [MediaRecorder `dataavailable` event — MDN](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder/dataavailable_event): timeslice가 정확한 interval을 보장하지 않는 점과 브라우저/기기별 지연 가능성을 시험 판정에 반영한다.
- [MediaRecorder `start()` — MDN](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder/start): 시작·timeslice 및 오류 종료 시 이벤트 계약을 확인한다.
- 이 자료는 Web API 공통 계약을 설명한다. 실제 iOS Safari 성능과 백그라운드 지속성은 이 Issue의 물리 기기 실행 결과만 근거로 한다.

## 관련 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)
- [Android Chrome 비교 설계](../TASK-018.01/spec.md)
- [녹음 종료 계약](../../phase-03-recording/TASK-005.08/spec.md)
- [실패 복구 UI 계약](../../phase-07-security/TASK-017.03/spec.md)

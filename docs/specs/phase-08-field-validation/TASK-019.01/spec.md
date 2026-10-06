# 3인 회의 fixture 및 품질 기준 검증

## 목표

정답 transcript와 speaker turn이 알려진 3인 한국어 테스트 음성을 고정 조건으로 처리해 STT 텍스트 오류, 화자 구분 오류 및 Speaker-to-Participant mapping 완료율의 재현 가능한 기준선을 측정한다. 실제 실명 음성 인식은 하지 않으며 DEC-009/010에 따라 사용자가 Speaker 단위로 참석자를 한 번 매핑하는 동작을 검증한다.

PRD v1.8.1 (2026-10-06), `TASK-019.01`, `DEC-007~010`, `FR-007`, `FR-010`, PRD Section 21을 구체화한다. Issue [#69](https://github.com/donghyunlee-dev/meeting-automation/issues/69). 선행 작업은 TASK-006.06 Issue #32와 TASK-018.04 Issue #68이다.

## 범위

- 정답 텍스트/발화 구간/익명 화자 ID가 있는 3인 한국어 fixture 및 버전 고정
- PRD DEC-007 조건을 반영해 비운영 실내 테스트 공간에서 스마트폰을 중앙에 둔 녹음 경로 검증
- 하나의 고정된 `TRANSCRIPTION_MODEL`/Provider 설정에서 STT, Speaker diarization 및 표준 Transcript 결과 관찰
- 기준 transcript 대비 CER/WER, diarization error 구성 요소, expected Speaker-to-Participant mapping 완료율과 segment 표시 전파 확인
- API-010 `REVIEW` response에서 Speaker/segment 참조 일관성 확인; 오류 원문/Transcript/Secret 로그 비노출 확인
- 세 번의 동일 fixture 반복 실행으로 run 간 결과 변동을 기록하고 `TASK-019.02`/`.03`에서 쓸 baseline Evidence 작성

## 비범위

- 5인 회의 조건 비교 (`TASK-019.02`)
- provider/model/prompt/threshold 튜닝 (`TASK-019.03`)
- 자동 실명 인식, voiceprint 또는 사용자 대신 자동 mapping
- Speaker마다 개별 segment 사람 지정 UI (DEC-010 위반)
- Minutes 생성 정확도, 문서/Email/Slack 게시
- 절대 정확도 합격 기준 확정. PRD는 오류와 mapping 완료율을 기록하도록 하지만 수치 임계값은 지정하지 않았으므로 이 작업에서 새 합격 숫자를 만들지 않는다.

## Fixture와 녹음 방식

- 비운영 QA 공간에서 쓸 6~10분 길이의 한국어 scripted dialogue를 사용한다. 세 화자의 문장/턴, 정답 시작·끝 시각, pause, 발화자 ID를 사람이 검수한 ground truth로 버전 관리한다.
- 합성 음성은 고유한 라이선스 허용 TTS voice 세 개를 사용하고 voice cloning을 하지 않는다. 세 음원을 별도 재생 위치에서 고정 재생하여 중앙 스마트폰 microphone으로 녹음한다. 재생 장치 위치/거리, room, phone 모델, OS/browser, 입력 level, ambient noise 조건을 run sheet에 기록한다.
- 기본 fixture는 3명 non-overlap 대화로 CER/WER와 화자 지표 기준선을 측정한다. 겹침 발화나 추가 소음 조건은 기본 점수와 섞지 않고 challenge run으로 별도 보고한다.
- Fixture 원본 음성/정답 transcript는 접근 제한된 QA 저장소만 사용하고 GitHub, Issue, `docs/evidence/`에 첨부하지 않는다. Evidence에는 fixture ID/version/SHA-256과 통계만 기록한다.
- Provider/model/API version, 언어 hint, prompt/config hash, 앱/backend commit, `acceptedMimeTypes`, 실제 MIME, sample rate/codec, processing result, 실행 날짜를 고정 기록한다. .019.01 반복 사이 설정을 변경하지 않는다.
- 실패 Audio는 이전 승인 계약을 따라 private object storage에 보존하고 사용자 재시도/다운로드/종료 경로를 확인한다. 최대 24시간 보존 후 정리하며 QA 참석자에게 실패 문서나 Email/Slack을 보내지 않는다. 정상 성공 Session은 Review 진입 뒤 Audio cleanup을 확인한다.

## 측정 정의

- **CER (주 지표)**: 기준 텍스트와 결과 텍스트를 Unicode NFC로 정규화하고 문장부호를 제거하며 연속 공백을 하나로 줄인 뒤 문자 단위 edit distance `(substitution + deletion + insertion) / 기준 문자 수`로 계산한다. 기준 문자열이 비어 있으면 비율 대신 건수를 보고한다.
- **WER (보조 지표)**: 동일 정규화 문자열을 whitespace-delimited token으로 분리하고 `(substitution + deletion + insertion) / 기준 token 수`로 계산한다. 한국어 띄어쓰기/tokenization 영향이 포함됨을 표시하고 CER와 단독 비교하지 않는다.
- **Diarization**: 각 reference/predicted 발화 interval과 anonymized speaker ID를 대조한다. predicted speaker label ↔ reference speaker ID 대응은 총 시간 overlap이 가장 커지는 일대일 label permutation으로 평가 전용 계산한다. Reference speech duration을 분모로 missed speech, false alarm, speaker confusion 시간을 각각 기록하고 합산 DER도 보고한다. 기본 fixture는 overlap speech가 없으며 overlap challenge는 별도 요약한다.
- **Mapping 완료율**: 사람 검토자가 API-010에 표시된 각 expected speaker label을 참가자 roster의 정답 participant ID와 대조해 직접 mapping한다. 완료율은 올바른 고유 매핑을 완료한 expected speakers 수 / 3 × 100으로 계산한다. 누락/추가 Speaker 수와 잘못된 Participant 배정은 완료율과 분리 보고한다.
- **Mapping 전파 정확성**: API-011 저장 뒤 API-010 재조회 및 Review UI에서 같은 `speakerId`를 참조하는 모든 transcript segment가 같은 Participant로 표시되는지 확인한다. Review 데이터가 완성되기 전 partial transcript는 노출하지 않는다.

## 수용 기준

- 3인 fixture는 고정 ID/version/hash, 정답 텍스트/turn annotation 및 실제 녹음 조건으로 반복 재현할 수 있다.
- 고정 provider 설정에서 세 번의 독립 처리 run 결과, CER/WER, missed/false alarm/speaker confusion/DER 및 run 간 변동이 계산/기록된다.
- 평가 전용 label matching은 participant 실명을 provider output에서 추론하지 않고 anonymized reference로만 수행된다.
- 각 run의 API-010 Review data에 segment speaker reference가 유효하며, 3명 expected mapping completion rate와 오매핑/미매핑이 기록된다.
- mapping 저장 후 같은 Speaker의 전체 segment에 단일 Participant mapping이 반영되고 다른 Speaker의 segment는 변경되지 않는다.
- 절대 pass threshold를 이 문서에서 만들지 않는다. 결과는 `.019.02`의 동일 기준 비교 및 `.019.03`의 조정 근거로 전달된다.
- Issue/Evidence에는 음성, transcript, 실제 이름, provider raw body/오류 원문, Secret이 없고 Audio retention/cleanup과 알림 미전송 조건이 확인된다.

## 관련 문서

- [구현 계획](./plan.md)
- [작업 목록](./tasks.md)
- [검증 계획](./test.md)
- [Processing/Review API-010](../../phase-04-processing/TASK-006.06/spec.md)
- [Provider transcription contract](../../phase-04-processing/TASK-006.02/spec.md)
- [Speaker 정규화 contract](../../phase-04-processing/TASK-006.03/spec.md)
- [Phase 8 현장 안정화](../TASK-018.04/spec.md)

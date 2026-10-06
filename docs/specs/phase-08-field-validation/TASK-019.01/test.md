# 검증 계획

## 자동화 테스트

이 task는 STT/diarization 현장 기준선 작성이며 애플리케이션 코드를 변경하지 않는다. 자동화된 제품 회귀를 추가하지 않고 테스트도 실행하지 않는다. Scorecard 수식은 작은 known-answer example로 두 명이 독립 계산해 같은 결과를 확인한 뒤 본 fixture에 적용한다. 별도 코드 변경 결함이 발견되면 `.019.03` 또는 bug Issue에 회귀 테스트를 설계한다.

## 품질 측정 시험

고정 조건: 세 명의 서로 다른 합성 한국어 voice, 정확한 scripted reference text, 6~10분 duration, 3개의 별도 speaker playback position, 중앙 smartphone capture, same room/noise profile, Android/iOS build 조합, actual MIME, Backend commit, `TRANSCRIPTION_MODEL`/Provider API/language hint/prompt config, three independent Session IDs.

| ID | 입력/절차 | 기대 결과 | Evidence |
|---|---|---|---|
| FIXTURE-3P | version/hash가 freeze된 script/voice/turn annotation으로 room capture | exactly 3 anonymous reference identities; 실제 참가자/회의 정보 없음 | fixture ID/version/hash, duration, device setup |
| SCORECARD-CHECK | 5 reference chars 대비 1 substitution + 1 deletion의 text vector, 10초 reference speech 중 1초 missed 및 1초 confusion, 3명 중 올바른 mapping 2명인 예시 계산 | CER=2/5, missed=1/10, confusion=1/10, mapping completion=2/3으로 독립 계산이 일치 | 수식 revision, 두 확인 결과 |
| PROCESS-3P-01..03 | 동일 fixture/config로 독립 Session 3회 API-009 처리 | 각 실행 결과/설정이 분리 추적되고 provider/model drift 없음 | Session ID, commit/config identity, stage outcome |
| STT-CER | 처리된 text를 NFC/문장부호 제거/공백 정규화 후 character edit distance 비교 | 세션별 `S+D+I / reference characters`와 denominator/정규화 규칙 보고 | CER 수치와 run ID |
| STT-WER | 동일 정규화 후 whitespace token 단위 비교 | `S+D+I / reference tokens`; 한국어 spacing 영향 표시 | WER 수치, token denominator |
| DIARIZATION-3P | Reference turn과 predicted normalized segments 비교 | max-overlap label permutation으로 missed speech, false alarm, confusion 시간 및 DER 보고; evaluation label은 익명 유지 | error duration/total reference speech time |
| REVIEW-MAP-3P | expected 3 speaker labels를 roster의 known synthetic identities에 맞춰 Reviewer가 API-011 저장 | correct unique maps / 3 completion %, duplicate/wrong/unmapped counts 별도 | completion fraction, API version |
| MAP-PROPAGATION | API-010 reload 후 같은 speakerId를 가리키는 전체 segments 확인 | 해당 speaker의 전체 segment에 같은 Participant 반영; 나머지 ID는 그대로 | segment/speaker count와 version |
| REVIEW-SNAPSHOT | 처리 완료 전/후 API-010 조회 비교 | REVIEW 이전 partial text/Minutes 숨김, 완료 후 valid speaker references 및 consistent snapshot | status/version/array count, no raw text |
| PRIVACY-3P | synthetic canary data를 처리하며 response/log/Evidence 경계 확인 | Transcript/raw provider error/Secret가 일반 log 또는 오류에 없음 | sanitized assertion 결과만 |
| AUDIO-RETENTION | 성공 Session REVIEW cleanup 및 실패 Session retry/download/discard QA 경로 | 성공 Audio 정리, 실패 Audio private/최대 24h, action 결과 후 삭제; attendee email/Slack 0건 | state/expiry/object count/message count |
| BASELINE-VARIANCE | 세 run별/평균/범위 계산 | quality variation이 보고되고 어떠한 PRD 임계값도 임의로 추가하지 않음 | aggregate CER/WER/DER/mapping completion |

## 정답 및 점수 처리

- Text normalization은 Unicode NFC → punctuation 제거 → 연속 whitespace 한 칸으로 변환 → trim 순서로 한다. 원본 음성은 평가 결과 작성 후에도 Issue/Evidence에 복사하지 않는다.
- CER와 WER는 reference denominator를 항상 함께 보고한다. Empty reference text가 있으면 0으로 나누지 않고 insertion/deletion 건수와 해당 run을 명시한다.
- Diarization은 reference speech interval 기준 max-overlap one-to-one label assignment로 익명 speaker permutation을 해결한다. DER denominator는 총 reference speech duration이며 missed/false-alarm/confusion 구성을 별도 공개한다. Primary fixture에는 겹침 구간이 없고 challenge overlap result를 기본 DER와 합치지 않는다.
- Mapping completion denominator는 세 expected identities다. duplicate mapping으로 두 labels가 같은 participant를 가리키면 한 명만 완료로 인정하고 잘못된 mapping은 별도 error count에 포함한다.
- 통계적 유의성, 인구 전체 정확도 또는 unsupported device 범위를 주장하지 않는다. 세 번 반복은 고정 fixture/config의 관찰값과 변동을 제공하는 작은 baseline이다.

## 성공/실패 판정

- 완료: 세 run fixture/config에 CER/WER/DER 구성요소 및 mapping 지표가 기록되고 API-010/API-011 계약과 전체 segment mapping 전파를 점검하며 제한과 variation을 후속 task가 재사용한다.
- 결함: 잘못된 speaker reference/time, API-010 partial 노출, mapping 저장 후 segment 전파 불일치, 기준 텍스트/화자 정답 파일 mismatch, raw content/Secret 로그 노출, 허가되지 않은 attendee delivery 또는 Audio retention/cleanup 위반.
- 품질 수치는 이번 PRD에 acceptance threshold가 없으므로 숫자만으로 PASS/FAIL을 만들지 않는다. 기준선은 TASK-019.02의 같은 metric 비교와 TASK-019.03의 고정 fixture 튜닝/회귀 판단에 사용한다.
- 재현 안 된 output difference는 별도 annotation review 후 미분류로 남기고 baseline에 임의 수정하지 않는다.

## Evidence 및 정리

- 위치: `docs/evidence/TASK-019.01.md`
- 허용: fixture ID/version/hash, Session ID, device/OS/browser/build, provider/model/API config identity, MIME, duration, CER/WER/DER aggregates, mapping completion, error category, object/message counts.
- 금지: audio file/waveform, ground-truth or predicted transcript text, participant real names, personal voice, raw provider response, Secret, credential, signed/public object URL.
- 합성 source/rendered recording은 access-controlled QA store에서 처리한다. success REVIEW 뒤 Audio cleanup; failure는 기존 private storage/retry/download/discard/24h cleanup을 사용한다. 실패 문서는 attendees에게 보내지 않는다.

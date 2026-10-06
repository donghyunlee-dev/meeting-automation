# 실행 작업

## 단계

- TASK-018.04 현장 Evidence 및 issue 완료 조건을 확인한다. 의존: 직접 선행 작업의 완료 결과. 완료 결과: 실제 검증된 device/browser/MIME 조합과 이슈 없는 기본 run 환경이 확보된다.
- 세 화자의 6~10분 한국어 dialogue fixture와 ground truth annotation을 준비/검수한다. 의존: 검증된 비운영 기기/환경 및 fixture 보안 경계. 완료 결과: fixture ID/version/hash, exact transcript, turn intervals, anonymized speaker set이 고정된다.
- 세 개 voice playback 위치, central smartphone, room/noise, capture settings 및 Session/provider configuration을 고정한다. 의존: fixture 검수. 완료 결과: 같은 환경을 재현할 run sheet가 작성된다.
- evaluation scorecard와 normalization 규칙을 deterministic 방식으로 확정한다. 의존: 정답 텍스트/interval annotation. 완료 결과: CER/WER 정규화 규칙, interval overlap 기반 DER components와 mapping 완료율 공식이 설명되고, 알려진 sample에서 손계산과 scorecard 결과가 일치한다.
- 같은 fixture/provider/config로 세 개 독립 Session run을 녹음/처리한다. 의존: run sheet와 고정 scorecard. 완료 결과: run ID와 처리 결과가 생기고 설정 drift가 없다.
- transcript 문자/단어 오류와 speaker interval errors를 계산한다. 의존: 세 run의 API-010 Review 결과와 freeze된 정답. 완료 결과: CER/WER, missed/false alarm/confusion/DER, run별 및 aggregate 결과가 확인된다.
- Reviewer가 각 Speaker를 roster의 정답 Participant에 단일 mapping한다. 의존: 각 run의 표준 speaker 목록과 익명 정답 대응표. 완료 결과: 예상 3명 mapping 완료율, 오배정, 미매핑이 기록된다.
- API-011 update 후 API-010 Review snapshot 및 같은 speaker 전체 segment propagation을 확인한다. 의존: Reviewer mapping 저장. 완료 결과: 버전/ID 참조와 mapping update 범위가 확인된다.
- private failure Audio action/24h cleanup과 성공 Audio 삭제/no attendee delivery를 확인한다. 의존: 각 run의 성공/실패 판정. 완료 결과: retention/outcome만 Evidence에 남는다.
- `.019.02`/`.019.03` 재사용용 기준선 및 한계 Evidence를 작성한다. 의존: 지표 계산, mapping 및 cleanup 확인. 완료 결과: audio/transcript 없이도 fixture manifest hash와 metric 결과, config를 추적할 수 있다.

## 의존 관계

- Phase 8 호환성 및 실제 device 조합 검증은 fixture 녹음/처리 전에 완료한다.
- fixture/ground truth freeze 후 세 run을 설정 변경 없이 순차 또는 독립 실행한다.
- 정답 화자 매핑 점수는 provider 결과/expected anonymized identity가 확정된 뒤 계산한다.
- API-011 후 API-010 snapshot propagation 확인을 mapping completion 계산 뒤 수행한다.
- Evidence 집계와 Audio cleanup은 모든 run 결과 확인 뒤 완료한다.

# 실행 작업

## 단계

- 3인/5인 원격 Evidence, config identity, fixture hash와 scorecard revision을 확인한다. 의존: TASK-019.01/#69 및 TASK-019.02/#71 완료. 완료 결과: 재현 가능한 baseline 두 개가 연결된다.
- 최대 세 개 candidate를 정하고 각 candidate에서 설정 한 dimension만 바꾼다. 의존: baseline 확인과 공식 provider 지원 상태. 완료 결과: candidate value/source, reason, rollback value 및 config diff 기록.
- 현재 provider/normalizer/EXT-002 mock contracts를 포함한 baseline regression suite를 고정 config에서 실행한다. 의존: candidate 명세. 완료 결과: 변경 전 통과하는 기준 test results가 commit에 연결된다.
- 코드 변경이 필요한 후보는 실패 regression을 먼저 추가하고 현 설정에서 red를 확인한다. 의존: 해당 결함의 재현 sample. 완료 결과: 실패 전/후 test와 invariant가 확인된다.
- 한 차원만 바꾸는 최소 config 또는 adapter change를 적용한다. 의존: red regression. 완료 결과: 후보 config가 기존 `TranscriptionProvider` port와 schema를 사용한다.
- provider parsing/normalization/API-010/EXT-002 downstream regression을 실행한다. 의존: code/config 수정. 완료 결과: Typed response/segment references/StructuredMinutes contract가 green이다.
- 한 candidate마다 3인 fixture와 5인 fixture를 각 세 번 실행한다. 의존: baseline green과 frozen input. 완료 결과: paired run IDs, config hash, model/API version, scorecard rows.
- CER/WER/DER 구성과 mapping completion을 baseline 대비 비교한다. 의존: 두 fixture의 전 run output. 완료 결과: per fixture median/range, Pareto decision 및 variance note.
- 채택, 거부, inconclusive 중 하나를 각 candidate에 부여한다. 의존: complete scorecard와 non-regression rule. 완료 결과: adopt only strict acceptable/non-regressive candidate; 아니면 baseline selected.
- 선택된 설정에서 FE/API consumer/Evidence logging 및 provider failure contract regression을 확인한다. 의존: candidate decision. 완료 결과: contract/privacy/non-regression results가 이어진다.
- 성공/실패 Audio cleanup, failure private storage max 24h/recovery action/no attendee delivery 및 synthetic fixture cleanup을 확인한다. 의존: 모든 Session outcome. 완료 결과: object/message counts only.
- `docs/evidence/TASK-019.03.md`에 config/version/commit, 변경 이유, 회귀, scorecard, rollback 및 한계를 작성한다. 의존: 결정 및 정리 완료. 완료 결과: Release acceptance가 같은 결과를 추적할 수 있다.

## 의존 관계

- Remote .019.01/.02 baseline과 동일 frozen fixture/settings prerequisites 없이는 tuning 불가.
- Baseline tests green before candidate red/implementation.
- Candidate별 결과 수집 전에 해당 candidate config/code regression green.
- 두 fixture의 반복값과 Pareto 비교 후에만 설정을 채택.
- Evidence 작성 후에만 task 완료로 판정; Audio cleanup/privacy evidence 필수.

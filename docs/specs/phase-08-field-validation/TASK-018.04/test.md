# 검증 계획

## 자동화 테스트

- 재현 결함마다 수정 전에 실패하는 최소 회귀 테스트를 추가한다. 구현 변경 후 그 테스트와 직접 영향받는 모듈의 기존 회귀를 실행한다.
- FE가 변경되면 기존 recorder/MIME, IndexedDB, uploader, API client 및 UI 단위/통합 테스트 중 결함에 연결되는 명령을 실행한다.
- BE가 변경되면 API-007/008 validation, hash/byte/idempotency, storage error 관련 기존 controller/service/repository 테스트를 실행한다.
- FE/BE 결합 변경은 API-006~009 contract mock 및 저장소에서 정의한 통합 test를 실행한다.
- 정확한 명령은 저장소 `package.json`, Gradle task, CI workflow에 실제로 정의된 명령으로 확인한다. 새 명령이 필요하면 task deliverable로 추가하고 임의 명령을 성공한 것처럼 보고하지 않는다.
- Typecheck/lint/build는 수정 소유 모듈의 저장소 표준 명령을 사용한다. 미실행 단계는 Evidence에 원인과 대체 확인 범위를 기록한다.

## 수동 현장 재검증 행렬

선행 `.01~.03` Evidence의 기기·build·브라우저 실행 모드·MIME·network·fixture·장애 조건을 고정한다. 결함과 관계없는 조건은 바꾸지 않는다.

| ID | 입력/절차 | 기대 결과 | 증거 |
|---|---|---|---|
| FIX-REPRO | 수정 전 선행 run의 동일 fixture/device/build/조건 실행 | 기존 결함과 같은 관찰 결과 재현 또는 미재현 사유 기록 | 원본 run ID, actual/expected |
| FIX-REGRESSION | 변경 전 작성한 automated regression을 수정 전/후 실행 | 수정 전 red, 최소 수정 뒤 green, 테스트 기준이 회귀로 유지 | test name, commit, 결과 |
| FIX-MIME | affected MIME/device에서 API-006 policy→`isTypeSupported`→recorder creation→actual MIME→API-007 upload 확인 | 정책 내 MIME만 선택, 실제 업로드 일치; 실패면 안전 오류와 녹음 전 권한 순서 유지 | 허용/actual MIME, upload result |
| FIX-CHUNK | 영향 조건에서 Recorder final Blob/IndexedDB 저장/sequence/hash/byte/API-008 대조 | 누락·중복 없이 조정, size/error 조건 올바르게 표시 | sequence/hash/length 집계만 |
| FIX-RETRY | 영향 조건에서 outage/응답유실 재생 및 retry | ACK 전 로컬 보존, missing-only/동일 ID 재시도 및 사용자 복구 경로 | attempt/status/receipt count |
| FIX-HANDOFF | incomplete upload와 completed upload 각각 종료 | incomplete 때 API-009 0회; 완료 때 같은 Session route | API 호출 횟수/Session ID |
| FIX-DEVICE-ANDROID | Android Chrome에서 affected run을 최초 조건으로 재실행 | 수정 후 결과와 limitation이 기록되고 미해결은 실패로 유지 | 모델/OS/Chrome/MIME/run 결과 |
| FIX-DEVICE-IOS | iOS Safari에서 affected run을 최초 조건으로 재실행 | 수정 후 결과와 limitation이 기록되고 미해결은 실패로 유지 | 모델/iOS/Safari/MIME/run 결과 |
| FIX-SUPPORT-MATRIX | 전 현장 Evidence를 version/mode/MIME로 정리 | 실제 pass 조합만 검증됨, fail/미실행 조합 분리 | source run ID와 지원표 |
| FIX-CLEANUP | mock processing/retry 종료 뒤 QA object 및 fixture 제거 확인 | 테스트 Audio/object 0 또는 격리 cleanup failure 기록 | object count와 삭제 결과 |

해당 브라우저/OS에서 재현되지 않은 수정 영향 행은 무조건 pass로 바꾸지 않는다. 실제 재현 run이 가능할 때까지 미검증으로 표시하거나 별도 환경 blocker를 연결한다.

## 성공/실패 판정

- 성공: 재현된 범위가 자동화 회귀 green이고, 최초 동일 조건의 기기 재검증이 pass하며 API/MIME/sequence/hash/handoff 계약이 유지된다.
- 실패: automated regression red, API contract 변화, 예상 밖 MIME/upload acceptance, Chunk 유실/중복/손상, pending handoff, 잘못된 browser 안내, cleanup 누락.
- 조건부: OS/브라우저/기기 차이로 original run을 반복할 수 없으면 수정 성공을 선언하지 않고 대체 기기 조건과 limitation을 구별한다.
- Browser `검증됨` 표시는 필요한 baseline/record-stop-upload 및 관련 recovery scenario를 직접 완료한 조합에만 쓴다. 다른 버전은 미검증이다.
- 구조 변경/새 API/Native app/PRD 변경이 필요한 항목은 별도 결정 Issue의 의사결정 완료 전 해결된 것으로 기록하지 않는다.

## Evidence 및 안전

- 위치: `docs/evidence/TASK-018.04.md`
- 허용: defect/test ID, commit, build, 기기/OS/browser, MIME name, sequence/hash/byte count, HTTP category/status, 시도 수, pass/fail, issue ID.
- 금지: audio/Blob/파형, Transcript/음성문구, 통화 내용/번호, 개인 이메일, Secret/auth header/raw API response, storage credential/public URL.
- 비운영 synthetic fixture와 QA object를 cleanup한다. cleanup 실패는 production data에 접근하지 않고 전용 scope에서 후속 Issue로 추적한다.

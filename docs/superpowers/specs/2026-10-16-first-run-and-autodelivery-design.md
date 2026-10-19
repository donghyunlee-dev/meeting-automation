# 최초 연결부터 자동 이메일 발송까지의 기준 흐름

> **상태:** 사용자 승인 요구사항을 반영한 제품 설계 초안
> **기준일:** 2026-10-16
> **목적:** 최초 설정과 회의 처리 순서를 하나로 고정해 PRD·API·화면·구현 task 사이의 상충을 막는다.
> **관련 설계:** [Backend 소유 Audio 설계](./2026-10-16-backend-owned-audio-design.md)

## 제품 목표

운영자는 OpenAI와 Gmail 자격 증명을 Backend Secret으로 제공하고, 사용자는 앱에서 문서 서비스(Notion 또는 Confluence)를 연결한다. OpenAI/Gmail 키 입력 화면이나 사용자별 연결 절차는 V1에 없다. 서비스는 참가자 연락처와 회의 이력을 문서 서비스에 보관하고, 회의 녹음에서 화자를 분리한다. 사용자가 화자를 참가자와 확인해 연결하면 그 연결과 기본 회의록 Template으로 회의록을 생성하고, 저장에 성공한 회의록을 매칭된 참가자의 이메일로 자동 발송한다.

문서 서비스가 연결되지 않은 상태에서도 웹 앱과 설정 API는 정상적으로 열려야 한다. 다만 회의 생성은 문서 연결과 기본 구조 준비가 완료될 때까지 막는다. 즉 **앱의 설정 가능 상태와 회의 업무 가능 상태는 다르다.**

## 최초 진입 상태와 화면 전환

앱 진입 시 Backend App Config와 문서 연결/초기화 상태를 확인한다.

| 상태 | 사용자에게 표시 | 허용 동작 |
|---|---|---|
| 연결 정보 없음 또는 초기 구조 미완료 | 문서 서비스 설정 화면 | Provider 선택, 연결값 입력, 읽기 전용 연결 테스트, 기본 구조 생성 및 활성화 |
| 설정 저장소 사용 불가 | 저장소 복구 안내 | 연결 설정을 새로 만들거나 기존 정보를 덮어쓰지 않는다. |
| 설정은 저장됐지만 Provider가 일시적으로 응답하지 않음 | 연결 문제와 재시도 안내 | 설정·문서를 보존하고 새 미팅 진입을 막는다. 최초 연결 화면으로 취급하지 않는다. |
| 연결 및 기본 구조 준비 완료 | Home | 새 미팅 시작, 이전 회의록 조회, 참가자 설정 |

최초 초기화 완료 응답을 받은 첫 브라우저는 곧바로 New Meeting으로 이동한다. 이후 앱을 다시 열면 Home을 표시하며, Home에는 `새 미팅`과 이전 회의록 목록을 함께 둔다. 초기화 완료 여부는 Backend의 활성 연결/구조 상태가 기준이며 브라우저 local storage 플래그를 기준으로 삼지 않는다.

## 연결 설정과 기본 구조 생성

최초 위저드 입력은 선택 Provider의 자격 증명과 부모 위치뿐이다. Notion은 내부 Integration token과 공유된 부모 Page, Confluence Cloud는 사이트·계정 이메일·API token·space 및 선택 부모 Page를 받는다. credential은 Backend에만 전송하고 암호화된 전역 설정으로 저장한다.

순서는 다음과 같다.

1. 사용자가 Notion 또는 Confluence를 선택하고 연결값을 입력한다.
2. Backend가 인증과 지정 부모 위치 접근을 읽기 전용으로 테스트한다. 이 요청은 페이지를 만들지 않는다.
3. 테스트 성공 후 화면은 만들 기본 구조를 간단히 보여주고 `연결 및 기본 페이지 만들기`를 제공한다.
4. 사용자가 실행하면 Backend가 누락된 기본 구조를 멱등하게 생성하거나 이미 일치하는 구조를 재사용한다.
5. 전체 생성과 read-back 확인이 성공한 뒤에만 연결을 활성화한다.
6. 첫 설정은 New Meeting으로 이동한다.

페이지 생성 실패·응답 유실 때 부분 구조를 지우지 않는다. 다음 시도는 생성 marker와 부모·제목을 다시 확인해 중복 생성하지 않는다. 연결 실패는 오류를 표시하고 입력을 유지하되 secret은 메모리 밖에 보존하지 않는다.

## 기본 문서 구조

루트 제목은 `Meeting Automation`이다. Provider의 단순 Page hierarchy를 사용한다.

```text
Meeting Automation
├─ Home                 # 서비스 설명과 시작 안내
├─ Meetings             # 저장된 회의록, 앱의 이전 회의록 목록 원본
├─ Participants         # 참가자 이름과 이메일 연락처
├─ Templates            # 기본 회의록 Template
└─ Speaker Memory       # 사용자가 확인한 화자·참가자 연결 기록
```

초기화는 이 다섯 child page를 준비한다. `Templates`에는 Backend에 번들된 기본 Template을 seed로 한 기본 회의록 Template을 작성한다. 초기 생성 뒤 Template 본문을 읽고 버전 정보를 보관하는 주체는 활성 문서 Provider다. 회의 생성 API는 활성 Template을 반환하며, Backend static resource는 최초 seed 및 복구 기준이지 매번 조용히 Provider 내용을 덮어쓰는 source가 아니다.

`Participants`는 `{participantId,name,email}` 연락처를 보관한다. `Meetings`는 성공한 회의록과 최소한의 검색 metadata를 보관한다. Home 최근 목록과 전체 목록은 `Meetings`를 조회한다. 미참가자 연락처를 문서 서비스의 `Participants`에서 추가·수정할 수 있다.

## 화자 매칭과 기억

전사/화자 분리 결과의 `speakerId`는 해당 회의 안에서만 유효하다. 사용자는 모든 감지 화자를 회의 참가자와 연결하고, 미확인 화자는 회의록 자동 생성/이메일 전송 전에 해결한다. 이 연결은 Action Item 담당자와 이메일 수신 대상을 일관되게 해석하는 데 사용한다.

`Speaker Memory`는 사용자가 확인한 기존 화자·참가자 매칭을 기록해 이후 매칭 UI에서 후보/힌트를 제공한다. V1은 음성 지문이나 목소리 임베딩으로 새 녹음의 실제 인물을 자동 식별하지 않는다. 서로 다른 회의의 `speakerId`만 같다는 이유로 자동 연결하지 않으며, 기억에서 제안된 연결은 사용자가 해당 회의에서 확인한다.

## 회의와 자동 발송 순서

```text
Home → New Meeting → Recording / Backend chunk upload
  → Transcription + diarization
  → 사용자가 모든 Speaker를 Participant에 매칭
  → 활성 Template으로 Minutes 생성
  → Meeting 문서 저장
  → 매칭된 Participant 이메일로 자동 발송
  → 완료 및 수신자별 결과 표시
```

- 회의 시작 전에 참가자 목록에서 이름과 이메일 주소가 있는 Participant를 선택한다.
- 전사/화자 분리 후 사용자에게 speaker-to-participant 매핑 화면을 제공한다. 매칭 UI가 완료되기 전에는 Template 기반 Minutes 최종 생성과 자동 발송을 시작하지 않는다.
- Minutes 생성에는 Transcript, 확인된 speaker mapping, 활성 Template을 사용한다. 근거가 없는 문장/담당자/날짜는 만들어내지 않는다.
- 정상 Minutes 생성 뒤 Backend는 `Meetings`에 멱등하게 저장한다. 저장 성공 후에만 각 매칭 Participant에게 Gmail API로 자동 발송한다. 동일 이메일 주소는 한 번만 발송한다.
- 사용자가 별도의 Publish/Send 버튼을 누르지 않아도 정상 흐름에서 발송한다. 화면은 처리 단계와 수신자별 전송 결과를 보여준다.
- 문서 저장 실패 시 이메일을 보내지 않는다. 일부 이메일 실패는 성공한 수신자에게 재발송하지 않고 실패 delivery만 재시도할 수 있게 기록한다. 불명확한 timeout은 중복 발송 방지를 위해 자동 반복하지 않는다.
- STT, Speaker diarization, Minutes 생성 실패에서는 일반 회의록 이메일을 보내지 않는다. 실패 정리/보존 세부 정책은 기존 관련 요구를 별도 검토한다.

## 연동 분류

| 연결 | V1의 역할 | 필수 여부 |
|---|---|---|
| Notion 또는 Confluence | 참가자·Template·화자 기억·회의록·이력 저장 | 하나는 필수. 설정이 완료되기 전에는 회의 생성 불가 |
| OpenAI | 전사/화자 분리와 Minutes 생성 | 필수 |
| Gmail API | 매칭 참가자에게 회의록 발송 | 필수 |
| Slack | 부가 운영/회의 알림 | 기본 회의 흐름에 불필요. 후속 선택 기능 |
| Vercel | React Frontend 배포 | 현재 배포 선택 |
| Render 또는 대체 Backend 호스트 | Backend API와 처리 | Backend 호스팅 선택. 무료 플랜 예외는 별도 운영 검토 |

## 기존 설계에서 폐기·수정할 기준

- 초기 Root가 `Meetings`/`Participants` 두 페이지만 만든다는 규칙을 위 다섯 페이지 구조로 바꾼다.
- Backend static templates만을 매 회의 Template source로 쓰는 규칙을 Provider `Templates` page 기반으로 바꾼다.
- 문서 서비스 연결 실패를 모든 경우 최초 설정으로 보내는 동작을 금지한다. 미설정/미초기화와 기존 연결의 일시 장애를 구별한다.
- Minutes를 먼저 만들고 나중에 화자 mapping을 적용하는 순서를 바꾼다. 화자 매핑을 완료한 다음 Minutes를 생성한다.
- 사용자 Confirm/Publish/Send 버튼을 필수로 요구하는 순서를 자동 발송 요구로 바꾼다. 정상 생성 → 문서 저장 → 자동 이메일이다.
- 기존 Task 문서의 테스트/완료 evidence는 역사 기록으로 보존한다. 구현 변경은 승인된 PRD/이 설계에 맞는 새 Task로 추적한다.

## 구현 전 계약 점검 항목

1. App Config가 `UNCONFIGURED`, `CONFIGURING`, `READY`, `ATTENTION_REQUIRED`, `STORAGE_UNAVAILABLE`을 UI 전환과 일관되게 제공하는지 확인한다.
2. 초기화 operation이 다섯 기본 페이지를 생성·재사용·검증하고 첫 성공 브라우저를 New Meeting으로 보낼 수 있는지 확인한다.
3. Template list/content/version, Participants CRUD, Speaker Memory 읽기/쓰기, Meeting list/detail, auto delivery 계약을 API 명세에 반영한다.
4. 화자 mapping 완료가 Minutes 생성의 선행 조건이고, 이메일은 문서 저장 성공 뒤 정확히 한 번씩만 시도되는지 계약을 정한다.
5. Render Free 사용 가능성과 설정/Session/Audio 복구는 이 제품 흐름의 요구사항과 섞지 않고 배포 선택에서 별도 결정한다.

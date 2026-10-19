# ⚙️ 문서 서비스 연결과 자료 이전 설계

> 기준: PRD-MA-001 v2.1.0 draft · 2026-10-16
> 사용자 결정: 제품 계정·로그인·사용자별 설정 없이, 서비스 전체가 하나의 문서 연결 설정을 공유한다.
> 구현 완료 기록이 아니다. 최초 진입과 기본 Page 구조는 [최초 연결·자동 발송 기준 흐름](../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)을 따른다.

## 🎯 서비스 동작

- 앱 진입 시 공개 설정과 Bootstrap 완료 상태를 조회한다. 연결이 없거나 필수 Page가 준비되지 않았으면 `/setup/document`를 표시한다. 웹과 설정 API는 열려 있어야 하지만 회의 생성은 차단한다. 앱에 로그인이나 관리자 계정을 추가하지 않는다.
- 초기 위저드는 Notion 또는 Confluence Cloud 한 곳을 연결한다. Provider 선택 → 자격 증명·부모 위치 입력 → 읽기 전용 연결 테스트 → 생성될 기본 구조 확인 → `연결 및 기본 페이지 만들기` → 구조 검증 및 활성화 순서다.
- 연결 테스트 성공만으로 Provider를 active로 바꾸지 않는다. Bootstrap이 모든 필수 Page를 준비하고 read-back 확인을 완료한 뒤에만 READY로 전환한다.
- 첫 Bootstrap 완료 직후에는 New Meeting으로 이동한다. 이후 READY 상태로 앱을 열면 Home에 새 미팅과 이전 회의록 목록을 표시한다.
- 운영 중 `/settings`의 `문서 서비스 변경`에서 같은 위저드를 다시 사용한다. 새 연결의 테스트나 구조 생성이 실패해도 기존 연결은 활성 상태를 유지한다.
- 변경 완료 전 `전체 자료 복사`와 `새 서비스에서 시작`을 명시적으로 선택한다. 기본 선택은 회의록·Transcript·참석자 전체 복사다. 원본 페이지는 두 선택 모두 보존한다. 제품은 원본 삭제 기능을 제공하지 않는다.
- 이전 범위는 2026-10-10 사용자 답변으로 회의록·원문 Transcript·참석자 전체 복사와 원본 보존을 확정했다. 외부 첨부파일, 댓글, 수정 이력, 권한, 임의의 사용자 페이지 전체 복제는 범위 밖이다.
- AI·Email·Slack 연결 및 회사 설정의 기존 배포 설정 방식은 이번 변경 범위에 포함하지 않는다.

## 🧭 Provider별 가이드와 입력

| Provider | 필수 입력 | 화면 가이드 | 기본 구조 위치 |
|---|---|---|---|
| Notion | 내부 연결 API token, 부모 페이지 URL 또는 ID | Notion에서 내부 연결을 만들고 부모 페이지에 접근 권한을 부여하는 절차와 필요한 읽기·생성·갱신 권한 안내 | 지정 부모 아래 `Meeting Automation` 루트와 필수 child pages |
| Confluence Cloud | `https://<site>.atlassian.net` 사이트, Atlassian 계정 이메일, API token, space ID, 선택 부모 페이지 ID | 회사 내부 운영용 연동 계정과 API token 준비, 공간 읽기·페이지 생성·갱신 권한 확인 | 부모 지정 시 그 아래 루트; 부모가 없으면 지정 space 최상위에 루트와 필수 child pages 생성 |

Confluence 계정 이메일은 외부 서비스 인증 정보이며 제품 사용자 계정이 아니다. V1은 회사 내부 배포를 전제로 기존 Basic 인증 Adapter를 사용한다. 외부 고객에게 배포하는 제품으로 범위를 확장할 때는 Atlassian 공식 정책을 확인하고 배포용 OAuth 연동을 별도로 설계한다.

Notion 키만으로 부모 페이지 접근 권한이 생기지는 않는다. 연결 테스트는 인증·부모 위치 접근을 확인하며 쓰기 가능 여부를 확인할 수 없는 Provider에서는 `writeCapability=UNVERIFIED`를 반환한다. 화면은 최종 페이지 생성 때 쓰기 권한이 확인된다고 안내한다. 테스트 요청에서 검사용 페이지를 만들지 않는다.

## 🗂️ 전역 설정 저장

- 업무 문서 정본은 계속 Document Provider다. 제품 계정 DB·사용자별 설정·JPA·Redis·Queue는 도입하지 않는다.
- 로컬 Backend는 `DOCUMENT_SETTINGS_DIR`와 암호화 키가 설정된 경우 Git 밖의 암호화 파일을 사용하고, 값이 없으면 메모리 설정 저장소로 시작한다. Render Backend도 실행 중 메모리에 설정을 둔다. 브라우저는 연결 입력과 생성된 루트 ID를 origin별 localStorage에 보관해 같은 브라우저에서 Backend 재시작 후 설정을 다시 구성한다. 다른 브라우저·기기는 별도로 연결해야 한다.
- 로컬 파일 설정은 `DOCUMENT_SETTINGS_ENCRYPTION_KEY`로 AES-256-GCM 암호화한다. 브라우저 복구 정보는 localStorage에 저장되며 문서 서비스 자격 증명을 포함한다. 브라우저 프로필과 기기에 종속되며 해당 브라우저 저장소를 지우면 다시 연결해야 한다.
- 설정 갱신은 프로세스 전역 잠금, 임시 파일 쓰기·동기화·동일 파일시스템 원자 교체로 처리한다. 하나의 암호화된 상태 snapshot에 active, draft, operation, revision을 함께 기록한다. crash 뒤에는 이전 또는 새 snapshot 한 개만 유효해야 한다. 복호화·저장 오류를 미설정으로 간주하거나 이전 파일을 덮어쓰지 않는다.
- `DOCUMENT_PROVIDER`, `DOCUMENT_ROOT_ID`, `NOTION_TOKEN`, `CONFLUENCE_*`는 새 런타임의 활성 연결 원본으로 사용하지 않는다. 기존 배포 운영자는 최초 위저드에서 기존 연결 정보를 다시 입력하고, 기존 루트 재사용을 명시 선택할 수 있다. env 자동 import·fallback은 없다.
- 키가 누락되거나 디렉터리가 비영속·쓰기 불가능하면 `STORAGE_UNAVAILABLE` 상태로 안전하게 안내하고 설정 저장·문서 작업을 막는다. Render 기본 임시 파일시스템으로 성공 처리하지 않는다.
- Render Persistent Disk는 유료 서비스에 필요하고 단일 인스턴스·배포 시 중단 제약이 있다. 이는 배포 설계 제약이며 이번 문서 변경에서 유료 자원 생성·요금 지출은 수행하지 않는다.

## 🔄 상태와 동시성

공개 setup 상태는 `UNCONFIGURED`, `CONFIGURING`, `READY`, `SWITCHING`, `ATTENTION_REQUIRED`, `STORAGE_UNAVAILABLE`다. `configured`는 유효한 활성 설정이 영속 저장됐는지, `canCreateMeeting`은 활성 설정·스토리지·전환 잠금 조건을 모두 만족하는지다. 일시적인 Provider 네트워크 실패는 설정 삭제나 최초 위저드 강제 이동으로 처리하지 않는다.

Draft는 `DRAFT → TESTED → INITIALIZING → PREPARED`로 진행한다. draft 내용 변경은 revision을 올리고 테스트 결과를 무효화한다. TESTED 결과는 10분 유효하며 초기화 직전에 접근을 다시 확인한다. 초기 설정은 PREPARED 후 활성 연결이 없는 경우 원자적으로 READY로 전환한다. 운영 중 새 draft는 PREPARED까지 준비해도 기존 active를 바꾸지 않는다.

Operation은 `PENDING → RUNNING → SUCCEEDED` 또는 `FAILED`, `INTERRUPTED`, `CANCELLED`, `RECONCILIATION_REQUIRED`로 끝난다. 최초 초기화와 서비스 변경 작업은 한 번에 하나만 수행한다. 취소 요청은 cancelRequested=true로 표시하고 진행 중 외부 요청의 결과를 먼저 조정한다. 재시작 때 RUNNING은 INTERRUPTED로 표시하고 사용자가 재개하도록 한다. 서버가 외부 쓰기를 자동 재시도하지 않는다. 완료 전 브라우저 종료는 작업 취소가 아니며 재접속 시 동일 operation 상태를 조회한다.

- 전환 시작은 전역 lock 아래 활성 Session이 없는지 검사한다. `CREATED`를 포함한 모든 비종료 Session과 수행 중 문서/참석자 쓰기·Delivery 재시도가 있으면 `DOCUMENT_CHANGE_BUSY`로 거절한다. 종료 상태는 `COMPLETED`, `COMPLETED_WITH_WARNINGS`다.
- 전환 lock과 Session 생성 검사는 같은 경계에서 원자적으로 수행한다. 전환 도중 신규 Session·참석자 쓰기·Publish·Delivery 재시도는 차단한다. 기존 연결의 회의록/참석자 읽기는 허용한다. 실패/중단 작업도 재개 또는 취소까지 전환 lock을 유지한다.
- 모든 Session은 생성 때 활성 연결 version을 보관한다. 오래된 New Meeting 입력은 `DOCUMENT_CONNECTION_CHANGED`로 거절하고 목록을 다시 읽도록 안내한다.
- 수정 API는 `If-Match` 전역 revision과 `Idempotency-Key`를 요구한다. revision 충돌은 412이고 동일 key·다른 정규화 입력은 409다. 멱등 기록도 암호화 snapshot에 저장한다. 요청의 credential 비교에는 키 기반 HMAC을 사용하고 원문 hash를 공개하지 않는다.
- 앱 계정 없이 배포 접근 정책으로 신뢰된 사내 이용자에게만 화면/API를 제공한다. 설정 쓰기 API는 JSON, 허용 Origin과 전용 요청 header를 검증한다. 임의 cross-origin 요청·URL credential·인증 redirect·사설 IP로의 Provider 요청은 허용하지 않는다. 브라우저 localStorage에 저장되는 연결 정보는 해당 기기에서의 재연결 용도다.

## 🏗️ 명시적 초기 구조 생성

초기화 유스케이스만 페이지를 생성한다. 일반 Health, 목록 조회, Session 생성 요청은 누락 구조를 묵시적으로 생성하지 않는다.

신규 루트는 고정 제목 `Meeting Automation`이다. 루트 바로 아래 필수 child page는 `Home`, `Meetings`, `Participants`, `Templates`, `Speaker Memory`다. `Home`은 기본 안내, `Meetings`는 회의록/이력, `Participants`는 이름과 이메일 주소, `Templates`는 회의록 생성 Template, `Speaker Memory`는 사용자가 확인한 화자·참가자 매칭 기록을 저장한다. Notion Database/Data Source나 Confluence Database/Folder는 만들지 않는다.

초기화는 각 생성 결과와 journal을 기록하고 재시도에서 중복 page를 만들지 않는다. 정확한 대소문자 제목과 지정 부모를 검증한다. 기존 루트를 재사용할 때 이미 있는 필수 page를 검증하고 누락된 page는 생성한다. 같은 이름의 후보가 둘 이상이면 선택·정리 안내와 구조 충돌 오류를 반환하고 임의 선택하지 않는다.

`Templates`에 Backend bundled default template을 초기 seed로 기록한다. 활성화 후 Provider의 Template page 본문과 version이 회의 처리의 source of truth다. Backend static seed를 매 요청에서 Provider 내용에 덮어쓰거나 Provider 오류를 조용한 fallback으로 숨기지 않는다.

`Speaker Memory`는 사용자가 확인한 mapping을 보관하고 다음 회의의 후보로 보여준다. `speakerId`는 회의별 식별자이므로 회의가 달라지면 동일인으로 자동 간주하지 않는다. V1은 voiceprint나 자동 실명 화자 인식을 하지 않는다. 이전 mapping 후보도 회의별로 사용자가 확인한다.

외부 생성 응답을 받지 못했다면 이미 만들어졌을 수 있다. 재개는 journal과 정확한 부모·제목·생성 식별자 조회로 재조정한다. 결과가 불명확하면 RECONCILIATION_REQUIRED로 두며 새 페이지를 무조건 만들지 않는다. 생성 중 취소/실패로 남은 페이지는 보존하고 다시 연결할 때 명시적으로 재사용한다.

## 📦 자료 이전과 활성 연결 전환

- 원본 연결의 Participants와 Meetings 페이지 목록을 pagination 끝까지 읽는다. 앱이 관리하는 schemaVersion 문서만 지원한다. 알 수 없는 문서 형식·깨진 참조·중복 externalSessionId는 preflight 실패로 보고하고 활성 연결을 바꾸지 않는다. 목록 UI의 100개 제한을 이전 완료 판정으로 사용하지 않는다.
- 원본을 먼저 검사하고 document ID·원본 revision(Notion last_edited_time, Confluence version.number)/content digest·표준 데이터 digest를 journal에 기록한다. 본문은 처리 중 메모리만 사용하고 journal에 Transcript/Minutes를 장기 복제하지 않는다.
- 참석자를 먼저 복사해 source participant ID → target participant ID map을 만든다. 이름/이메일만으로 기존 target 참석자와 자동 병합하지 않는다. 새 provider ID에 맞춰 Meeting participantIds, Speaker mapping, Action Item owner 참조를 재작성한다. externalSessionId, meetingAt, title, templateId, Minutes, Transcript, Speaker IDs와 실패 문서의 stage/errorCode/audioDisposition은 보존한다.
- 대상은 앱 표준 데이터와 현재 내용만 이관한다. 원본 provider의 시각 레이아웃 동일성, 첨부·댓글·버전 이력·공유 권한 복제는 보장하지 않는다. 해석 불가능한 본문을 조용히 누락하지 않고 preflight 실패시킨다.
- import는 source connection fingerprint와 source document ID 기반 transferKey를 생성 payload에 함께 기록한다. 각 create 전 transferKey/externalSessionId를 조회하고, 동일 키·동일 표준 digest만 재사용한다. 이미 존재하는 다른 내용은 `DOCUMENT_MIGRATION_CONFLICT`다. 대상에서 기존 페이지를 덮어쓰지 않는다.
- 응답 유실은 대상 marker 재조회로 판별한다. 생성 여부를 확인할 수 없으면 RECONCILIATION_REQUIRED다. 사용자 재개도 불명확한 쓰기를 자동 반복하지 않는다.
- 표준 digest는 안정적으로 정렬한 객체 key와 원본 배열 순서를 사용한다. 대상 Provider ID 참조는 ID map으로 원본 ID에 역변환한 뒤 비교한다. 전 건 복사 후 대상의 표준 데이터 digest와 참조 무결성을 read-back 검증하고 원본 목록/revision이 preflight와 동일한지 재확인한다. 원본 편집·추가·삭제를 발견하면 완료로 처리하지 않는다. 전환 바로 직전 검증 이후 외부에서 직접 변경된 원본을 실시간 동기화하는 기능은 없다.
- 모든 검증이 끝난 뒤 active 설정과 operation 완료를 하나의 snapshot으로 교체한다. 목록 cache를 비우고 UI가 새 provider 목록을 다시 읽는다. 이 전에는 원본 연결이 계속 활성 상태다.
- `START_EMPTY`는 이전을 생략하고 준비된 빈 구조로 전환한다. 대상 Meetings/Participants에 자료가 있으면 거절하고 새 빈 루트 준비를 요구한다. 확인 화면에서 기존 자료가 앱의 새 서비스 목록에 나타나지 않지만 원본에는 보존됨을 안내한다.
- 실패/취소 시 active는 원본을 유지한다. 부분 복사본은 지우지 않고 journal로 재사용한다. 전환 완료 또는 취소 후 불필요한 draft/source 자격 증명은 snapshot에서 제거한다. 비민감 작업 요약과 ID map은 30일 보관 뒤 정리한다. draft는 마지막 변경 후 24시간 보관하고 활성 operation이 참조하는 동안에는 정리하지 않는다. 완료 과거 operation 조회는 요약만 반환한다.
- 이전으로 Email/Slack을 다시 보내거나 AI/STT를 다시 실행하지 않는다. 기존 이메일·Slack 링크는 원본 주소로 계속 유효하며 자동 갱신하지 않는다.

## 🧩 API와 구현 책임

정확한 HTTP 계약은 [API 명세](./api-spec.md#문서-서비스-설정-api), 저장 모델은 [데이터 명세](./data-spec.md#전역-문서-연결-설정), 화면은 [UI 명세](./ui-design.md#문서-연결-위저드)를 따른다.

| 작업 | 결과 | 선행 |
|---|---|---|
| TASK-022.01 | 암호화한 전역 설정 저장, draft·test API, 활성 연결 Resolver, App Config 상태 | 완료 TASK-002.04, TASK-001.04 |
| TASK-022.02 | Notion/Confluence 기본 5개 Page bootstrap, 재사용·복구, 초기화 operation API | TASK-022.01 |
| TASK-022.03 | 최초 실행 gate, 공용 연결 위저드·Provider 가이드, 완료 후 New Meeting 이동 | TASK-022.02 |
| TASK-022.04 | 양방향 표준 문서 export/import, 참석자 ID map, read-back 검증 | TASK-022.02 |
| TASK-022.05 | COPY_ALL/START_EMPTY 전환, durable journal, 재개·취소, 동시성 gate | TASK-022.04, TASK-022.01 |
| TASK-022.06 | Settings 재설정·이전 화면, 최초/양방향 전환 통합 QA | TASK-022.03, TASK-022.05 |

TASK-022.05는 공용 연결 gate와 업무 등록/쓰기 lease 계약을 만들고 기존 Participant API에 연결한다. 아직 없는 Session/Publish/Delivery API는 소비자 fixture로 검증한다. TASK-022.06의 실제 통합은 설정·참석자 API와 Provider 초기화/복사/read-back이며, Session/History UI는 계약 fixture로 검증한다. 실제 API-006/015/017/018 연동 회귀는 TASK-004.04/010.02/014.01/014.02/021.03의 필수 후속 검증이다. 이 구분으로 설정 작업이 미구현 후속 기능을 선행 조건으로 요구하지 않는다.

개발 순서는 이 표 순서다. TASK-022.06 완료 전 TASK-004.04와 PR #95를 병합하지 않는다. 재개 때 새 master를 반영하고 변경된 연결 gate/API 계약에 맞춰 테스트·리뷰·QA를 다시 수행한다. DONE인 기존 TASK는 역사적 증거로 보존하고 새 변경 TASK에서 수정한다.

## 🔎 공식 기준 확인

2026-10-10 현재 공식 문서를 확인했다. 작성일이 오래된 블로그를 설계 근거로 사용하지 않았다.

- [Notion 페이지 본문·하위 페이지 생성](https://developers.notion.com/guides/data-apis/working-with-page-content): 부모 페이지 접근과 페이지 생성 계약.
- [Notion 인증](https://developers.notion.com/guides/get-started/authorization): 내부 연결과 공유 권한.
- [Confluence Cloud 페이지 API](https://developer.atlassian.com/cloud/confluence/rest/v2/api-group-page/): spaceId·parentId와 페이지 생성 권한.
- [Confluence Basic 인증](https://developer.atlassian.com/cloud/confluence/basic-auth-for-rest-apis/): 2026-10-09 갱신, 회사 내부 운영 범위와 외부 배포 정책 구분.
- [Render Persistent Disks](https://render.com/docs/disks): 재배포 영속성, 유료 서비스·단일 인스턴스·배포 중단 제약.

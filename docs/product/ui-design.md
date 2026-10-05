# 🎨 Meeting Automation UI Design

> **문서 역할:** 선택한 A안(Minimal & Clean)을 Meeting Automation V1의 구현 가능한 UI 기준으로 정의한다.
> **기준 문서:** [PRD](./PRD.md)
> **시안:** [세 가지 UI 시안](../시안_3.png) 중 A안
> **관련 문서:** [Architecture](./architecture.md), [Data Specification](./data-spec.md), [API Specification](./api-spec.md)
> **기준일:** 2026-10-04

## 디자인 목표

**Calm · Minimal · Trustworthy · Mobile Recording · Clear Review**를 따른다. 앱은 기업 Dashboard보다 녹음기와 회의 노트에 가까운 사용 경험을 제공한다.

- 회의 시작과 녹음 중에는 핵심 행동만 보여준다.
- 녹음 화면은 시간을 가장 강하게 보여주고 불필요한 탐색/메뉴를 숨긴다.
- 자동 생성 결과 화면은 Speaker mapping, Template, Minutes, Transcript 순서로 확인할 수 있게 한다.
- 저장·Email·Slack 결과는 각각 구분해 표시한다.
- 밝은 중립 배경과 파란색 한 가지 주요 행동색을 사용한다.
- PRD의 기능 요구사항에 없는 입력이나 흐름은 시안에 있더라도 추가하지 않는다.

## 시안 결정

A안은 흰색 카드와 옅은 회색 바탕, 선명한 파랑 CTA, 얇은 구분선, 절제된 그림자를 사용한다. 주의를 끄는 그라데이션, 다크 배경, 장식 애니메이션은 V1의 기본 스타일에 포함하지 않는다.

시안은 제품 톤과 SCR-001~004의 시각 방향을 정하는 참고물이다. 최종 화면 구성은 PRD의 12개 Screen ID와 FR/API 계약에 맞춘다. 이미지에 있는 날짜·시작시간·예상시간 입력, 바로가기 탭 등 PRD에 없는 요소는 구현 범위로 간주하지 않는다.

## 색상 토큰

### A안에서 가져온 기준 색상

| 토큰 | 색상 | 사용 |
|---|---|---|
| `color-primary-600` | `#2563EB` | 주요 CTA, 활성 링크, 선택 상태 |
| `color-text-primary` | `#111827` | 제목, 핵심 본문, 입력값 |
| `color-background` | `#F9FAFB` | 앱 페이지 배경 |

### 의미 색상 확장

아래 값은 A안의 단순한 팔레트를 기능 상태에 적용하기 위해 추가한 토큰이다. 디자인 검토 후 전역 토큰으로 변경할 수 있다.

| 토큰 | 색상 | 사용 |
|---|---|---|
| `color-surface` | `#FFFFFF` | 카드, Sheet, 입력 배경 |
| `color-text-secondary` | `#4B5563` | 보조 설명, 날짜, 참석자 수 |
| `color-text-muted` | `#6B7280` | 비활성 보조 텍스트, placeholder |
| `color-border` | `#E5E7EB` | 입력 테두리, 구분선, 카드 경계 |
| `color-primary-hover` | `#1D4ED8` | Primary hover/pressed |
| `color-primary-soft` | `#EFF6FF` | 선택된 Speaker, 정보 배경 |
| `color-success` | `#15803D` | 완료 상태 아이콘/텍스트 |
| `color-success-soft` | `#F0FDF4` | 완료 상태 배경 |
| `color-warning` | `#92400E` | 진행 지연, 확인 필요 |
| `color-warning-soft` | `#FFFBEB` | 경고 배경 |
| `color-danger` | `#B91C1C` | 녹음 종료, 오류 |
| `color-danger-soft` | `#FEF2F2` | 오류 배경 |
| `color-focus` | `#1D4ED8` | 키보드 포커스 ring |

```css
:root {
  color-scheme: light;
  --color-primary-600: #2563eb;
  --color-primary-hover: #1d4ed8;
  --color-primary-soft: #eff6ff;
  --color-text-primary: #111827;
  --color-text-secondary: #4b5563;
  --color-text-muted: #6b7280;
  --color-background: #f9fafb;
  --color-surface: #ffffff;
  --color-border: #e5e7eb;
  --color-success: #15803d;
  --color-success-soft: #f0fdf4;
  --color-warning: #92400e;
  --color-warning-soft: #fffbeb;
  --color-danger: #b91c1c;
  --color-danger-soft: #fef2f2;
  --color-focus: #1d4ed8;
}
```

오류/성공/경고 상태는 색에만 의존하지 않고 아이콘, 라벨 또는 짧은 안내 문구를 함께 제공한다. 일반 텍스트 대비는 WCAG AA를 만족해야 한다. 브랜딩 색 변경 시 CTA 텍스트와 상태 색 대비를 다시 측정한다.

## 폰트와 타이포그래피

한국어·영어를 한 계열 안에서 표현하며 OS 기본 글꼴을 우선 사용한다. 별도 웹 폰트를 도입하지 않아 초기 로딩과 외부 의존성을 늘리지 않는다.

```css
font-family:
  Pretendard,
  "Noto Sans KR",
  -apple-system,
  BlinkMacSystemFont,
  "Apple SD Gothic Neo",
  "Malgun Gothic",
  sans-serif;
```

| 역할 | 크기 / 행간 | 굵기 | 적용 |
|---|---|---:|---|
| Display | 48px / 1.1, 작은 화면 40px | 600 | 녹음 Timer |
| Title | 24px / 1.3 | 700 | 화면 제목, 회의명 |
| Heading | 18px / 1.4 | 600 | 섹션 제목, 바텀시트 제목 |
| Body | 16px / 1.5 | 400 | 기본 문장, 회의록 |
| Body emphasis | 16px / 1.5 | 600 | 카드 제목, 선택 값 |
| Label | 14px / 1.4 | 500 | 필드 label, 상태 |
| Caption | 12px / 1.4 | 400 | 보조 정보, 날짜 |

녹음 시간은 숫자 폭이 바뀌어도 UI가 흔들리지 않도록 tabular numeral을 적용한다. 본문 기본 크기는 모바일에서 16px 이상으로 유지하고, 정보 위계를 폰트 크기와 굵기로 만든다.

## 레이아웃과 간격

| 토큰 | 값 | 용도 |
|---|---:|---|
| `space-1` | 4px | 아이콘과 label |
| `space-2` | 8px | 촘촘한 내부 간격 |
| `space-3` | 12px | 필드 내부 요소 |
| `space-4` | 16px | 작은 컴포넌트 간격, 좁은 화면 좌우 여백 |
| `space-5` | 20px | 기본 모바일 좌우 여백 |
| `space-6` | 24px | 섹션 사이 기본 간격 |
| `space-8` | 32px | 주요 섹션 그룹 |
| `content-max` | 640px | 본문 최대 너비 |

- 모바일 단일 컬럼을 기본으로 하고 페이지 본문은 가운데 정렬한다.
- 360px 폭에서 가로 스크롤이 생기지 않아야 한다.
- 일반 페이지 좌우 padding은 20px, 아주 좁은 viewport는 16px이다.
- Primary action이 화면 하단에 고정되면 `safe-area-inset-bottom`을 반영하고 본문이 버튼에 가리지 않게 여유 공간을 둔다.
- Recording과 Processing에서는 Bottom Navigation을 숨긴다.
- 넓은 화면에서도 업무 Dashboard형 다중 컬럼으로 바꾸지 않는다. 본문 폭만 제한한다.

```css
.page-content {
  width: min(100% - 40px, 640px);
  margin-inline: auto;
}

@media (max-width: 391px) {
  .page-content {
    width: calc(100% - 32px);
  }
}

.bottom-action {
  padding-bottom: max(16px, env(safe-area-inset-bottom));
}
```

## 모양과 깊이

| 요소 | 기준 |
|---|---|
| 작은 컨트롤 모서리 | 8px |
| 카드 / 입력 모서리 | 12px |
| Bottom Sheet 상단 모서리 | 20px |
| 버튼 높이 | Primary 최소 52px, 일반 control 최소 48px |
| 카드 그림자 | 기본은 없음. 필요한 경우 `0 1px 3px rgb(17 24 39 / 8%)` 수준 |
| 구분선 | 1px `color-border` |

카드 안에 카드를 반복해 중첩하지 않는다. 경계는 배경 톤과 구분선을 우선하고 그림자는 떠 있는 Sheet처럼 실제 레이어가 있는 경우에만 쓴다.

## 공통 컴포넌트

### Primary Button

- 화면에서 가장 중요한 동작 하나에만 사용한다.
- 파란 배경과 흰색 label을 쓰며 모바일에서는 전체 폭 우선이다.
- 높이 52px 이상, 좌우 padding 16px, radius 10px을 기본으로 한다.
- 로딩 중에는 중복 제출을 막고 진행 상태를 label/icon으로 표시한다.
- Disabled 상태는 색뿐 아니라 상호작용 불가 상태도 표현한다.

### Secondary Button

- 취소, 뒤로, 보조 행동에 사용한다.
- Surface 배경, 기본 텍스트, 테두리로 표현한다.
- Primary와 나란히 쓸 때 종료/삭제처럼 위험한 action만 Danger 스타일로 구분한다.

### Icon Button

- 닫기, 뒤로 등 보조 기능에 사용한다.
- 시각 아이콘은 작더라도 실제 hit area는 최소 44×44 CSS px로 만든다.
- `aria-label` 또는 접근 가능한 이름을 제공한다.

### Input / Select

- label을 입력 위에 항상 노출한다. placeholder만으로 label을 대신하지 않는다.
- 높이 48px 이상, 입력 text 16px, 테두리 1px, radius 8px을 기본으로 한다.
- 오류 문구는 해당 입력과 프로그램적으로 연결한다.
- Multi-select는 선택 수와 선택된 참석자를 확인할 수 있어야 한다.

### Card / Meeting List Item

- 회의 제목을 가장 먼저 읽고 날짜·참석자·완료 상태는 한 단계 낮게 보여준다.
- Home은 최근 회의를 최대 5건까지만 표시한다.
- Meetings는 Provider에서 가져온 100건 미만의 항목을 제목/참석자 기준으로 브라우저에서 필터한다.
- 화면마다 card를 중첩하지 않는다.

### Status / Badge

- 완료·진행·오류를 아이콘과 텍스트로 함께 표시한다.
- 화면 낭독기가 상태 문맥을 얻도록 적절한 live region/status role을 사용한다.
- 상태 변경이 빈번한 녹음/처리 화면은 전체 화면을 live로 만들지 말고 현재 상태만 알린다.

### Bottom Navigation

- 항목은 `홈`, `회의록`, `설정` 세 개로 고정한다.
- 활성 탭은 색과 icon/text 상태로 나타낸다.
- Participants는 Settings 하위에서 접근한다.
- Recording/Processing 중에는 표시하지 않는다.

### Bottom Sheet / Confirmation Dialog

- 종료 확인, Transcript 조회, 참석자 추가 등 보조 흐름에 사용한다.
- 제목, 설명, 닫기/취소 방법, 핵심 action 순서가 명확해야 한다.
- 바깥 영역 탭이나 Escape로 닫히는 경우 입력 손실 여부를 고려한다.
- 회의 종료 확인에서 계속 녹음은 Secondary, 회의 종료는 Danger action이다.

### Speaker Mapping Row

- `Speaker A` label과 Participant dropdown을 한 행/그룹으로 보여준다.
- mapping은 speaker 단위다. Transcript segment마다 개별 화자 선택을 제공하지 않는다.
- 매핑된 사람 이름이 해당 Speaker의 모든 transcript 문장에 적용된다는 점을 Review 화면에서 안내한다.

### Processing Stepper

표준 단계는 Audio upload → STT → Speaker analysis → Minutes generation이다. 실제 Backend 단계와 동기화하며 완료/현재/대기/실패를 구분한다. 각 단계는 icon과 label을 사용하고 진행률을 색만으로 표현하지 않는다.

### Recording Control

- Timer가 Recording 화면의 시각적 중심이다.
- 상태는 `● 녹음 중`처럼 점과 텍스트를 같이 표시한다.
- 종료 버튼을 가장 명확한 action으로 제공한다.
- 경과 시간을 tabular numeral로 고정 폭 표시한다.
- 녹음 중 깜박임/파형 움직임을 사용하는 경우 `prefers-reduced-motion` 환경에서는 정지 표현을 쓴다.

## 화면 정의

### SCR-001 Home

**목적:** 새 회의를 시작하거나 최근 회의로 돌아간다.

- App title과 서비스 목적을 간단하게 표시한다.
- 파란색 Primary `새 회의 시작` 버튼을 화면 상단 주요 영역에 둔다.
- 최근 회의는 최대 5건이며 제목, 회의 일시, 참석자 수, 완료 상태를 보여준다.
- `전체 보기`는 Meetings 화면으로 이동한다.
- 회의가 없으면 최근 목록 자리에 짧은 Empty state를 보여주고 시작 버튼은 그대로 노출한다.
- Loading/Error 상태와 재시도 action을 정의한다.
- Bottom Navigation을 표시한다.
- 구현 단계에서 Home shell과 새 회의 진입은 TASK-004.01, 최근 회의 목록·전체 보기는 TASK-014.03에서 연결한다.

### SCR-002 New Meeting

**목적:** 회의 Session을 만들기 위한 최소 정보 입력.

- 회의명 text field, Template select, 참석자 multi-select, 참석자 추가 action을 세로로 배치한다.
- 회의명과 Template은 필수, 참석자는 최소 1명 선택해야 한다.
- `참석자 추가`는 API-003 roster에서 미선택 참석자를 multi-select에 추가한다. 새 roster record 관리는 Participants 설정 화면에서 한다.
- 폼 오류를 해당 필드 근처에 표시하고 입력값을 보존한다.
- 하단 Primary `회의 시작`을 제공한다. 비활성 사유는 label/help text로 알린다.
- PRD 범위에 없는 날짜·시작시간·예상시간 입력은 추가하지 않는다.

### SCR-003 Recording

**목적:** 테이블 중앙에 놓인 모바일을 방해 없이 녹음기로 사용.

- 회의명, Recording indicator, 경과 시간, 큰 종료 버튼만 주요 콘텐츠로 표시한다.
- 마이크 권한 거부/미지원, MediaRecorder 오류, 업로드 연결 상태를 사용자에게 식별 가능하게 표시한다.
- 녹음 중 Bottom Navigation과 설정 진입을 숨긴다.
- 미전송 chunk가 있으면 종료 후 처리 시작이 안전한 상태가 아님을 명확히 알려준다.
- 화면 잠금/앱 전환 지원 여부는 실제 기기 검증 결과와 PRD Phase 8 범위를 따른다.

### SCR-004 End Confirm

**목적:** 종료 전에 녹음을 계속할지 끝낼지 명시적으로 확인.

- Bottom Sheet 또는 접근 가능한 modal로 보여준다.
- 안내는 녹음 종료 후 업로드와 회의록 작성이 시작됨을 설명한다.
- `계속 녹음`은 Secondary, `회의 종료`는 Danger action이다.
- 확인 취소 시 현재 녹음 Session을 유지한다.

### SCR-005 Processing

**목적:** Audio 업로드와 AI 처리 상태를 알려 사용자가 기다릴 수 있게 한다.

- 단계는 녹음 업로드, 음성 변환(STT), 화자 분석, 회의록 작성 순서다.
- 상태와 실패 단계는 Backend 응답과 동기화한다.
- 대기 상태에서는 “잠시만 기다려 주세요”와 함께 단계별 완료/현재/대기를 구분한다.
- 네트워크 끊김, 처리 실패에는 안전한 오류 요약과 가능한 재시도만 표시한다.
- Bottom Navigation을 숨긴다.

### SCR-006 Review

**목적:** 사용자가 필요한 사실 확인과 수정을 한 번에 마친다.

- 정보 우선순위는 Speaker mapping → Template → Minutes → Transcript 원문이다.
- 감지된 각 Speaker에 Participant dropdown을 하나씩 제공한다. 선택한 참석자를 우선 제안한다.
- Template 변경 시 Minutes만 재생성되며 Transcript/STT는 재실행하지 않는다는 설명을 표시한다.
- 재생성 전 기존 사용자 수정 Minutes가 교체될 수 있음을 확인한다.
- Summary, discussion points, decisions, action items, follow-ups는 편집 가능한 구조로 제공한다.
- Transcript 원문은 별도 Sheet/Drawer에서 읽는다. Speaker mapping은 speaker 단위로 유지한다.
- 화면 하단에 `회의록 확정` Primary action을 제공하고, 누락된 mapping 등 확정 불가 이유를 알려준다.

### SCR-007 Share

**목적:** 저장 위치, Email 수신자, 알림 여부를 확인 후 공유 시작.

- Document Provider 및 `Meeting Automation / Meetings` 저장 위치를 표시한다.
- Email은 선택된 Participant 단위로 checkbox와 주소를 보여준다. 개인정보가 과도하게 노출되지 않게 필요한 범위에서 표시한다.
- Slack notification은 on/off 선택과 현재 상태를 보여준다.
- Primary action은 `저장하고 공유`다.
- Document 저장 실패 시 Email/Notification을 보내지 않는 처리 순서를 사용자에게 혼동 없이 보여준다.
- 전송 중에는 중복 Publish를 막고 진행 상태를 표시한다.

### SCR-008 Complete

**목적:** 문서 저장, 수신자별 Email, Notification 결과를 각각 확인한다.

- 문서/Email/Slack을 독립된 결과 행으로 표시한다.
- 성공·부분 실패·전체 실패를 각각 다른 제목과 텍스트로 표현한다.
- 실패한 Delivery에만 Retry action을 제공한다. 성공한 수신자에게 재전송하지 않는다.
- `회의록 보기`와 `홈으로`를 제공한다.
- Document URL은 성공적으로 저장된 경우에만 연다.

### SCR-009 Meetings

**목적:** 저장된 회의 이력을 읽고 찾는다.

- 제목과 참석자 기준 검색 field를 상단에 둔다.
- 결과는 Provider에서 가져온 최대 100건 미만을 브라우저에서 필터한다.
- 월별 그룹, 제목, 날짜, 참석자 이름을 보여준다.
- Empty, Loading, Provider Error 상태를 정의한다.
- 각 row는 읽기 전용 Meeting Detail로 이동한다. Bottom Navigation을 표시한다.

### SCR-010 Meeting Detail

**목적:** 과거 회의록과 원문 Transcript를 확인하고 Provider 원문을 연다.

- 회의명, 회의 일시, 참석자, Summary, 논의사항, 결정사항, Action Items를 순서대로 보여준다.
- Transcript 원문은 별도 Sheet/Drawer에서 연다.
- `Notion/Confluence에서 열기` 링크는 새 문서 URL을 사용한다.
- 앱 내부 수정 action은 표시하지 않는다.
- Bottom Navigation을 표시한다.

### SCR-011 Settings

**목적:** 회사와 Document/Email/Notification 연결 상태를 확인한다.

- 회사 정보, Document Provider, Email, Notification 상태를 읽기 쉬운 설정 row로 표시한다.
- Participants 관리로 이동하는 항목을 제공한다.
- `설정됨`, `연결 확인 필요`, `확인 실패` 같은 텍스트와 아이콘을 함께 쓴다.
- `document.provider=null` 또는 `document.configured=false`이면 Settings의 문서 저장 row에 연결이 필요하다는 안내와 관리자에게 연결 설정을 요청하는 다음 행동을 표시한다. 설정을 자동 선택하거나 화면에서 Secret 입력값을 재노출하지 않는다.
- API Key, Token, Webhook URL 등 Secret 원문을 표시하지 않는다.
- Bottom Navigation을 표시한다.

### SCR-012 Participants

**목적:** 회의에 사용할 참석자를 관리한다.

- Participant name/email 목록과 검색 field를 제공한다.
- 추가/수정 폼은 name과 email만 받는다.
- 이름과 이메일을 목록에서 확인하고 추가/수정할 수 있다.
- 새 참석자 추가/수정은 Bottom Sheet 또는 간결한 폼 화면으로 처리한다.
- API 목록 결과를 화면에서 이름/email 부분 문자열로 대소문자 구분 없이 검색한다.
- 첫 조회 Loading, 검색 결과 없음/roster Empty, 조회 Error와 재시도, 목록 Ready를 구분한다. 검색 결과 없음은 검색어를 지우는 동작을 제공한다.
- 각 row에서 수정 action을 제공한다. 저장 성공 후 목록을 즉시 반영하고, 실패 시 입력값을 유지하며 입력 오류는 해당 필드에 표시한다.
- 생성 재시도는 해당 입력 시도의 `Idempotency-Key`를 재사용하고, 성공 뒤 새 생성 시도는 새 키를 사용한다.
- 제거/삭제 action은 제공하지 않는다.
- Bottom Navigation의 Settings 경로로 돌아갈 수 있다.

## 상태와 피드백

모든 주요 조회 화면에 Ready, Loading, Empty, Error 상태를 정의한다. 제출 과정에는 Submitting 상태를 더한다.

- 로딩은 Skeleton 또는 단순 progress indicator를 사용한다. 화면 전체를 불필요하게 깜박이지 않는다.
- Empty에는 왜 비었는지와 가능한 다음 행동을 짧게 안내한다.
- Error에는 사용자가 할 수 있는 복구 행동과 안전한 오류 요약을 제공한다. 내부 stack trace/Provider 응답 원문은 노출하지 않는다.
- 비동기 상태는 실제 Backend 상태를 기준으로 한다. UI 애니메이션만으로 완료를 가장하지 않는다.
- 입력 오류는 입력 근처에 표시하고 유효한 기존 값을 보존한다.

## 접근성과 모바일 동작

- 모든 주요 터치 대상은 최소 44×44 CSS px, Primary button은 높이 52px 이상으로 한다.
- 키보드/보조기술로 순서대로 접근할 수 있고, focus ring을 숨기지 않는다.
- 입력은 항상 label과 연결한다. 아이콘 단독 버튼은 접근 가능한 이름을 가진다.
- 상태와 검증 오류를 색상만으로 표현하지 않는다.
- 일반 본문과 배경은 WCAG AA 대비 기준을 만족한다.
- `prefers-reduced-motion`을 존중한다.
- 화면 하단 고정 동작은 iOS/Android safe area와 가상 키보드를 고려한다.
- 360px 이상 폭에서 가로 스크롤이 없어야 한다.

## 구현 규칙

- CSS 변수 이름은 이 문서의 semantic token을 사용한다. 컴포넌트별 raw hex 복제를 피한다.
- 공통 버튼, 입력, 상태, 하단 내비게이션, Sheet는 공통 UI 계층에서 재사용한다.
- 도메인 기능 컴포넌트는 `features/meeting`, `features/history`, `features/settings` 등 PRD 화면 흐름에 따라 둔다.
- 아이콘은 의미가 명확한 일관된 단일 icon set을 사용하고 emoji를 제품 UI icon 대용으로 사용하지 않는다.
- 반응형 breakpoint는 작은 화면 우선으로 설계하고 최소 360px과 실제 Android/iOS에서 확인한다.
- 이미지 시안의 내용을 그대로 복제하지 말고 API 상태, 오류, 접근성 요구까지 반영한다.

## 추적성

이 문서는 PRD의 `SCR-001~012`, `FR-001~030`, `NFR-001~003`, `NFR-010`, `NFR-012`를 시각/상호작용 규칙으로 구체화한다. 화면 기능과 API 연결은 PRD Traceability Matrix 및 [API Specification](./api-spec.md)을 따른다.

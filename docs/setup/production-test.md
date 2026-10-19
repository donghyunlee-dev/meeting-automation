# 운영 테스트 설정 기준

> **현재 방향:** Vercel은 Frontend만 제공하고, Backend가 오디오 chunk를 직접 받아 처리한다. Vercel Blob과 오디오 제어 Functions 사용 계획은 취소했다.
> **상태:** 운영 환경 설정의 최종 기준이 아니다. Backend 저장 방식과 Render Free 적합성 결정이 남아 있다.
> **미적용:** 저장소 코드와 현재 배포 설정은 아직 이 방향으로 바뀌지 않았다. 배포 점검 기록상 Vercel에는 Blob 변수명이 등록돼 있고, 기존 배포가 새 Backend 흐름을 실행한다고 간주하지 않는다.

제품·아키텍처 방향은 [Backend 소유 Audio 설계](../superpowers/specs/2026-10-16-backend-owned-audio-design.md)와 [최초 연결·자동 발송 기준 흐름](../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md), 실제 배포 관측값은 [2026-10-16 배포 점검 기록](../evidence/2026-10-16-deployment-audit.md)을 참고한다. 이전 Vercel Blob 문서는 철회된 계획의 기록이다. 이 문서는 목표 구성과 미결 배포 선택을 기록하며, 현재 소스가 모두 구현됐거나 운영 배포가 검증됐다는 뜻은 아니다.

## 제품 동작의 운영 전제

- Notion 또는 Confluence 중 하나의 연결과 초기 기본 페이지 생성은 회의 기능의 필수 gate다. 앱과 설정 위저드는 연결 전에도 열려야 한다.
- 최초 방문은 연결 위저드로 시작한다. 자격 증명 연결 테스트 성공 후 사용자가 기본 페이지 생성을 시작하고, `Home`, `Meetings`, `Participants`, `Templates`, `Speaker Memory`를 Provider에서 생성 또는 확인하고 다시 읽어야 준비 완료다.
- 최초 초기화가 끝나면 `New Meeting`으로 이동한다. 다음 방문은 Home에서 새 미팅과 이전 회의록 목록을 보여준다.
- 회의 참가자에는 이메일이 있어야 한다. 녹음·전사 이후 사용자가 모든 화자를 참가자에 연결해야 Minutes 생성이 시작된다. Minutes 생성과 문서 저장이 성공한 뒤 매핑된 참가자에게 Gmail로 자동 발송한다.
- OpenAI와 Gmail 자격 증명은 운영 Secret으로 제공한다. Slack은 핵심 흐름의 필수 연결이 아니다. Notion/Confluence가 아직 연결되지 않은 상태에서 회의 생성은 허용하지 않는다.

## 서비스 경계

| 서비스 | 역할 | 현재 판단 |
|---|---|---|
| Vercel | React/Vite 정적 Frontend 배포 | 유지. Blob store, 오디오 Functions, 오디오 공유 secret은 사용하지 않음 |
| Render | Spring Boot Backend, 회의 API, 오디오 수신·조립·AI 및 메일 연동 | 후보. Free의 임시 파일·설정 저장·재시작 복구 한계 검토 필요 |

## Vercel Frontend 설정

| 항목 | 값 |
|---|---|
| Git repository / production branch | `donghyunlee-dev/meeting-automation` / `master` |
| Root Directory | `frontend` |
| Framework / Node.js | Vite / 24.x |
| Install / Build / Output | `npm ci` / `npm run build` / `dist` |
| Production `VITE_API_BASE_URL` | 실제 Backend 공개 URL |

브라우저 라우트를 직접 열거나 새로고침할 수 있도록 SPA fallback을 설정한다. Preview 배포도 사용할 경우 Preview URL이 Backend CORS 허용 목록에 있어야 한다. 환경 변수 변경은 새 배포에 적용된다. Frontend 프로젝트에 `BLOB_READ_WRITE_TOKEN`, `RENDER_AUDIO_STORAGE_SECRET`, `RENDER_AUDIO_GRANT_SECRET` 또는 OpenAI/Gmail 비밀값을 설정하지 않는다. `VITE_` 변수는 브라우저에 포함되므로 Backend URL 외 비밀값을 넣지 않는다.

## Backend 설정: 아직 확정 전

Render에서 현재 필요한 설정 이름은 Render 콘솔에 등록된 실제 값과 코드 기준을 맞춰야 한다. 아래는 설정 범주이며, Render Free로 운영 가능한 구성을 확정했다는 뜻은 아니다.

| 범주 | 설정 | 남은 확인 |
|---|---|---|
| 기본 | `APP_COMPANY_ID`, `APP_COMPANY_NAME`, `APP_TIMEZONE` | 운영 값 확정 |
| Browser 접근 | `ALLOWED_ORIGINS` | Production/Preview Frontend origin 반영 |
| 설정 저장 | `DOCUMENT_SETTINGS_DIR`, `DOCUMENT_SETTINGS_ENCRYPTION_KEY` | Render는 메모리 저장소와 브라우저 localStorage 자동 복구를 사용한다. 복구는 동일 브라우저가 접속한 뒤 진행된다. |
| 오디오 임시 파일 | `TEMP_AUDIO_DIR` | 쓰기 가능한 임시 경로. Free에서는 영속성이 없고 처리 중 재시작 시 파일 유실 가능 |
| 오디오 정책 | chunk 크기, 업로드 제한 시간, 최대 회의 길이, `TRANSCRIPTION_MAX_AUDIO_BYTES` | 현재 전사 입력 기본 상한 25 MiB, UI 녹음 시간 최대 60분이 서로 맞지 않음 |
| AI | `OPENAI_API_KEY`, `TRANSCRIPTION_MODEL`, `MINUTES_MODEL` | 사용자가 제공하는 Secret과 모델명 등록 필요 |
| 메일 | `EMAIL_PROVIDER=GMAIL_API`, `EMAIL_OAUTH_CLIENT_ID`, `EMAIL_OAUTH_CLIENT_SECRET`, `EMAIL_OAUTH_REFRESH_TOKEN`, `EMAIL_SENDER_ADDRESS` | Gmail API 접근 설정 필요. 제품에 OAuth 연결 화면은 없음 |
| 선택 알림 | `NOTIFICATION_PROVIDER`, Slack webhook 값 | Slack을 쓰는 경우만 필요 |

비밀값은 저장소나 문서에 적지 않는다. Render Free 웹 서비스는 Persistent Disk를 지원하지 않으며 파일시스템 변경이 재시작, 재배포, 절전 시 사라진다. 15분간 inbound traffic이 없으면 절전하고 다음 요청에 약 1분 정도 기동 시간이 필요할 수 있다. 녹음 중 매 15초 chunk 요청은 정상 수신되는 동안 절전을 막는 효과가 있지만, 임의 재시작이나 메모리 Session 손실을 방지하지 않는다. [Render 무료 플랜](https://render.com/docs/free), [Render Persistent Disk](https://render.com/docs/disks).

## 운영 테스트 전에 결정할 것

1. 문서 연결 정보는 브라우저 localStorage에 보관되고 같은 브라우저가 Render 재시작 후 Backend 설정을 복구한다. 다른 기기에서는 별도 연결이 필요하다.
2. 진행 중 회의가 Backend 재시작으로 사라져도 되는지, 브라우저 IndexedDB의 원본 오디오를 새 Session에 재업로드할 수 있으면 충분한지.
3. 테스트 최대 녹음 시간·파일 크기·동시 회의 수를 정하고 25 MiB 기본 입력 제한과 맞출 것.
4. Render Free의 임시 Audio 처리와 메모리 Session 손실을 허용할 수 있는지 실제 짧은 회의로 확인할 것. 재시작 이후 Audio/Session 복구가 필요하면 별도 영속 저장소를 선택할 것.
5. Frontend가 Backend에 직접 업로드하는 요청 크기 제한·멱등성·재시도 계약을 실제 배포에서 확인할 것.

위 항목이 정해지기 전에는 Render 운영 테스트가 완료됐다고 보지 않는다. 특히 Render `/actuator/health`의 `UP`은 API 프로세스만 확인하며, 저장소·AI·메일·회의 전체 흐름의 준비 완료를 의미하지 않는다.

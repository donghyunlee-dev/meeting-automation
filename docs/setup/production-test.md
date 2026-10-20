# Vercel·Render 운영 설정 및 확인

> 이 문서는 저장소에 구현된 배포 구성을 설명합니다. 현재 배포의 설정값·상태·로그를 확인하지 않은 항목은 검증된 운영 사실로 간주하지 않습니다.

## 서비스 구성

| 서비스 | 역할 | 저장·실행 특성 |
|---|---|---|
| Vercel | React/Vite Frontend 정적 파일 배포 | 브라우저에서 Backend URL로 직접 API와 오디오 요청 전송 |
| Render | Spring Boot Backend 실행 | 세션·작업 상태는 메모리, Audio는 임시 파일, 설정 저장은 런타임 메모리 |
| Notion 또는 Confluence | 참석자·템플릿·완료 회의 문서 저장 | 연결된 Provider가 업무 문서의 영속 저장소 |

Vercel Blob, Vercel Audio Functions, S3는 현재 오디오 업로드 경로에서 사용하지 않습니다. Frontend는 오디오 조각을 Render Backend API로 직접 전송합니다.

## Vercel Frontend 설정

| 항목 | 값 |
|---|---|
| 저장소 / 배포 브랜치 | `donghyunlee-dev/meeting-automation` / `master` |
| Root Directory | `frontend` |
| Framework / Node.js | Vite / 24.x |
| Install / Build / Output | `npm ci` / `npm run build` / `dist` |
| `VITE_API_BASE_URL` | Render Backend의 공개 HTTPS URL |

Vercel Preview를 사용할 경우 해당 Origin을 Backend `ALLOWED_ORIGINS`에 포함합니다. `VITE_` 변수는 브라우저에 전달되므로 Backend 주소 외 비밀값을 두지 않습니다. OpenAI·Gmail 자격 증명과 저장소 암호화 키는 Vercel 환경 변수에 넣지 않습니다.

## Render Backend 설정

Backend는 Dockerfile로 실행하며 Render가 제공하는 `$PORT`를 사용합니다. `/actuator/health`는 프로세스 상태 확인용입니다. 이 응답만으로 OpenAI, Notion/Confluence, 이메일, Slack 기능까지 정상이라고 판단할 수 없습니다.

필요한 설정 이름은 다음과 같습니다.

| 설정 | 용도 |
|---|---|
| `ALLOWED_ORIGINS` | 운영 Frontend Origin 허용 |
| `OPENAI_API_KEY` | OpenAI 음성 인식·회의록 생성 |
| `TRANSCRIPTION_MODEL`, `MINUTES_MODEL` | 필요 시 기본 AI 모델 변경 |
| `TEMP_AUDIO_DIR` | 오디오 조각·조립본 임시 파일 경로 |
| `EMAIL_PROVIDER`, `EMAIL_OAUTH_CLIENT_ID`, `EMAIL_OAUTH_CLIENT_SECRET`, `EMAIL_OAUTH_REFRESH_TOKEN`, `EMAIL_SENDER_ADDRESS` | Gmail API 발송 기본 설정 및 fallback |
| `NOTIFICATION_PROVIDER`, `SLACK_MEETING_WEBHOOK_URL` | Slack 알림 fallback |
| `APP_COMPANY_ID`, `APP_COMPANY_NAME`, `APP_TIMEZONE` | 공개 앱 설정 |

Gmail·Slack은 설정 화면에서 입력한 값이 Backend의 전역 설정에도 저장됩니다. 브라우저 `localStorage`는 Backend 재시작 후 같은 브라우저에서 설정을 다시 등록하기 위한 복구본입니다. 문서 연결 입력도 동일한 브라우저 복구 방식입니다. 이 저장 정보는 브라우저 프로필과 기기에 한정됩니다.

Render 설정 저장은 현재 프로세스 메모리 기반이며 서비스 재시작·재배포 이후 유지되지 않습니다. Backend API가 먼저 빈 설정을 반환할 수 있고, 설정을 복구하는 브라우저가 해당 화면을 열어 저장 API를 호출한 뒤 설정이 다시 등록됩니다. 별도 Backend DB나 Render disk를 통해 복구하는 구조는 아닙니다.

## 운영상 데이터 한계

- 회의 Session과 처리·전달 상태는 Backend 메모리에 있어 프로세스 재시작 시 복구되지 않습니다.
- Audio는 Render Backend의 임시 파일에 보관합니다. 영구 저장이나 재시작 뒤 복원을 보장하지 않습니다.
- 회의록 초안 생성 성공 뒤 Audio를 정리합니다. 처리 실패 시 재시도에 필요한 임시 파일을 제한된 기간 보존합니다.
- 저장이 완료된 회의와 참석자·템플릿은 연결된 Notion 또는 Confluence에서 다시 읽을 수 있습니다.
- 운영자가 Render 재시작·절전 복귀·배포 후 복구를 시험할 경우 문서 연결 설정, Gmail, Slack을 각각 조회하고 데이터가 재등록됐는지 확인해야 합니다. 각 설정은 브라우저 localStorage 복구와 Backend API 저장이 모두 완료되어야 합니다.

## 배포 후 확인 순서

1. Vercel 사이트가 열리고 SPA 경로를 직접 새로고침해도 로드되는지 확인합니다.
2. 브라우저 Network에서 API 요청이 `VITE_API_BASE_URL`의 Backend로 가는지, CORS 오류가 없는지 확인합니다.
3. Render 로그와 `GET /api/v1/system/health`를 확인합니다. 프로세스 health와 기능 연동 상태는 별도로 판단합니다.
4. 같은 브라우저로 설정 화면을 열어 문서·이메일·Slack 상태를 각각 확인합니다. 필요한 경우 브라우저 복구가 완료될 때까지 기다린 후 다시 조회합니다.
5. 비운영 테스트 회의로 오디오 업로드, 음성 처리, 검토, 문서 저장, 연결된 이메일·Slack 전달을 각각 확인합니다.
6. 저장된 회의가 Notion/Confluence 목록·상세에서 다시 조회되는지 확인합니다.

오류 분석 시 trace ID, Backend 오류 코드와 실패 단계를 함께 확인하되 Secret, 전체 이메일, 오디오, Transcript, 회의록 본문을 로그나 캡처에 복사하지 않습니다.

## 과거 기록

2026-10-15 Vercel Blob 오디오 저장 설계는 취소된 제안입니다. 2026-10-16 배포 점검은 그 시점의 배포 관측 기록이며, 오늘의 운영 배포 상태를 뜻하지 않습니다. 관련 내용은 [과거 배포 감사](../evidence/2026-10-16-deployment-audit.md)와 [취소된 Vercel Blob 설계](../superpowers/specs/2026-10-15-vercel-blob-audio-storage-design.md)를 참고하세요.

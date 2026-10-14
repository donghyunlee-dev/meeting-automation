# 사용자 직접 테스트 준비

이 안내는 사용자가 로컬에서 설정부터 회의 기록·검토·공유까지 확인할 때 사용한다. 실제 Provider 계정과 녹음 장비를 이용한 검증은 개발 완료 후 사용자가 진행한다.

## 1. 준비물

- Java 25와 저장소의 Gradle Wrapper
- Node.js 22.12 이상과 npm
- OpenAI API key와 사용할 수 있는 전사·회의록 모델 ID
- 회의 문서를 저장할 Notion 또는 Confluence 계정과 설정 권한
- 마이크 권한이 있는 브라우저
- 결과를 이메일로 공유하려면 Gmail OAuth 설정. Slack 알림을 시험하려면 Slack webhook도 준비한다.

상세 설치 방법은 [Backend 개발 환경](./backend-setup.md)과 [Frontend 개발 환경](./frontend-setup.md)을 참고한다.

## 2. Backend 설정

Backend를 실행하는 IDE Run Configuration 또는 PowerShell 프로세스 환경에 `backend/.env.example`의 값을 설정한다. Spring Boot는 이 예제 파일이나 `.env.local`을 자동으로 읽지 않으므로 실제 비밀값을 저장소 파일에 넣지 않는다.

회의 생성·전사·회의록 작성에 필요한 최소 항목:

- `APP_COMPANY_ID`, `APP_COMPANY_NAME`, `APP_TIMEZONE`
- `DOCUMENT_SETTINGS_DIR`: 저장소 바깥의 쓰기 가능한 로컬 경로
- `DOCUMENT_SETTINGS_ENCRYPTION_KEY`: Base64로 인코딩한 임의의 32바이트 키
- `OPENAI_API_KEY`, `TRANSCRIPTION_MODEL`, `MINUTES_MODEL`
- `PRIVATE_AUDIO_S3_BUCKET`: Audio upload/processing에 쓸 private bucket
- `PRIVATE_AUDIO_S3_REGION` 또는 `AWS_REGION`; AWS credentials chain 또는 해당 bucket에 한정된 Backend service role
- `ALLOWED_ORIGINS=http://localhost:5173`

메일 공유를 시험할 때는 `EMAIL_PROVIDER`, `EMAIL_OAUTH_CLIENT_ID`, `EMAIL_OAUTH_CLIENT_SECRET`, `EMAIL_OAUTH_REFRESH_TOKEN`, `EMAIL_SENDER_ADDRESS`도 설정한다. Slack 알림을 시험할 때는 `NOTIFICATION_PROVIDER`와 사용할 webhook 값을 설정한다. 값의 전체 목록과 저장 원칙은 [Backend 설정 안내의 환경변수 절](./backend-setup.md)을 따른다.

Backend 디렉터리에서 실행한다.

```powershell
Set-Location backend
java -version
.\gradlew.bat --version
.\gradlew.bat bootRun
```

두 버전 출력 모두 Java 25인지 확인한다. `bootRun`이 시작된 PowerShell 창은 열린 채로 둔다.

## 3. Frontend 실행

새 PowerShell 창에서 다음을 실행한다.

```powershell
Set-Location frontend
npm ci
npm run dev
```

`frontend/.env.local`에 Backend 주소를 둔다. 기본 주소를 쓸 때 파일 내용은 다음과 같다.

```dotenv
VITE_API_BASE_URL=http://localhost:8080
```

터미널이 출력한 로컬 주소(기본 `http://localhost:5173`)를 브라우저에서 연다.

## 4. 설정과 회의 테스트 순서

1. `/settings`에서 설정 위저드를 열고 Notion 또는 Confluence 연결과 회의 문서 구조를 등록한다.
2. 새 회의를 시작하고 브라우저의 마이크 권한을 허용한다.
3. 짧은 테스트 녹음을 시작한 뒤 종료하고 업로드·처리가 끝날 때까지 기다린다.
4. 전사 내용과 생성된 회의록을 검토하고 필요한 내용을 수정해 저장한다.
5. 메일 또는 Slack 공유 설정을 준비한 경우 공유 동작을 확인한다.
6. 완료 화면으로 이동한 뒤 Home의 최근 회의와 `/meetings` 목록에서 방금 만든 회의를 확인한다.

화면에서 오류가 나면 Backend와 Frontend 터미널의 오류, 브라우저 Console/Network, 설정 위저드의 연결 상태를 확인한다. 로그나 캡처를 공유할 때 API key, OAuth 토큰, 문서 내용, 녹음·전사 원문은 포함하지 않는다.

## 테스트 결과 기록

- 설정 위저드 완료 여부:
- 녹음 및 업로드 결과:
- 전사·회의록 처리 결과:
- 검토·저장·공유 결과:
- Home/Meetings 목록 표시 결과:
- 오류 문구와 재현 순서(민감정보 제거):

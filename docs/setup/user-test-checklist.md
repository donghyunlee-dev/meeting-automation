# 사용자 직접 테스트 준비

이 안내는 로컬 Frontend에서 Backend까지 회의 흐름을 확인할 때 사용한다. 오디오 chunk는 Backend API로 직접 전송하는 방향이다. 실제 Provider 계정과 녹음 장비를 이용한 검증은 사용자가 진행한다.

**현재 구현 기준:** 첫 방문에는 Notion 또는 Confluence 연결 위저드를 보여준다. 연결 테스트와 별도로 사용자가 기본 페이지 생성을 시작하고 구조 확인이 끝나야 준비된다. 초기 구조는 `Meeting Automation` 아래 `Meetings`, `Participants`, `Meeting Data`, `Templates`다. 녹음·전사·화자 구간 분석과 회의록 초안 생성이 끝나면 검토 화면에서 참석자 매핑과 회의록을 저장하고, 검토 완료를 실행한다. 그 뒤 선택 템플릿에 맞춘 문서를 저장하고 활성화된 이메일과 Slack 알림을 처리한다. 상세 저장 동작은 [현재 구현 기준](../product/implementation-status.md)을 따른다.

Vercel·Render 배포에서 시험할 때는 [운영 테스트 설정 기준](./production-test.md)을 먼저 따른다.

## 1. 준비물

- Java 25와 저장소의 Gradle Wrapper
- Node.js 24와 npm
- OpenAI API key와 사용할 수 있는 전사·회의록 모델 ID
- 회의 문서를 저장할 Notion 또는 Confluence 계정과 설정 권한
- 마이크 권한이 있는 브라우저
- 자동 이메일을 검증하려면 Gmail OAuth 설정.
- Slack 설정은 V1 핵심 흐름의 준비물이 아니다.

상세 설치 방법은 [Backend 개발 환경](./backend-setup.md)과 [Frontend 개발 환경](./frontend-setup.md)을 참고한다.

## 2. Backend 설정

Backend를 실행하는 IDE Run Configuration 또는 PowerShell 프로세스 환경에 `backend/.env.example`의 값을 설정한다. Spring Boot는 이 예제 파일이나 `.env.local`을 자동으로 읽지 않으므로 실제 비밀값을 저장소 파일에 넣지 않는다.

회의 생성·전사·회의록 작성에 필요한 최소 항목:

- `APP_COMPANY_ID`, `APP_COMPANY_NAME`, `APP_TIMEZONE`
- `DOCUMENT_SETTINGS_DIR`: 저장소 바깥의 쓰기 가능한 로컬 경로
- `DOCUMENT_SETTINGS_ENCRYPTION_KEY`: Base64로 인코딩한 임의의 32바이트 키
- `OPENAI_API_KEY`, `TRANSCRIPTION_MODEL`, `MINUTES_MODEL`
- `TEMP_AUDIO_DIR`: Backend가 처리 중 Audio chunk와 조립 파일을 둘 임시 디렉터리
- `ALLOWED_ORIGINS=http://localhost:5173,http://127.0.0.1:5173`

자동 이메일을 시험할 때는 `EMAIL_PROVIDER`, `EMAIL_OAUTH_CLIENT_ID`, `EMAIL_OAUTH_CLIENT_SECRET`, `EMAIL_OAUTH_REFRESH_TOKEN`, `EMAIL_SENDER_ADDRESS`도 설정한다. 값의 전체 목록과 저장 원칙은 [Backend 설정 안내의 환경변수 절](./backend-setup.md)을 따른다. Slack은 이 테스트의 선행 조건이 아니다.

Backend 디렉터리에서 실행한다.

```powershell
Set-Location backend
java -version
.\gradlew.bat --version
.\gradlew.bat bootRun
```

`java -version`과 Gradle Wrapper JVM이 Java 25인지 확인한다. Frontend 실행 전 `node --version`이 `frontend/.nvmrc`의 Node 24 버전인지도 확인한다. `bootRun`이 시작된 PowerShell 창은 열린 채로 둔다.

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

`npm run dev`는 화면만 실행하므로 Backend도 별도로 실행하고 `VITE_API_BASE_URL`을 설정한다. Audio 업로드·처리의 운영 방식과 Render Free 제한은 [운영 테스트 설정](./production-test.md)과 [Backend 소유 Audio 설계](../superpowers/specs/2026-10-16-backend-owned-audio-design.md)를 확인한다.

## 4. 설정과 회의 테스트 순서

1. 첫 화면에서 Notion 또는 Confluence를 선택하고 자격 증명을 입력해 연결 테스트를 실행한다.
2. 테스트 성공 후 명시적으로 기본 페이지 생성을 시작한다. Meetings, Participants, Meeting Data, Templates가 생성·재조회로 확인된 뒤 준비 완료가 되는지 확인한다. 새 미팅으로 이동하는지 확인한다.
3. 새 회의를 시작하고 브라우저의 마이크 권한을 허용한다. 참가자 이메일이 회의 roster에 등록되어 있는지 확인한다.
4. 짧은 테스트 녹음을 시작한 뒤 종료하고 업로드·전사·화자 구분이 끝날 때까지 기다린다.
5. 화자를 회의 참석자에 연결하고 매핑을 저장한다. 회의록 초안의 내용을 검토·수정하고, 회의록 변경을 저장한 뒤 검토 완료를 누른다.
6. 문서 작성 단계에서 선택 템플릿에 맞춘 페이지가 생성·저장되는지 확인한다. 문서 저장 성공 뒤 설정된 이메일과 Slack 결과를 회의 결과 화면에서 확인한다.
7. 다음 방문에 Home의 새 미팅과 이전 회의록 목록이 보이는지 확인한다.

화면에서 오류가 나면 Backend와 Frontend 터미널의 오류, 브라우저 Console/Network, 설정 위저드의 연결 상태를 확인한다. 로그나 캡처를 공유할 때 API key, OAuth 토큰, 문서 내용, 녹음·전사 원문은 포함하지 않는다.

## 테스트 결과 기록

- 설정 위저드 완료 여부:
- 녹음 및 업로드 결과:
- 전사·회의록 처리 결과:
- 화자 매핑·자동 회의록 생성 결과:
- 문서 저장·수신자별 자동 이메일 결과:
- Home/Meetings 목록 표시 결과:
- 오류 문구와 재현 순서(민감정보 제거):

# ☕ Backend 개발 환경 설정

이 가이드는 Meeting Automation 모노레포의 `backend/` Spring Boot API를 로컬에서 개발하고 실행하는 절차를 설명한다. 제품 요구사항의 기준은 [PRD](../product/PRD.md), 시스템 경계는 [Architecture](../product/architecture.md), REST 계약은 [API Specification](../product/api-spec.md), 연동 설정은 [Integrations](../product/integrations.md)을 따른다.

> **기준 환경:** Eclipse Temurin JDK 25, Spring Boot 4.1.x, Gradle Wrapper 9.x (JDK 25 실행 시 최소 9.1), IntelliJ IDEA
> **주 개발 OS:** Windows 10/11 + PowerShell

## 환경 개요

```text
meeting-automation/
├─ frontend/
├─ backend/          # 이 문서에서 설정하는 Java API
│  ├─ build.gradle
│  ├─ settings.gradle
│  └─ gradlew.bat
└─ docs/
```

Gradle Wrapper를 통해 저장소 버전을 사용한다. Backend만 외부 Provider에 연결한다. Document 연결은 화면 위저드로 등록한 암호화 전역 설정을 사용하고 나머지 Provider Secret과 설정 암호화 키는 로컬 환경/Render Secret에 둔다.

## 사전 준비

- Git 저장소를 로컬에 clone한다.
- JDK 25를 설치한다.
- IntelliJ IDEA를 설치한다. Java 25 기능 지원 버전을 사용한다.
- 저장소의 `backend/gradlew.bat`가 생성되어 있는지 확인한다. 없다면 TASK-001의 Backend scaffold가 아직 완료되지 않은 것이므로 임의로 빌드 파일을 만들지 않는다.

## JDK 설치

Windows에서는 [Eclipse Temurin 설치 안내](https://adoptium.net/installation/)의 Winget 명령을 사용할 수 있다.

```powershell
winget install EclipseAdoptium.Temurin.25.JDK
```

또는 Adoptium의 공식 사이트에서 Windows MSI installer를 받아 설치한다. 설치 뒤 새 PowerShell 창을 연다.

```powershell
java -version
javac -version
where.exe java
```

`java -version`, `javac -version` 모두 `25`로 시작해야 한다. 여러 JDK가 설치되어 있으면 `where.exe java`에 표시된 첫 경로가 원하는 JDK인지 확인한다.

### `JAVA_HOME` 설정

Windows 검색에서 **시스템 환경 변수 편집 → 환경 변수**를 열고 사용자 변수에 다음을 설정한다.

- `JAVA_HOME`: Temurin JDK 25 설치 디렉터리. `bin` 하위 폴더가 아닌 JDK 루트 경로.
- `Path`: `%JAVA_HOME%\bin`을 추가하고 오래된 JDK 경로보다 앞에 둔다.

새 PowerShell에서 다시 확인한다.

```powershell
$env:JAVA_HOME
java -version
javac -version
```

macOS/Linux는 [Adoptium 설치 안내](https://adoptium.net/installation/)에서 플랫폼별 설치 명령을 따른다. 사용하는 shell profile에 `JAVA_HOME`과 `$JAVA_HOME/bin`을 설정하고 새 terminal에서 확인한다.

## Gradle Wrapper 확인

저장소 루트에서 Backend 폴더로 이동한다.

```powershell
Set-Location backend
```

Windows PowerShell에서는 `gradlew.bat`, macOS/Linux에서는 `./gradlew`를 실행한다. Windows 명령:

```powershell
.\gradlew.bat --version
```

출력에서 Gradle이 저장소 wrapper가 지정한 9.x 버전이고 JVM이 Java 25인지 확인한다. Java 25로 Gradle을 실행하는 기준의 최소 버전은 9.1이다. Spring Boot 4.1.x는 Gradle 8.14 이상 또는 9.x를 지원한다.

Wrapper가 지원하는 작업:

```powershell
.\gradlew.bat tasks
.\gradlew.bat build
.\gradlew.bat test
```

`gradlew.bat` 또는 `gradle/wrapper/gradle-wrapper.properties`가 없다면 TASK-001 scaffold를 확인한다. 전역 `gradle` 명령이나 다른 wrapper 버전으로 우회하지 않는다.

## 환경변수

Backend는 AI/Email/Slack과 저장 기반 설정을 환경변수로 읽는다. Document Provider·Root·인증 정보는 전역 draft API에 입력한다. TASK-022.01은 draft 저장과 읽기 테스트까지 제공하며 초기 구조 생성·활성화와 화면 위저드는 후속 작업에서 연결한다. 로컬 개발에서는 `backend/.env.local` 파일을 사용할 수 있도록 애플리케이션 실행 설정을 구성하거나 IntelliJ Run Configuration에 값을 입력한다. Spring Boot가 `.env` 파일을 자동 로딩한다고 가정하지 않는다. 프로젝트가 로컬 dotenv loader를 포함하지 않으면 Run Configuration의 Environment variables에 직접 설정한다.

비밀이 아닌 설정의 예:

```text
APP_COMPANY_ID=sfood
APP_COMPANY_NAME=SFOOD
APP_TIMEZONE=Asia/Seoul
DOCUMENT_SETTINGS_DIR=<git-outside-persistent-directory>
TRANSCRIPTION_MODEL=<configured-model-id>
MINUTES_MODEL=<configured-model-id>
EMAIL_PROVIDER=GMAIL_API
EMAIL_OAUTH_CLIENT_ID=<google-oauth-client-id>
EMAIL_OAUTH_CLIENT_SECRET=<secret>
EMAIL_OAUTH_REFRESH_TOKEN=<secret>
EMAIL_SENDER_ADDRESS=<authorized-gmail-address>
NOTIFICATION_PROVIDER=SLACK
ALLOWED_ORIGINS=http://localhost:5173
TEMP_AUDIO_DIR=<absolute-local-temp-directory>
```

Secret 설정 이름의 예:

```text
DOCUMENT_SETTINGS_ENCRYPTION_KEY
OPENAI_API_KEY
EMAIL_*
SLACK_MEETING_WEBHOOK_URL
SLACK_ADMIN_WEBHOOK_URL
```

로컬 Secret은 개인 개발 환경에만 보관한다. 실제 값을 Git에 commit하거나 문서, 로그, IDE 공유 설정, 화면 캡처에 넣지 않는다. 저장소의 `.gitignore`가 `.env.local` 및 secret 파일을 제외하는지 확인한다. 다른 개발자에게 전달할 설정 예시는 값이 없는 `.env.example`로 제공한다.

Audio 처리에는 임시 디렉터리가 필요하다. `TEMP_AUDIO_DIR`를 OS 임시 경로 아래 개발 전용 디렉터리로 설정하고, 테스트 후 파일이 정리되는지 확인한다. 회사 실회의 녹음을 로컬 개발용으로 사용하지 않는다.

## IntelliJ IDEA 설정

모노레포 루트 `meeting-automation/`을 IntelliJ IDEA에서 연다. `backend/` 하위 프로젝트만 별도 창에서 열지 않아도 된다.

### Gradle 프로젝트 연결

1. `backend/build.gradle` 또는 `backend/build.gradle.kts`를 열고 Gradle 프로젝트로 Load/Link한다.
2. **Settings → Build, Execution, Deployment → Build Tools → Gradle**로 이동한다.
3. **Distribution**은 `gradle-wrapper.properties` 사용을 선택한다.
4. **Gradle JVM**은 JDK 25로 선택한다.
5. **Build and run using** 및 **Run tests using**은 Gradle을 선택해 IDE와 wrapper 결과를 일치시킨다.
6. Gradle sync 후 프로젝트가 정상 인덱싱되는지 확인한다.

### Project SDK와 언어 수준

- **File → Project Structure → Project SDK**를 JDK 25로 설정한다.
- Project language level을 25로 설정한다.
- Module SDK가 Project SDK를 상속하는지 확인한다.
- Preview features는 제품 코드에서 사용하지 않는다. Preview 사용이 필요해지면 별도 결정과 빌드 설정 변경이 먼저 필요하다.

### Run Configuration

Spring Boot Application Run Configuration을 만들거나 Gradle `bootRun` task를 사용한다.

- Working directory: 모노레포의 `backend/`
- JRE: Project SDK / JDK 25
- Environment variables: 로컬에서 필요한 설정만 입력
- Active profiles: 기본 local profile이 프로젝트에 정의된 경우 해당 profile 선택
- VM options: 기본값 유지. Secret을 command line argument로 넘기지 않는다.

Run Configuration 공유 파일을 커밋해야 한다면 Secret 값이 없는 공유 설정만 저장한다. 실제 credential은 개인 환경에서 주입한다.

## 빌드와 로컬 실행

Backend scaffold가 준비되면 `backend/`에서 실행한다.

```powershell
.\gradlew.bat clean build
.\gradlew.bat test
.\gradlew.bat bootRun
```

Spring Boot 기본 로컬 포트는 `8080`으로 맞춘다. 앱 시작 로그에서 실제 listening port와 active profile을 확인한다. Health endpoint가 활성화된 경우:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

Health endpoint 노출 설정이 scaffold에 없다면 PRD의 Actuator 기준을 확인하고 무단으로 운영 환경 노출 범위를 넓히지 않는다. 브라우저 Frontend를 연결할 때 `ALLOWED_ORIGINS`에 `http://localhost:5173`이 포함되어야 한다.

## 실행 결과 점검

- `java -version`과 `javac -version`이 JDK 25를 가리킨다.
- IntelliJ의 Project SDK와 Gradle JVM이 JDK 25다.
- Gradle Wrapper가 JDK 25에서 실행되는 9.1 이상 버전이다.
- `clean build` 및 `test`가 성공한다.
- Spring Boot가 설정된 local profile과 포트로 시작한다.
- `/actuator/health` 응답에 Secret이 포함되지 않는다.
- 임시 Audio가 작업 완료 후 정리된다.

## 자주 발생하는 문제

| 증상 | 확인 방법 |
|---|---|
| `java`가 JDK 25가 아님 | `JAVA_HOME`, `Path`, `where.exe java`, 새 terminal 여부 확인 |
| CLI 빌드와 IntelliJ 빌드가 다름 | IntelliJ Gradle JVM 및 wrapper 설정 확인 |
| Java 25인데 Gradle daemon이 시작되지 않음 | `gradlew --version` 확인, wrapper가 9.1 이상인지 점검 |
| Spring Boot plugin과 Gradle 호환 오류 | Spring Boot 4.1.x 및 wrapper 8.14+/9.x 조합 확인 |
| 환경변수가 null | Run Configuration에 값이 전달되는지 확인. Spring Boot는 임의의 `.env.local`을 자동 읽지 않음 |
| Frontend 요청이 CORS 차단됨 | Backend `ALLOWED_ORIGINS`와 Frontend API URL의 정확한 origin 확인 |
| Actuator endpoint 404 | Actuator 의존성과 노출 설정 확인. Health endpoint가 활성화되어 있는지 확인 |

## 참고 문서

- [Eclipse Temurin 설치 안내](https://adoptium.net/installation/)
- [Spring Boot 4.1 시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html)
- [Gradle Java 호환성 표](https://docs.gradle.org/current/userguide/compatibility.html)
- [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html)
- [IntelliJ IDEA Gradle 설정](https://www.jetbrains.com/help/idea/gradle-settings.html)
- [IntelliJ IDEA Java 지원 버전](https://www.jetbrains.com/help/idea/supported-java-versions.html)

## ⚙️ 문서 연결 위저드 운영 준비

TASK-022 이후 문서 연결은 화면에서 설정한다. Render는 Persistent Disk mount 아래 DOCUMENT_SETTINGS_DIR를 사용하고 로컬은 저장소/Git 밖의 디렉터리를 사용한다. 설정 키는 Backend secret으로 유지한다. Disk는 유료·단일 인스턴스·재배포 중단 제약이 있으며 임시 파일로 대체하지 않는다. 이 문서 변경으로 실제 배포/요금 지출을 실행하지 않는다.

`DOCUMENT_SETTINGS_DIR`는 미리 생성한 절대 경로여야 한다. Git 저장소 아래, 심볼릭 링크 디렉터리, 쓰기 불가능한 디렉터리는 거절한다. `RENDER=true`에서는 root 파일시스템과 다른 실제 mount가 필요하다. Render 임시 파일시스템은 저장 성공으로 처리하지 않는다. 로컬 운영자는 임시 디렉터리가 아닌 지속 보존되는 경로를 지정한다.

`DOCUMENT_SETTINGS_ENCRYPTION_KEY`는 안전하게 생성한 32 bytes를 Base64로 인코딩한 값이다. 키는 상태 디렉터리와 분리한 deployment secret에 저장하고 백업한다. 기존 암호화 파일을 읽으려면 같은 키가 필요하다. 키 누락·손상 snapshot·복호화 실패는 `STORAGE_UNAVAILABLE`이며 기존 파일을 지우거나 빈 설정으로 초기화하지 않는다. 암호화 snapshot `settings.enc`와 키를 별도로 백업한다.

설정 조회는 `GET /api/v1/document-setup`, draft 저장은 `POST /api/v1/document-setup/drafts`, 읽기 테스트는 `POST /api/v1/document-setup/drafts/{draftId}/test`다. mutation은 `Content-Type: application/json`, 허용 `Origin`, `X-Document-Setup-Request: true`, `If-Match: "<version>"`, `Idempotency-Key`를 요구한다. Origin은 `ALLOWED_ORIGINS`의 정확한 값과 일치해야 한다. 성공 응답의 version/ETag를 다음 변경에 사용하며 동일 키·동일 입력 재전송은 재시작 후에도 최초 결과를 재사용한다.

Notion 입력은 `{provider:"NOTION",location:{parentPageId:"<page ID 또는 https://www.notion.so/... URL>"},credentials:{token:"<secret>"}}`다. Confluence 입력은 `{provider:"CONFLUENCE",location:{baseUrl:"https://<site>.atlassian.net",spaceId:"<numeric ID>",parentPageId:"<optional numeric ID>"},credentials:{accountEmail:"<external account email>",apiToken:"<secret>"}}`다. `reuseExistingRootId`는 선택 필드이며 후속 초기화에서 명시적 재사용에 사용한다. draft 변경은 테스트를 무효화하고 마지막 변경 후 24시간이 지나면 다음 설정 조회에서 자격 증명을 제거한다. 읽기 테스트 결과는 10분 유효하며 검사용 페이지를 만들지 않고 `writeCapability=UNVERIFIED`를 반환한다.

legacy `DOCUMENT_PROVIDER`, `DOCUMENT_ROOT_ID`, `NOTION_TOKEN`, `CONFLUENCE_*`는 활성 연결 선택에 사용하지 않는다. draft가 있어도 active가 없으면 업무 API를 차단한다. Provider 네트워크 장애는 저장된 active를 삭제하지 않는다. 인증 HTTP client는 HTTPS public Provider host만 허용하고 redirect를 따르지 않으며 연결 10초·응답 20초 제한을 적용한다.

앱 계정은 없으며 신뢰된 사내 배포 접근 경계를 유지한다. Notion 부모 페이지 접근 부여, Confluence space와 페이지 생성 권한은 [문서 연결 설계](../product/document-setup.md)의 가이드를 따른다. 기존 env 기반 연결은 화면에 다시 입력하고 기존 루트 재사용을 선택한다. 초기 완료·양방향 변경·재배포 복원은 synthetic 테스트 문서로 확인한다.

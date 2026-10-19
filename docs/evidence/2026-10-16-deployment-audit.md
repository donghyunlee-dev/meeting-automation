# 2026-10-16 운영 배포 점검 기록

이 문서는 2026-10-16 KST에 관측한 상태다. 변할 수 있는 서비스 상태를 제품 설계 문서의 영구 사실로 사용하지 않는다. 운영 설정 기준은 [운영 테스트 설정](../setup/production-test.md)에 있다. 비밀값의 내용은 조회하거나 기록하지 않았다.

| 대상 | 관측 결과 | 의미 |
|---|---|---|
| Vercel `meeting-automation` | Git 연결은 `donghyunlee-dev/meeting-automation`, production branch는 `master`. Root Directory `frontend`, Vite, Node 24.x, `npm ci`, `npm run build`, `dist`. Production 배포가 `READY`이고 최근 배포 source는 CLI. | 설계 문서의 “배포 없음·Git 연결 없음” 진술은 현재 사실이 아니다. 최근 CLI 배포가 Git commit과 같은 소스인지는 이 결과만으로 확인되지 않는다. |
| Vercel 환경 변수 이름 | `VITE_API_BASE_URL`, `BLOB_READ_WRITE_TOKEN`, `RENDER_AUDIO_GRANT_SECRET`, `RENDER_AUDIO_STORAGE_SECRET`이 Production/Preview/Development 대상으로 등록됨. | 이름과 대상만 확인했다. 값·Blob store 연결·양쪽 서비스의 secret 일치 여부는 확인하지 않았다. |
| 공개 화면 | `https://meeting-automation-one.vercel.app/`는 HTTP 200. 배포된 브라우저 파일에 Render API URL이 포함되고 `localhost:8080`은 없음. `/settings`와 `/meetings/new` 직접 요청은 HTTP 404. | API 기본 주소는 운영 URL로 빌드됐으나 SPA 경로 재작성이 필요하다. |
| Render | `https://meeting-automation-4419.onrender.com/actuator/health`는 기동 뒤 HTTP 200, `UP`. 최초 요청은 45초 안에 응답하지 않았음. | Java 프로세스가 기동됐다는 뜻이며 업무 연동의 준비 상태를 보장하지 않는다. |
| Render 공개 설정 API | `/api/v1/app-config`: `setup.status=STORAGE_UNAVAILABLE`, `canCreateMeeting=false`, `document.configured=false`, `email.configured=false`. `/api/v1/integrations/health`: `ai.configured=false`, `email.configured=false`. | 현재 배포에서 회의 생성·AI 처리·메일 발송을 진행할 수 없다. 어떤 Render 변수나 디스크가 빠졌는지는 공개 API만으로 특정할 수 없다. |
| Render CORS | 운영 Vercel Origin의 `app-config` 요청과 오디오 `PUT` preflight에 허용 Origin 및 오디오 헤더가 반환됨. | 현재 배포의 해당 Origin은 허용된다. 현재 저장소의 CORS 설정은 `X-Audio-SHA256`, `X-Audio-Byte-Length`가 목록에서 빠져 있어 동일 코드로 배포할 때 다시 확인해야 한다. |
| 로컬 작업 트리와 원격 추적 브랜치 | 로컬 `master`(`d86998a`)는 `origin/master`(`a7e8465`)보다 네 커밋 뒤에 있음. `origin/master`의 `95a8669`에 Blob 함수·Java 어댑터와 새 환경 변수가 추가됨. 로컬 작업 트리에는 S3 코드와 미커밋 변경이 남아 있음. | 현재 디렉터리의 오래된 S3 코드만으로 운영 배포를 판단하면 잘못된 결론이 나온다. 미커밋 변경을 보존하면서 최신 커밋과 통합해야 한다. |
| 코드와 배포본 | 공개 배포에는 `/api/audio-storage` 함수와 Blob 업로드 경로가 있음. `origin/master`에도 해당 함수·`@vercel/blob`·Java Blob 어댑터가 있음. 최근 Vercel 배포 source는 CLI이며 해당 Git SHA는 배포 정보로 확인되지 않음. | 공개 배포본이 원격 추적 브랜치의 Blob 구현과 일치하는 정황은 있으나 완전한 동일성은 아직 검증되지 않았다. |

설정값을 바꾼 뒤에는 각 서비스의 새 배포에서 공개 API 상태와 브라우저 흐름을 다시 확인한다. 이 기록의 관측값을 최신 상태로 가정하지 않는다.

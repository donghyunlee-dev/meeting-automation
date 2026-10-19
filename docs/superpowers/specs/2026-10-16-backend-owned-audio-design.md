# Backend 소유 Audio 업로드·처리 설계

> **상태:** 코드 적용 및 로컬 자동 검증 완료, Render 배포 확인은 미실시
> **기준일:** 2026-10-16
> **목표:** 브라우저는 녹음과 화면에 집중하고, Spring Boot Backend가 오디오 업로드 수신·검증·조립·AI 처리를 담당한다.
> **적용 상태:** Frontend는 Backend로 직접 chunk를 업로드하며, Backend는 `TEMP_AUDIO_DIR`에 임시 저장·조립·만료 정리한다. 문서 연결 설정은 현재 브라우저의 localStorage에 저장하고, Render Backend 재시작 후에는 같은 브라우저가 연결을 다시 구성한다. Render 배포와 실제 외부 서비스 연결은 이 변경에서 수행하지 않았다.

## 확정된 방향

- Vercel은 정적 Frontend 배포에 사용한다.
- Vercel Blob과 오디오 업로드용 Vercel Functions를 사용하는 계획은 취소한다.
- 브라우저는 기존처럼 녹음하고 오디오를 여러 조각으로 나눠 Backend API에 보낸다. 오디오 bytes의 저장 위치와 처리 lifecycle은 Backend가 소유한다.
- Backend는 순서·크기·MIME·checksum을 검증하고, 업로드 완료 뒤 조각을 조립해 전사와 회의록 생성을 실행한다. 임시 파일은 작업 완료/만료 시 정리한다.
- 운영 테스트 우선 목표는 짧은 회의를 처음부터 끝까지 처리하는 것이다. 장시간 녹음 지원 여부는 용량·시간 한도를 확정한 뒤 결정한다.

## 제안하는 요청 흐름

1. Frontend가 Backend에서 회의 Session과 업로드 정책을 받는다.
2. 브라우저는 녹음 중 15초 단위 Audio chunk를 IndexedDB에 임시 보관하고 Backend의 chunk API로 업로드한다.
3. Backend는 각 chunk를 제한된 크기로 스트리밍 수신하고 검증 후 `TEMP_AUDIO_DIR` 아래 임시 파일로 게시한다. 동일 chunk 재시도는 멱등 처리한다.
4. Frontend는 Backend의 수신 확인을 받은 chunk만 브라우저 대기열에서 제거한다. 네트워크 단절이면 대기열을 보존하고 재시도한다.
5. 녹음 종료 후 Backend가 누락 chunk를 확인하고 조립한 뒤 AI 처리한다. 성공/실패/만료 상태에 맞춰 Backend가 임시 Audio를 정리한다.

Frontend는 화면만 담당하지는 않는다. 브라우저 녹음 API를 제어하고 네트워크 재시도를 위해 chunk를 잠시 보관해야 한다. 서비스 규칙, 파일 검증·조립·AI·메일 처리는 Backend가 담당한다.

## Render 무료 플랜에 대한 판단

Render Free는 짧은 테스트 파일을 받아 즉시 처리하는 데 쓸 수 있지만, 운영 데이터의 영속 저장소로 사용할 수 없다. 무료 웹 서비스는 로컬 파일시스템이 ephemeral이며 재시작·재배포·절전 때 바뀐 파일이 사라진다. Persistent Disk도 지원하지 않는다. 따라서 `TEMP_AUDIO_DIR`는 처리 중 임시 공간으로만 사용할 수 있고, 재시작 복구를 보장하지 않는다.

무료 서비스는 15분간 inbound traffic이 없으면 절전한다. 15초 chunk 업로드가 계속 성공하는 동안에는 Backend에 요청이 들어오므로 녹음 세션 중 절전을 막는 효과가 있다. 별도 heartbeat는 chunk 사이 업로드 정체가 길어질 수 있는 경우의 보조 수단으로만 검토한다. 백그라운드 모바일 브라우저의 타이머는 지연될 수 있고, heartbeat는 Render의 임의 재시작이나 세션 메모리 유실을 방지하지 못한다.

Render Backend의 문서 연결 설정은 프로세스 메모리에만 유지한다. 브라우저 localStorage가 연결 입력과 생성된 루트 ID를 보관하므로, 같은 브라우저가 재접속하면 설정 작업을 자동 복구한다. localStorage는 브라우저와 origin마다 분리되므로 다른 기기에서는 문서 서비스를 다시 연결해야 한다. Backend의 재시작 뒤에도 연결이 자동 복구되려면 사용자가 해당 브라우저로 접속해야 한다. Session과 오디오 임시 파일은 Render 재시작 후 복구되지 않을 수 있다.

Render Free는 월별 사용량, 512 MB RAM, 0.1 CPU의 제한도 있으므로 실제 전사/조립을 견디는지는 짧은 운영 측정으로 확인한다. 사용자가 “운영 테스트”에서 재시작 후 이어하기를 기대한다면 Free 로컬 임시 파일 방식은 적합하지 않다.

## 용량과 실패 정책

- 현재 Backend 전사 입력의 기본 최대 크기는 25 MiB이고 UI 녹음 시간은 최대 60분이다. 두 제한은 서로 맞지 않으므로 테스트용 최대 녹음 길이와 전사 전략을 먼저 정해야 한다.
- 업로드 중 Backend 재시작 시 Render Free의 임시 파일과 메모리 Session은 복구되지 않을 수 있다. 브라우저 IndexedDB 원본을 보존해 재업로드 가능한지, Session 자체를 영속화할지 정해야 한다.
- 실패 후 Audio 재처리/다운로드 요구가 있으면 ephemeral 파일만으로 충족되지 않는다. 보존 기간과 저장소 선택이 별도 필요하다.
- API는 요청 크기 상한, 인증/접근 통제, checksum·idempotency, 업로드 제한 시간과 동시 처리 수를 명시해야 한다.

## Vercel과 Render의 범위

| 서비스 | 현재 문서 정리 방향 | 남은 검토 |
|---|---|---|
| Vercel | Frontend 정적 배포. Blob/API/오디오 자격 증명 설정 제거 | SPA fallback, Frontend의 Backend URL, Preview/Production origin |
| Render | 우선 후보 Backend. 오디오 조각을 Backend API가 직접 수신 | Free 설정 저장, 재시작 복구, 메모리/CPU, 업로드 상한 측정 |

Render의 적합성이 확인되지 않으면 Backend를 옮긴다. Backend 소스의 업로드·조립·처리 경계는 호스팅 이전에도 유지하고 저장소/런타임 Adapter를 환경에 맞게 선택한다.

## 적용 이후 남은 검증

1. Render 배포 환경에서 `RENDER=true`일 때 문서 설정 API가 `UNCONFIGURED`로 정상 시작하는지 확인한다.
2. 실제 Render에 짧은 녹음을 올려 업로드 크기 한도, 메모리, CPU, 처리 시간을 확인한다.
3. Backend 재시작 중 진행 회의의 복구가 필요하다면 Session 영속화와 Audio 재업로드 정책을 별도로 설계한다.
4. 다른 기기에서는 localStorage가 공유되지 않으므로 해당 기기에서 서비스 연결을 다시 설정해야 한다.

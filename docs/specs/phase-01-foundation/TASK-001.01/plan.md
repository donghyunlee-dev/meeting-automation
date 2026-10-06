# 저장소 기본 구조와 제외 규칙 계획

## 목표

후속 Frontend 및 Backend 설정 작업이 독립적으로 진행될 수 있도록 최소한의 추적 가능한 Monorepo 골격을 만들고, 생성 산출물과 개발자 로컬 환경 파일이 저장소에 들어오지 않게 한다.

- GitHub Issue: [#1](https://github.com/donghyunlee-dev/meeting-automation/issues/1)

## 의존성과 인접 작업

- 선행 작업: 없음.
- 후속 작업: `TASK-001.02` Frontend 도구 체계, `TASK-001.03` Backend 도구 체계.
- 파일을 추가하기 전에 PRD의 후속 작업 계약을 확인한다. 이 작업은 루트 구조, 문서 진입점, 제외 정책을 소유한다. 개발 workflow상 PRD에 개발 tracker가 없으면 tracker와 다음 개발 커서도 추가하며 기존 제품·설계 내용은 바꾸지 않는다.

## 생성할 파일

| 파일 | 책임 |
|---|---|
| `.gitignore` | 지정된 FE/BE 산출물과 로컬 환경 파일만 제외하고 환경 예제 파일은 허용한다. |
| `README.md` | 기존 파일을 프로젝트 개요, 최상위 영역의 책임 경계, PRD/설정 가이드 링크가 있도록 갱신한다. |
| `docs/product/PRD.md` | 기존 설계 표·설계 커서를 보존하면서 개발 상태 tracker와 다음 개발 커서만 추가한다. |
| `frontend/.gitkeep` | `TASK-001.02`가 실제 파일을 추가하기 전까지 Frontend 루트를 Git에 남긴다. |
| `backend/.gitkeep` | `TASK-001.03`이 실제 파일을 추가하기 전까지 Backend 루트를 Git에 남긴다. |
| `docs/ADR/.gitkeep` | PRD에서 정한 의사결정 기록 위치를 만든다. |
| `docs/evidence/.gitkeep` | 제외 규칙 없이 검증 증거 위치를 만든다. |

그 외 기존 제품/설정 파일은 수정하지 않는다. `src/`, 앱 매니페스트, 빌드 파일은 추가하지 않는다. 후속 도구 체계 작업에서 소유하는 경로다.

## 구현 순서

- 먼저 `test.md`의 구조 및 제외 규칙 검사를 현재 저장소에서 실행해 기대 파일이나 규칙이 없어서 실패하는지 확인한다. 관련 없는 기존 경로를 실패로 간주하지 않는다.
- `spec.md`에 정한 `.gitignore` 패턴과 예제 허용 규칙을 추가한다.
- 필수 개요와 링크를 담은 루트 README를 추가한다. 제품 동작과 설정 지침은 원본 문서에 두고 중복 작성하지 않는다.
- 지정된 빈 디렉터리만 필요한 경우 표식 파일로 만든다.
- 모든 구조/제외 규칙 검사를 다시 실행하고 `git status --short --ignored`로 생성/로컬 경로가 제외되고 원본 문서가 보이는지 확인한다.
- PRD 개발 tracker에 각 leaf TASK 한 행이 있고, 개발 상태/단계/Issue·PR/검증 증거/병합 SHA/완료 시각을 기록하는지 확인한다. 기존 설계 표와 설계 커서는 변경되지 않아야 한다.

## 검증 방법

아직 애플리케이션 런타임이 없는 저장소 설정 작업이다. PowerShell 경로 확인, `git check-ignore --no-index`, README 링크 존재 여부, Git 상태를 확인한다. 프로젝트가 생기기 전에 npm 또는 Gradle 명령을 임의로 만들지 않는다.

## 완료 증거

명령과 결과를 `docs/evidence/TASK-001.01.md`에 기록한다. 증거에는 검사한 제외 경로, 허용 경로, 저장소 루트, 앱 도구 체계 및 비밀 값이 추가되지 않았음을 포함한다.

## 관련 원본

- [PRD](../../../product/PRD.md), 3.1, 3.2, 3.3, 6.3, 23.1절 및 `TASK-001.01`
- [Frontend 설정](../../../setup/frontend-setup.md), 로컬 `.env` 및 제외 규칙 지침
- [Backend 설정](../../../setup/backend-setup.md), 로컬 비밀 값과 환경 예제 지침
- [아키텍처](../../../product/architecture.md), FE/BE 빌드 및 배포 경계

# 저장소 기본 구조와 제외 규칙 테스트 계획

## 테스트 목표

저장소 구조, 제외 범위, README 링크, 앱 도구 체계가 추가되지 않았는지를 확인한다. 이 작업에는 FE/BE 런타임이 없으므로 npm이나 Gradle 대신 Git과 PowerShell을 사용한다.

- GitHub Issue: [#1](https://github.com/donghyunlee-dev/meeting-automation/issues/1)

## 자동 명령 검사

저장소 루트의 PowerShell에서 실행한다.

### 저장소 구조

```powershell
$required = @('.gitignore', 'README.md', 'frontend/.gitkeep', 'backend/.gitkeep', 'docs/ADR/.gitkeep', 'docs/evidence/.gitkeep')
$missing = $required | Where-Object { -not (Test-Path -LiteralPath $_) }
if ($missing) { throw "Missing required paths: $($missing -join ', ')" }
```

기대 결과: 명령이 성공하고 `$missing`이 비어 있다.

### 제외되어야 하는 경로

```powershell
$ignored = @(
  'frontend/node_modules/probe/package.json',
  'frontend/dist/index.html',
  'frontend/.vite/deps/probe.json',
  'backend/.gradle/caches/probe.bin',
  'backend/build/classes/probe.class',
  '.env.local',
  'frontend/.env.development.local',
  'backend/.env.local'
)
foreach ($path in $ignored) {
  git check-ignore --no-index --quiet $path
  if ($LASTEXITCODE -ne 0) { throw "Expected ignored path: $path" }
}
```

기대 결과: 모든 경로가 `.gitignore` 규칙에 매칭된다. 증거를 남길 때 각 경로에 대해 `git check-ignore --no-index --verbose <path>`를 실행하고 일치 규칙을 기록한다.

### 추적 가능해야 하는 경로

```powershell
$trackable = @('.env.example', '.env.development.example', 'frontend/.env.example', 'backend/.env.example', 'docs/evidence/verification.md')
foreach ($path in $trackable) {
  git check-ignore --no-index --quiet $path
  if ($LASTEXITCODE -eq 0) { throw "Expected trackable path, but it is ignored: $path" }
}
```

기대 결과: 어떤 경로도 `.gitignore`와 매칭되지 않는다. 존재하지 않거나 합성한 경로여도 `--no-index`는 인덱스에 파일이 없어도 제외 규칙을 검사한다.

### README 링크와 작업 경계

```powershell
$readme = Get-Content README.md -Raw
$links = @('docs/product/PRD.md', 'docs/setup/frontend-setup.md', 'docs/setup/backend-setup.md')
foreach ($path in $links) {
  if (-not (Test-Path -LiteralPath $path)) { throw "README target does not exist: $path" }
  if (-not $readme.Contains($path)) { throw "README does not link to: $path" }
}
if (Test-Path 'frontend/package.json') { throw 'Frontend toolchain belongs to TASK-001.02' }
if ((Test-Path 'backend/build.gradle') -or (Test-Path 'backend/build.gradle.kts')) { throw 'Backend toolchain belongs to TASK-001.03' }
```

기대 결과: 링크 세 개가 모두 존재하고 후속 작업 소유인 도구 체계를 이 작업에서 만들지 않았다.

## 수동 QA

- `.gitignore`의 순서를 확인해 환경 예제 예외가 환경 파일 제외 규칙 뒤에 있는지 확인한다.
- README가 PRD 내용을 복사하지 않고 Frontend, Backend, docs의 역할을 분리해 설명하는지 확인한다.
- `git status --short --ignored`를 확인한다. `docs/product/`, `docs/setup/`의 원본 문서는 제외되지 않아야 한다.
- 변경 내용을 확인해 인증 정보나 환경 값이 들어가지 않았는지 점검한다. 테스트 출력에 비밀 값을 표시하지 않는다.
- PRD에 leaf TASK별 개발 tracker 행이 정확히 하나씩 있는지 확인한다. 현재 Issue 상태와 일치하는지, 기존 `다음 설계 대상 커서` 및 설계 상태 표가 보존됐는지 확인한다.

## 증거

명령 출력과 최종 통과/실패 요약을 `docs/evidence/TASK-001.01.md`에 저장한다. 이 검증은 저장소 설정 확인이며 모바일/회의실 QA나 npm/Gradle 빌드 테스트가 아니다.

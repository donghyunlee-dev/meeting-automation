# 구현 계획

> 📌 v1.9.0 변경 계약: Document credential은 password 입력 중 메모리와 요청에서만 허용하고 저장된 credential의 GET/log/bundle/웹 영속 저장 노출을 금지한다. 화면 입력 자체를 보안 위반으로 처리하지 않는다. 설정 snapshot 암호화와 Origin 검증을 회귀에 포함한다. 상세 기준은 [문서 연결·이전 설계](../../../product/document-setup.md)다. 아래의 과거 기준과 충돌하면 이 변경 계약을 우선 적용한다.

## 의존성

- TASK-001.05 env sample checker/local env boundary (#5)
- TASK-016.01 public error/log mapping (#57)
- API-001/API-019 및 Provider integration redaction contracts
- PRD v1.7.0 (2026-10-05), DEC-017, FR-030, NFR-004~006
- GitHub Issue [#60](https://github.com/donghyunlee-dev/meeting-automation/issues/60)

## 변경 대상

- Existing secret-boundary checker: 제한된 sample 검사 확장 또는 별도 focused checks.
- Frontend build config/output test: public env allowlist 및 synthetic canary absence.
- Backend serialization tests: AppConfig/Health/error/incident DTO allowlist.
- Logging filters/configuration: Provider client/errors 및 sensitive domain content masking.
- Synthetic fixtures: credential, Authorization, webhook, provider error body, Transcript/Minutes/email.
- Verification documentation: safe command, scan scope, non-sensitive evidence output.

## 구현 순서

기존 sample checker는 로컬 환경 샘플만 검사하므로 별도 build/API/log checks를 추가하고 실제 runtime credential을 테스트 입력으로 쓰지 않는다.

1. Scanner scope tests를 작성해 포함 대상과 제외 경로를 고정한다.
2. Normal/violation sample fixture와 synthetic canary tests를 작성한다. 실패 출력은 값 없이 file/rule만 표시한다.
3. FE build artifact canary scan 및 public environment allowlist 검증을 연결한다.
4. Backend DTO allowlist/error serializer와 Provider/processing log capture tests를 연결한다.
5. HTTPS deployment config/source presence를 확인하고 local HTTP fixture와 구분한다.
6. security regression suite/build 및 non-sensitive evidence를 수행한다.

## 검증

정확한 npm/Gradle 명령은 저장소 구현을 확인해 사용한다. 실제 credential은 terminal, fixture, build argument, CI log에 넣지 않는다. 사례는 [검증 계획](./test.md)을 따른다.

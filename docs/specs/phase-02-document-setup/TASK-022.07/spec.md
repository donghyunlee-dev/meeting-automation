> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# 최초 문서 연결 UI 모바일 정돈과 Provider 안내 개선

> PRD v1.9.0 · 2026-10-15 · TASK-022.07 · 영역 FE
> GitHub Issue: [#161](https://github.com/donghyunlee-dev/meeting-automation/issues/161)

## 목표

`/setup/document` 최초 연결 화면을 작은 모바일 화면에서 읽고 조작하기 쉽게 정돈하고, Notion 또는 Confluence Cloud를 처음 연결하는 사용자가 준비물과 접근 권한을 단계별로 확인할 수 있게 한다.

## 계약

- PRD `SCR-013` 및 [문서 연결·이전 설계](../../../product/document-setup.md)의 최초 설정 흐름과 기존 연결 API 계약을 유지한다.
- Provider 선택/변경, 연결 테스트, 이후 구조 확인·완료 흐름을 유지하고 다른 Provider로 바꿀 때 기존 입력을 지우는 동작도 유지한다.
- Notion 안내는 내부 연결 token 발급, 대상 부모 페이지에 연결 추가, 읽기·페이지 생성·갱신 접근 권한 확인을 구분해서 설명한다.
- Confluence 안내는 Atlassian 운영 계정과 API token, 사이트 URL 및 Space ID 준비, 대상 공간의 읽기·페이지 생성·갱신 권한 확인을 설명한다.
- 연결 테스트가 검사용 페이지를 만들지 않고, 입력한 비밀정보가 브라우저 저장소에 남지 않는 현재 동작을 정확히 알린다.
- 한국어 문장은 단어 단위로 자연스럽게 줄바꿈한다. 긴 URL/ID 같은 식별자는 화면을 밀어내지 않도록 별도 처리한다.
- 360px 화면에서 공급자 전환/주요 버튼 라벨이 잘리거나 어색하게 두 줄로 갈라지지 않고, 고정 하단 메뉴가 입력 또는 마지막 동작을 가리지 않도록 한다.
- 색상과 컴포넌트는 기존 `Minimal & Clean` 시각 체계를 따른다.

## 범위 밖

Backend/API 계약, 인증 방식, 비밀정보 보존 정책, 회의/참석자 기능 및 다른 설정 화면의 재설계는 변경하지 않는다.

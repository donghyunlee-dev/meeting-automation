# Meeting Automation

## 📝 프로젝트 소개

사내 오프라인 회의를 녹음하고, 변환된 내용을 검토해 회의록으로 저장·공유하는 서비스입니다. 제품 범위와 확정 결정은 [PRD](docs/product/PRD.md)에서 관리합니다.

## 🧩 저장소 구성

- `frontend/`: React 기반 모바일 우선 웹 애플리케이션
- `backend/`: Spring Boot REST API와 외부 연동
- `docs/`: 제품 요구사항, 설계, 설정 및 검증 증거

Frontend와 Backend는 독립적인 빌드·배포 단위입니다. 설정은 [Frontend 개발 환경](docs/setup/frontend-setup.md)과 [Backend 개발 환경](docs/setup/backend-setup.md)을 참고하세요.

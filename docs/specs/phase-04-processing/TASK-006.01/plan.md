# Audio 조립 구현 계획

## 문서 기준 및 의존성

- PRD v1.7.0 (2026-10-05): `DEC-020`, `FR-007`, `NFR-014`, `API-009`, `TASK-006.01`
- 선행 TASK-005.08 Issue [#26](https://github.com/donghyunlee-dev/meeting-automation/issues/26): API-009 processing handoff
- 직접 연계 TASK-005.04 Issue [#22](https://github.com/donghyunlee-dev/meeting-automation/issues/22): Session/sequence Chunk temp repository
- 본 TASK Issue [#27](https://github.com/donghyunlee-dev/meeting-automation/issues/27)
- 후속 TASK-006.02: assembled Audio를 STT adapter input으로 사용
- 후속 TASK-017.02: terminal processing 경로 임시 Audio 보유/삭제

## 변경 경계

- Backend application `AudioAssemblyUseCase`: job 입력과 sequence 검증 및 조립 lifecycle orchestration
- Audio temp storage port: ordered metadata/stream 접근, atomic output publish, source cleanup
- Filesystem adapter: `TEMP_AUDIO_DIR` 안전 경로, streaming copy, atomic move, cleanup
- Job output metadata: assembled reference/MIME/size/checksum 및 다음 단계 handoff
- Controller/API-009, AI provider/STT 계약은 변경하지 않는다.

## 구현 순서

1. TASK-005.04 저장 metadata/repository와 API-009 job queue/`TEMP_AUDIO_DIR` 설정을 확인한다. 결과: source access 및 output reference 계약이 확정된다.
2. sequence integrity, per-chunk checksum/MIME, cleanup/atomic publish retry 테스트를 먼저 작성한다. 결과: 성공/실패 filesystem 결과가 고정된다.
3. application input validation과 storage port를 구현한다. 결과: 파일 system은 domain/application 밖에 유지된다.
4. chunk를 순서대로 bounded stream으로 복사하고 rolling length/hash 검증을 구현한다. 결과: 메모리 사용이 chunk 단위다.
5. part 파일 atomic publish, 같은 job 재사용, 실패 partial cleanup을 구현한다. 결과: 후속 STT가 완성 파일만 본다.
6. 성공 뒤 source Chunk cleanup과 terminal output lifecycle handoff, 구조화 로그를 구현한다. 결과: DEC-020 보존 경계가 지켜진다.
7. component/filesystem tests와 Backend 전체 테스트/build를 실행한다. 결과: 테스트 및 cleanup evidence를 기록한다.

## 검증 명령

Backend root에서 `./gradlew test`, `./gradlew clean build`를 실행한다. Filesystem adapter는 임시 root test fixture를 사용하고 절대 경로를 로그 assertion으로 검사한다.

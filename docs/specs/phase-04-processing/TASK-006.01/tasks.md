> **기준 변경 안내 (2026-10-16):** 이 TASK 설계는 이전 PRD 기준의 이력이며 현재 구현 지시로 사용하지 않습니다. 현재 제품 흐름은 [최초 연결·자동 발송 기준](../../superpowers/specs/2026-10-16-first-run-and-autodelivery-design.md)과 [PRD v2.1.0](../../product/PRD.md)을 따릅니다. 구현 전 새 TASK 설계가 게시될 때까지 대기하세요.

# Audio 조립 작업 항목

1. API-007 Chunk metadata/storage adapter, API-009 job data, `TEMP_AUDIO_DIR` 설정을 확인한다. 완료 증거: 입력 계약과 fixture가 고정된다.
2. assembly result/port와 job output reference를 정의한다. 완료 증거: 후속 STT는 임시 경로의 존재 여부를 repository 밖에서 직접 조립하지 않는다.
3. success/order/missing/extra/checksum/MIME/atomicity/cleanup retry 테스트를 먼저 작성해 실패를 확인한다.
4. 예상 sequence를 검증하고 chunk 단위 streaming append 및 rolling checksum/size를 구현한다. 완료 증거: 전체 오디오를 메모리에 적재하지 않는다.
5. `.part` 생성, 성공 검증 뒤 atomic publish, 같은 job output 재사용을 구현한다. 완료 증거: partial file은 consumer에 보이지 않는다.
6. 실패 시 partial output 제거/Chunk 보존, 성공 시 source Chunk 제거를 구현한다. 완료 증거: 재시도 가능한 실패 입력과 STT가 사용할 output이 각각 보존된다.
7. NFR-014 구조화 trace/session logging과 민감 경로/Audio 비노출을 확인한다. 완료 증거: 로그 assertion이 통과한다.
8. 후속 STT/cleanup handoff contract를 확인하고 문서화된 관련 Backend 테스트만 실행한다. Windows PowerShell에서 `backend/`를 기준으로 `.\gradlew.bat test --tests com.meetingautomation.api.processing.AudioAssemblyUseCaseTests --tests com.meetingautomation.api.audio.AudioAssemblyFileStoreTests --tests com.meetingautomation.api.audio.AudioChunkFileStoreTests`를 실행하며 전체 Backend suite와 `clean build`는 반복하지 않는다. 완료 증거: 정확한 선택 테스트와 결과가 리뷰에 있다.

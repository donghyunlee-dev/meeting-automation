# Audio 조립 작업 항목

1. API-007 Chunk metadata/storage adapter, API-009 job data, `TEMP_AUDIO_DIR` 설정을 확인한다. 완료 증거: 입력 계약과 fixture가 고정된다.
2. assembly result/port와 job output reference를 정의한다. 완료 증거: 후속 STT는 임시 경로의 존재 여부를 repository 밖에서 직접 조립하지 않는다.
3. success/order/missing/extra/checksum/MIME/atomicity/cleanup retry 테스트를 먼저 작성해 실패를 확인한다.
4. 예상 sequence를 검증하고 chunk 단위 streaming append 및 rolling checksum/size를 구현한다. 완료 증거: 전체 오디오를 메모리에 적재하지 않는다.
5. `.part` 생성, 성공 검증 뒤 atomic publish, 같은 job output 재사용을 구현한다. 완료 증거: partial file은 consumer에 보이지 않는다.
6. 실패 시 partial output 제거/Chunk 보존, 성공 시 source Chunk 제거를 구현한다. 완료 증거: 재시도 가능한 실패 입력과 STT가 사용할 output이 각각 보존된다.
7. NFR-014 구조화 trace/session logging과 민감 경로/Audio 비노출을 확인한다. 완료 증거: 로그 assertion이 통과한다.
8. 후속 STT/cleanup handoff contract 및 `./gradlew test`, `./gradlew clean build`를 검증한다. 완료 증거: API/integration test evidence가 리뷰에 있다.

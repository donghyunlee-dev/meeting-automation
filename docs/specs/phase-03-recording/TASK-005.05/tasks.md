# Audio 업로드 현황 작업 항목

1. API-007 저장소 key/atomic publish 계약 및 API-008 DTO를 확인한다. 완료 증거: query가 완료된 Session+sequence 저장물에 한정된다.
2. 응답 DTO, query port/application use case, 표준 오류 mapping을 정의한다. 완료 증거: Controller는 집계 로직과 분리된다.
3. 빈/다중 Chunk/정렬/중복/없는 Session/저장소 실패 테스트를 먼저 작성해 실패를 확인한다.
4. Session 검증과 metadata-only sequence/byte 집계를 구현한다. 완료 증거: binary 본문을 읽지 않고 정확한 count와 sum을 반환한다.
5. API-007 adapter와 통합해 partial write 제외와 numeric ordering을 검증한다. 완료 증거: 실제 업로드 전후 API-008 응답 변화가 예상과 같다.
6. 오류 envelope/비노출, `./gradlew test`, `./gradlew clean build`를 확인한다. 완료 증거: 자동화 결과 및 API-009/Frontend 책임 경계가 리뷰 기록에 있다.

# Minutes 생성 작업 항목

1. EXT-002, Template catalog/version, Session transcript/speaker mapping 및 StructuredMinutes schema를 확인한다. 완료 증거: 모든 source contract가 맞는다.
2. normal/empty/malformed/roster/date/provider failure와 근거 없는 claim 제거 fixture tests를 먼저 작성해 실패를 확인한다.
3. static Template loader와 최소 Participant/mapping/Transcript MinutesCommand mapper를 구현한다. 완료 증거: 불필요한 개인정보/Audio/Secret 입력이 없다.
4. empty Transcript fast path를 구현한다. 완료 증거: 외부 호출 0회, 빈 template-version draft가 생성된다.
5. schema-constrained EXT-002 adapter output을 파싱하고 필수 field validation을 구현한다. 완료 증거: invalid output은 저장되지 않는다.
6. transcript segment 근거를 검증해 미지원 claim만 제거하고, roster/template/date/schema 오류는 안전하게 거부한다. 완료 증거: 지원 claim은 유지되고 미지원 claim만 빠지며 malformed 결과는 저장되지 않는다.
7. Minutes/Transcript와 `REVIEW`/`DRAFT_READY`/100%를 원자 저장한다. 완료 증거: 저장 실패 시 완료 전이가 없다.
8. 개인정보/log redaction 및 변경 코드와 직접 관련된 focused Backend test selectors를 확인한다. 완료 증거: 검증 evidence가 기록되고 전체 suite나 `clean build`를 중복 실행하지 않는다.

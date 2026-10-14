# êµ¬í˜„ ?‘ì—… ëª©ë¡

ê¸°ì?: `TASK-011.02`, PRD v1.7.0 (2026-10-05), `FR-017`, `SCR-007`, `API-003`, `API-010`, `API-015`, Issue [#47](https://github.com/donghyunlee-dev/meeting-automation/issues/47).

## ?¬ì „ ?•ì¸

- [x] TASK-004.03 API-003 participant record and selected Session IDs behavior, TASK-010.02 API-015 body, TASK-011.01 Gmail command/result contract ?•ì¸. ?„ë£Œ ì¦ê±°: DTO/ownership cross-check ê¸°ë¡.
- [x] API-010 Session response mapper/cache and in-memory Session Delivery model ?•ì¸. ?„ë£Œ ì¦ê±°: optional projection lifecycle and version source recorded.
- [x] Existing RecipientDeliveryResult/Delivery retryable error classification and no-email-address logging tests ?•ì¸. ?„ë£Œ ì¦ê±°: reused types/error semantics inventory.

## Backend ?‘ì—…

- [x] API-010 `meeting.participantIds` and `deliveries[]` projection normal/empty/history-hidden casesë¥?failing testë¡??‘ì„±?œë‹¤. ?„ë£Œ ì¦ê±°: response contract assertions.
- [x] API-015 selected IDs must be subset of immutable Session roster, duplicate IDs handled, empty accepted, external participant status never queried for roster eligibilityë¥??ŒìŠ¤?¸í•œ?? ?„ë£Œ ì¦ê±°: boundary test.
- [x] API-010 response mapper??roster IDs ë°?generic Delivery projection??ì¶”ê??œë‹¤. ê²°ê³¼: API-010 single Session version/snapshot?¼ë¡œ statusesë¥?ë°˜í™˜?œë‹¤.
- [x] API-015 coordinatorê°€ ParticipantProvider dataë¥?selected IDsë¡??œí•œ??email addressë¥?resolve?˜ê³ , miss/bad email??recipient-level `FAILED`ë¡??€?¥í•œ?? ê²°ê³¼: roster itself and other channels stay unchanged.
- [x] EmailProviderë¥?recipientë³?invoke?˜ê³  success/failure/retryable resultë¥?generic Deliveryë¡?mapping?œë‹¤. ê²°ê³¼: mixed recipients are isolated; no raw addresses persisted.
- [x] Missing Provider list or batch failureë¥?safe Email failed outcomesë¡?mapping?˜ê³  Notification/document resultë¥?? ì??œë‹¤. ê²°ê³¼: generic API-010 still returns other channel outcomes.

## Frontend ?‘ì—…

- [x] API-010/003 clients/types/query testsë¥?ë¨¼ì? ?‘ì„±?œë‹¤. ê²°ê³¼: Session roster IDsë§?eligible, API-003 records are display metadata, unknown records fallback to ID.
- [x] SCR-007 checkbox testë¥??‘ì„±?œë‹¤. ê²°ê³¼: default selected per screen, user can deselect, zero selection persists as empty array.
- [x] Selection stateë¥?API-015 request test?€ ?°ê²°?œë‹¤. ê²°ê³¼: emailRecipientParticipantIds only selected Session roster IDs, never emails.
- [x] Publish `202` ?´í›„ API-010 polling/cache version guardë¥?êµ¬í˜„?œë‹¤. ê²°ê³¼: each delivery status updates from authoritative server snapshot only.
- [x] Per-recipient PENDING/SENDING/SENT/FAILED/retryable feedbackë¥?êµ¬í˜„?œë‹¤. ê²°ê³¼: mixed outcomes independent, no resend controls before TASK-013.02.
- [x] Provider/email roster read errors and PII redaction/accessibility/mobile statesë¥?êµ¬í˜„?œë‹¤. ê²°ê³¼: selection/draft preserved; no raw email in errors/logs.

## ?µí•© ë°??„ë£Œ ?•ì¸

- [x] Document save?’email handoff and API-010 delivery results end-to-end with mocked Gmail and ParticipantProvider. ê²°ê³¼: selected IDs only, message delivery only after document ref exists.
- [x] FE ë³€ê²??™ì‘ ?ŒìŠ¤???Œì¼??`npm run test -- --run <changed-test-file>`ë¡?? íƒ???¤í–‰?˜ê³ , lint/buildê°€ ë³€ê²?ê²€ì¦ì— ?„ìš”??ê²½ìš°?ë§Œ ?¤í–‰?œë‹¤. Backend??Java 25?€ Windows Gradle Wrapperë¡?ë³€ê²?ì½”ë“œ ë°?ì§ì ‘ ?í–¥ë°›ëŠ” ?ŒìŠ¤?¸ë§Œ `test --tests <fully.qualified.TestClass>` selectorë¡??¤í–‰?œë‹¤. ?„ì²´ Backend suite, `clean build`, local service, ë³„ë„ acceptance/QA pass???¤í–‰?˜ì? ?ŠëŠ”?? ê²°ê³¼: ?•í™•??command/output??evidence??ê¸°ë¡?œë‹¤.
- [x] ?ë™??contract/component ?ŒìŠ¤?¸ë¡œ roster ?œí•œ, sent/failed ë¶€ë¶?ê²°ê³¼, ë¹?? íƒ, ë¯¼ê° ?‘ë‹µ ?„ë“œ ë¶€?¬ì? Email ?íƒœ ë¬¸êµ¬ë¥?ê²€ì¦í•œ?? ê²°ê³¼: Issue #47 ë°?task evidence???¬í˜„ ê°€?¥í•œ ê²°ê³¼ë¥?ê¸°ë¡?œë‹¤.

package com.meetingautomation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.DocumentConnectionGate;
import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.ProviderHealth;
import com.meetingautomation.document.MeetingDocumentRef;
import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ParticipantListingProvider;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import com.meetingautomation.document.transfer.DocumentTransferPort;
import com.meetingautomation.document.transfer.TransferRecord;
import com.meetingautomation.document.transfer.TransferManifest;
import com.meetingautomation.document.transfer.TransferCodec;
import com.meetingautomation.minutes.MinutesDocumentCommand;
import com.meetingautomation.minutes.MinutesDocumentWriter;
import com.meetingautomation.document.settings.DocumentProviderFactory;
import com.meetingautomation.document.settings.GlobalSettingsStore;
import com.meetingautomation.email.EmailProvider;
import com.meetingautomation.email.MeetingEmailCommand;
import com.meetingautomation.email.RecipientDeliveryResult;
import com.meetingautomation.notification.DeliveryResult;
import com.meetingautomation.notification.MeetingPublishedCommand;
import com.meetingautomation.notification.slack.SlackNotificationAdapter;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"DOCUMENT_PROVIDER=", "NOTIFICATION_PROVIDER=SLACK", "OPENAI_API_KEY="})
@AutoConfigureMockMvc
@Import({MeetingSessionPublishApiTests.TestFixtures.class,
        ErrorContractProbeController.class, ConstraintViolationProbeService.class})
class MeetingSessionPublishApiTests {
    private static final String SESSION_URL = "/api/v1/meeting-sessions/{sessionId}";
    private static final String PUBLISH_URL = SESSION_URL + "/publish";
    private static final String RETRY_URL = SESSION_URL + "/deliveries/{deliveryId}/retry";
    private static final String SESSION_ID = "ms_publish-" + UUID.randomUUID();
    private static final String REQUEST = """
            {"emailRecipientParticipantIds":["pt_ada"],"notificationEnabled":true}
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private MeetingSessionStore sessionStore;
    @Autowired private ManualPublishExecutor workerExecutor;
    @Autowired private GlobalSettingsStore globalSettingsStore;
    @Autowired private DocumentConnectionGate gate;

    @MockitoBean private DocumentProviderFactory providerFactory;
    @MockitoBean private EmailProvider emailProvider;
    @MockitoBean private SlackNotificationAdapter notificationProvider;
    @MockitoBean private MinutesDocumentWriter minutesDocumentWriter;

    @BeforeEach
    void resetPublishFixture() {
        saveConfirmedSessionAndResetQueue();
    }

    void saveConfirmedSessionAndResetQueue() {
        sessionStore.remove(SESSION_ID);
        workerExecutor.reset();
        FakePublishProvider.PUBLISH_CALLS.set(0);
        when(providerFactory.create(any())).thenReturn(new FakePublishProvider());
        when(emailProvider.sendMeetingEmail(any())).thenAnswer(invocation -> {
            MeetingEmailCommand command = invocation.getArgument(0);
            return command.recipients().stream().map(recipient ->
                    RecipientDeliveryResult.sent(recipient.participantId())).toList();
        });
        when(notificationProvider.sendMeetingPublished(any())).thenReturn(DeliveryResult.sent());
        when(minutesDocumentWriter.render(any())).thenReturn("# Planning\n\n## 회의 요약\nSummary");
        sessionStore.save(new MeetingSession(SESSION_ID, 7, "CONFIRMED", 1,
                new MeetingSession.Meeting("Planning", "default.md", "1.0.0", List.of("pt_ada", "pt_grace"),
                        "Asia/Seoul", List.of(new MeetingSession.ParticipantReference("pt_ada", "Ada"),
                                new MeetingSession.ParticipantReference("pt_grace", "Grace"))),
                "DRAFT_READY", 100,
                List.of(new Speaker("speaker_1", "Speaker one", "pt_ada")),
                List.of(new TranscriptSegment("seg_1", "speaker_1", 0, 1000, "Discuss launch")), null,
                new StructuredMinutes("default.md", "1.0.0", "Summary", List.of("Discussion"),
                        List.of("Decision"), List.of(new StructuredMinutes.ActionItem(
                                "pt_grace", "Follow up", LocalDate.of(2026, 10, 20))), List.of("Next")),
                Instant.parse("2026-10-01T03:00:00Z")), null);
    }

    @Test
    void validPublishReturns202AndAtomicallyQueuesOneWorkerForSameKeyReplay() throws Exception {
        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document").doesNotExist())
                .andExpect(jsonPath("$.data.meeting.participantIds[0]").value("pt_ada"))
                .andExpect(jsonPath("$.data.deliveries").doesNotExist())
                .andExpect(jsonPath("$.data.publish").doesNotExist());
        String first = mockMvc.perform(publish(SESSION_ID, "7", "publish-once", REQUEST))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.status").value("PUBLISHING"))
                .andReturn().getResponse().getContentAsString();

        MeetingSession accepted = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(accepted.status()).isEqualTo("PUBLISHING");
        assertThat(accepted.version()).isEqualTo(8);
        assertThat(workerExecutor.pendingCount()).isEqualTo(1);
        assertThat(FakePublishProvider.PUBLISH_CALLS.get()).isZero();
        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document").doesNotExist());

        String replay = mockMvc.perform(publish(SESSION_ID, "7", "publish-once", REQUEST))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(sessionStore.find(SESSION_ID).orElseThrow()).isEqualTo(accepted);
        assertThat(workerExecutor.pendingCount()).isEqualTo(1);
        assertThat(globalSettingsStore.read().active().connectionVersion()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gate.beginSwitch(globalSettingsStore.read().version()))
                .isInstanceOf(com.meetingautomation.document.settings.SettingsException.class);
        workerExecutor.drain();
        assertThat(FakePublishProvider.PUBLISH_CALLS.get()).isEqualTo(1);
        org.mockito.ArgumentCaptor<MinutesDocumentCommand> documentCommand =
                org.mockito.ArgumentCaptor.forClass(MinutesDocumentCommand.class);
        verify(minutesDocumentWriter).render(documentCommand.capture());
        assertThat(documentCommand.getValue().templateContent()).contains("# 기본 회의록 템플릿");
        assertThat(documentCommand.getValue().reviewedMinutes()).contains("Summary", "Discussion", "Decision", "Follow up");
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("DOCUMENT_SAVED");
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(13);
        assertThat(sessionStore.findPublishSnapshot(SESSION_ID).document().documentId()).isEqualTo("doc_publish");
        assertThat(sessionStore.findPublishSnapshot(SESSION_ID).recipients()).containsExactly("pt_ada");
        assertThat(sessionStore.findPublishSnapshot(SESSION_ID).notificationEnabled()).isTrue();
        org.mockito.ArgumentCaptor<MeetingEmailCommand> emailCommand =
                org.mockito.ArgumentCaptor.forClass(MeetingEmailCommand.class);
        verify(emailProvider).sendMeetingEmail(emailCommand.capture());
        assertThat(emailCommand.getValue().subject()).isEqualTo("회의록 공유: Planning");
        assertThat(emailCommand.getValue().textBody()).isEqualTo("# Planning\n\n## 회의 요약\nSummary");
        assertThat(emailCommand.getValue().documentUrl()).isEqualTo("https://docs.example/doc_publish");
        assertThat(emailCommand.getValue().recipients()).containsExactly(
                new MeetingEmailCommand.Recipient("pt_ada", "ada@example.com"));
        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.documentId").value("doc_publish"))
                .andExpect(jsonPath("$.data.document.documentUrl").value("https://docs.example/doc_publish"))
                .andExpect(jsonPath("$.data.deliveries[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.data.deliveries[0].recipientParticipantId").value("pt_ada"))
                .andExpect(jsonPath("$.data.deliveries[0].status").value("SENT"))
                .andExpect(jsonPath("$.data.deliveries[0].retryable").value(false))
                .andExpect(jsonPath("$.data.deliveries[0].email").doesNotExist())
                .andExpect(jsonPath("$.data.deliveries[0].providerMessageId").doesNotExist())
                .andExpect(jsonPath("$.data.deliveries[1].channel").value("NOTIFICATION"))
                .andExpect(jsonPath("$.data.deliveries[1].status").value("SENT"))
                .andExpect(jsonPath("$.data.publish").doesNotExist());
        try (var switchLease = gate.beginSwitch(globalSettingsStore.read().version())) {
            assertThat(switchLease).isNotNull();
        }
    }

    @Test
    void emptyRecipientSelectionSavesDocumentWithoutCallingEmailProvider() throws Exception {
        mockMvc.perform(publish(SESSION_ID, "7", "publish-empty-email",
                        "{\"emailRecipientParticipantIds\":[],\"notificationEnabled\":false}"))
                .andExpect(status().isAccepted());
        workerExecutor.drain();

        verify(emailProvider, never()).sendMeetingEmail(any());
        verify(notificationProvider, never()).sendMeetingPublished(any());
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("DOCUMENT_SAVED");
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(9);
        assertThat(sessionStore.findPublishSnapshot(SESSION_ID).recipients()).isEmpty();
        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.documentId").value("doc_publish"))
                .andExpect(jsonPath("$.data.deliveries").isArray())
                .andExpect(jsonPath("$.data.deliveries").isEmpty());
    }

    @Test
    void selectedNotificationIsSentAfterDocumentAndAppearsAsIndependentGenericDelivery() throws Exception {
        mockMvc.perform(publish(SESSION_ID, "7", "notification-success", REQUEST))
                .andExpect(status().isAccepted());
        workerExecutor.drain();

        org.mockito.ArgumentCaptor<MeetingPublishedCommand> command =
                org.mockito.ArgumentCaptor.forClass(MeetingPublishedCommand.class);
        verify(notificationProvider).sendMeetingPublished(command.capture());
        assertThat(command.getValue().title()).isEqualTo("Planning");
        assertThat(command.getValue().documentUrl()).isEqualTo("https://docs.example/doc_publish");
        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DOCUMENT_SAVED"))
                .andExpect(jsonPath("$.data.deliveries[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.data.deliveries[0].status").value("SENT"))
                .andExpect(jsonPath("$.data.deliveries[1].channel").value("NOTIFICATION"))
                .andExpect(jsonPath("$.data.deliveries[1].recipientParticipantId").doesNotExist())
                .andExpect(jsonPath("$.data.deliveries[1].status").value("SENT"))
                .andExpect(jsonPath("$.data.deliveries[1].attemptCount").value(1));
    }

    @Test
    void notificationFailureKeepsSuccessfulEmailAndDocumentOutcome() throws Exception {
        when(notificationProvider.sendMeetingPublished(any())).thenReturn(DeliveryResult.failed(true));
        mockMvc.perform(publish(SESSION_ID, "7", "notification-failure", REQUEST))
                .andExpect(status().isAccepted());
        workerExecutor.drain();

        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DOCUMENT_SAVED"))
                .andExpect(jsonPath("$.data.deliveries[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.data.deliveries[0].status").value("SENT"))
                .andExpect(jsonPath("$.data.deliveries[1].channel").value("NOTIFICATION"))
                .andExpect(jsonPath("$.data.deliveries[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data.deliveries[1].errorCode").value("NOTIFICATION_FAILED"))
                .andExpect(jsonPath("$.data.deliveries[1].retryable").value(true));
        verify(notificationProvider).sendMeetingPublished(any());
    }

    @Test
    void retryFailedEmailDeliveryReplaysByKeyAndLeavesOtherChannelUntouched() throws Exception {
        doAnswer(invocation -> {
            MeetingEmailCommand command = invocation.getArgument(0);
            return command.recipients().stream().map(recipient ->
                    RecipientDeliveryResult.failed(recipient.participantId(), true)).toList();
        }).when(emailProvider).sendMeetingEmail(any());
        mockMvc.perform(publish(SESSION_ID, "7", "retry-email-source", REQUEST)).andExpect(status().isAccepted());
        workerExecutor.drain();
        MeetingSessionStatusResponseData before = deliverySnapshot(SESSION_ID);
        String emailId = before.emailDeliveryId();
        int versionBeforeRetry = sessionStore.find(SESSION_ID).orElseThrow().version();
        doAnswer(invocation -> {
            MeetingEmailCommand command = invocation.getArgument(0);
            return command.recipients().stream().map(recipient ->
                    RecipientDeliveryResult.sent(recipient.participantId())).toList();
        }).when(emailProvider).sendMeetingEmail(any());
        when(providerFactory.create(any())).thenReturn(new FakePublishProvider(false, false, null,
                "ada.current@example.com"));

        String first = mockMvc.perform(retry(SESSION_ID, emailId, "email-retry-once"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.deliveryId").value(emailId))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String replay = mockMvc.perform(retry(SESSION_ID, emailId, "email-retry-once"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(workerExecutor.pendingCount()).isEqualTo(1);
        workerExecutor.drain();
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isGreaterThan(versionBeforeRetry);
        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveries[0].status").value("SENT"))
                .andExpect(jsonPath("$.data.deliveries[0].attemptCount").value(2))
                .andExpect(jsonPath("$.data.deliveries[1].status").value("SENT"))
                .andExpect(jsonPath("$.data.deliveries[1].attemptCount").value(1));
        org.mockito.ArgumentCaptor<MeetingEmailCommand> command =
                org.mockito.ArgumentCaptor.forClass(MeetingEmailCommand.class);
        verify(emailProvider, org.mockito.Mockito.times(2)).sendMeetingEmail(command.capture());
        assertThat(command.getAllValues().get(1).recipients())
                .containsExactly(new MeetingEmailCommand.Recipient("pt_ada", "ada.current@example.com"));
        verify(notificationProvider).sendMeetingPublished(any());
    }

    @Test
    void retryRejectsMissingKeyUnknownDeliveryAndAlreadySuccessfulDelivery() throws Exception {
        mockMvc.perform(retry(SESSION_ID, "dlv_00000000-0000-0000-0000-000000000000", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(retry("ms_publish_missing", "dlv_00000000-0000-0000-0000-000000000000", "missing-session"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));
        mockMvc.perform(retry(SESSION_ID, "dlv_00000000-0000-0000-0000-000000000000", "unknown-delivery"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("DELIVERY_NOT_FOUND"));

        String foreignSessionId = "ms_publish_foreign-" + UUID.randomUUID();
        sessionStore.save(new MeetingSession(foreignSessionId, 7, "CONFIRMED", 1,
                new MeetingSession.Meeting("Foreign", "default.md", "1.0.0", List.of("pt_ada"),
                        "Asia/Seoul", List.of(new MeetingSession.ParticipantReference("pt_ada", "Ada"))),
                "DRAFT_READY", 100, List.of(), List.of(), null,
                new StructuredMinutes("default.md", "1.0.0", "Summary", List.of(), List.of(), List.of(), List.of()),
                Instant.parse("2026-10-01T03:00:00Z")), null);
        mockMvc.perform(publish(foreignSessionId, "7", "foreign-owner-publish",
                "{\"emailRecipientParticipantIds\":[\"pt_ada\"],\"notificationEnabled\":false}"))
                .andExpect(status().isAccepted());
        workerExecutor.drain();
        mockMvc.perform(retry(SESSION_ID, deliveryId(foreignSessionId, 0), "foreign-owner-retry"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("DELIVERY_NOT_FOUND"));
        sessionStore.remove(foreignSessionId);

        mockMvc.perform(publish(SESSION_ID, "7", "retry-sent-source", REQUEST)).andExpect(status().isAccepted());
        workerExecutor.drain();
        String sentId = deliveryId(SESSION_ID, 0);
        mockMvc.perform(retry(SESSION_ID, sentId, "retry-sent-delivery"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DELIVERY_NOT_RETRYABLE"));
        verify(emailProvider, org.mockito.Mockito.times(2)).sendMeetingEmail(any());
        verify(notificationProvider).sendMeetingPublished(any());
    }

    @Test
    void retryRejectsNonretryableAndPendingDeliveriesWithoutProviderCalls() throws Exception {
        doThrow(new IllegalStateException("sensitive timeout detail"))
                .when(emailProvider).sendMeetingEmail(any());
        mockMvc.perform(publish(SESSION_ID, "7", "retry-nonretryable-source", REQUEST)).andExpect(status().isAccepted());
        workerExecutor.drain();
        String emailId = deliveryId(SESSION_ID, 0);
        String response = mockMvc.perform(retry(SESSION_ID, emailId, "retry-nonretryable"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DELIVERY_NOT_RETRYABLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("sensitive timeout detail");
        verify(emailProvider).sendMeetingEmail(any());

        org.mockito.Mockito.reset(emailProvider, notificationProvider);
        saveConfirmedSessionAndResetQueue();
        mockMvc.perform(publish(SESSION_ID, "7", "retry-pending-source", REQUEST)).andExpect(status().isAccepted());
        String pendingId = deliveryId(SESSION_ID, 0);
        mockMvc.perform(retry(SESSION_ID, pendingId, "retry-pending"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DELIVERY_RETRY_IN_PROGRESS"));
        assertThat(workerExecutor.pendingCount()).isEqualTo(1);
        verify(emailProvider, never()).sendMeetingEmail(any());
        workerExecutor.drain();
    }

    @Test
    void correctableEmailRosterLookupFailureRemainsRetryableWithoutCountingAnAttempt() throws Exception {
        doAnswer(invocation -> {
            MeetingEmailCommand command = invocation.getArgument(0);
            return command.recipients().stream().map(recipient ->
                    RecipientDeliveryResult.failed(recipient.participantId(), true)).toList();
        }).when(emailProvider).sendMeetingEmail(any());
        mockMvc.perform(publish(SESSION_ID, "7", "retry-preflight-source",
                "{\"emailRecipientParticipantIds\":[\"pt_ada\"],\"notificationEnabled\":false}"))
                .andExpect(status().isAccepted());
        workerExecutor.drain();
        String emailId = deliveryId(SESSION_ID, 0);
        when(providerFactory.create(any())).thenReturn(new FakePublishProvider(false, true));

        mockMvc.perform(retry(SESSION_ID, emailId, "retry-preflight-failure")).andExpect(status().isAccepted());
        workerExecutor.drain();

        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveries[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.deliveries[0].retryable").value(true))
                .andExpect(jsonPath("$.data.deliveries[0].attemptCount").value(1));
        verify(emailProvider, org.mockito.Mockito.times(1)).sendMeetingEmail(any());
    }

    @Test
    void initialEmailPreflightFailureEmitsSafeCorrelatedIncidentLog() throws Exception {
        when(providerFactory.create(any())).thenReturn(new FakePublishProvider(false, true));
        Logger logger = (Logger) LoggerFactory.getLogger("com.meetingautomation.api.session.PublishJobRunner");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        mockMvc.perform(publish(SESSION_ID, "7", "initial-email-preflight-failure",
                "{\"emailRecipientParticipantIds\":[\"pt_ada\"],\"notificationEnabled\":false}")
                .header("X-Request-Id", "publish-trace-57"))
                .andExpect(status().isAccepted());
        workerExecutor.drain();

        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveries[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.deliveries[0].retryable").value(true))
                .andExpect(jsonPath("$.data.deliveries[0].attemptCount").value(0));
        String logs = appender.list.stream()
                .map(event -> event.getFormattedMessage() + event.getKeyValuePairs())
                .reduce("", String::concat);
        logger.detachAppender(appender);
        appender.stop();

        assertThat(logs).contains("EMAIL_FAILURE", SESSION_ID, "publish-trace-57", "EMAIL_DELIVERY",
                "EMAIL_FAILED", "retryable=\"true\"", "occurredAt", "Email delivery failed.")
                .doesNotContain("ada@example.com", "pt_ada", "private-provider-body", "Notion-Version", "fake-token");
    }

    @Test
    void retryNotificationUsesOnlyNotificationProviderAndConflictingKeyCannotTargetAnotherDelivery() throws Exception {
        when(notificationProvider.sendMeetingPublished(any())).thenReturn(DeliveryResult.failed(true));
        mockMvc.perform(publish(SESSION_ID, "7", "retry-notification-source",
                "{\"emailRecipientParticipantIds\":[\"pt_ada\",\"pt_grace\"],\"notificationEnabled\":true}"))
                .andExpect(status().isAccepted());
        workerExecutor.drain();
        String notificationId = deliveryId(SESSION_ID, 2);
        String emailId = deliveryId(SESSION_ID, 0);
        when(notificationProvider.sendMeetingPublished(any())).thenReturn(DeliveryResult.sent());
        mockMvc.perform(retry(SESSION_ID, notificationId, "shared-retry-key")).andExpect(status().isAccepted());
        mockMvc.perform(retry(SESSION_ID, emailId, "shared-retry-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        workerExecutor.drain();
        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveries[0].status").value("SENT"))
                .andExpect(jsonPath("$.data.deliveries[0].attemptCount").value(1))
                .andExpect(jsonPath("$.data.deliveries[1].status").value("SENT"))
                .andExpect(jsonPath("$.data.deliveries[1].attemptCount").value(1))
                .andExpect(jsonPath("$.data.deliveries[2].status").value("SENT"))
                .andExpect(jsonPath("$.data.deliveries[2].attemptCount").value(2));
        verify(notificationProvider, org.mockito.Mockito.times(2)).sendMeetingPublished(any());
        verify(emailProvider, org.mockito.Mockito.times(1)).sendMeetingEmail(any());
    }

    @Test
    void concurrentDifferentKeysCanQueueOnlyOneRetryForTheSameDelivery() throws Exception {
        doAnswer(invocation -> {
            MeetingEmailCommand command = invocation.getArgument(0);
            return command.recipients().stream().map(recipient ->
                    RecipientDeliveryResult.failed(recipient.participantId(), true)).toList();
        }).when(emailProvider).sendMeetingEmail(any());
        mockMvc.perform(publish(SESSION_ID, "7", "concurrent-retry-source",
                "{\"emailRecipientParticipantIds\":[\"pt_ada\"],\"notificationEnabled\":false}"))
                .andExpect(status().isAccepted());
        workerExecutor.drain();
        String emailId = deliveryId(SESSION_ID, 0);
        doAnswer(invocation -> {
            MeetingEmailCommand command = invocation.getArgument(0);
            return command.recipients().stream().map(recipient ->
                    RecipientDeliveryResult.sent(recipient.participantId())).toList();
        }).when(emailProvider).sendMeetingEmail(any());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> retryStatus(emailId, "concurrent-key-a", ready, start));
            var second = pool.submit(() -> retryStatus(emailId, "concurrent-key-b", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(202, 409);
            assertThat(workerExecutor.pendingCount()).isEqualTo(1);
            verify(emailProvider).sendMeetingEmail(any());
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
        workerExecutor.drain();
        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveries[0].status").value("SENT"))
                .andExpect(jsonPath("$.data.deliveries[0].attemptCount").value(2));
    }

    private int retryStatus(String deliveryId, String key, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Retry test barrier timed out.");
        return mockMvc.perform(retry(SESSION_ID, deliveryId, key)).andReturn().getResponse().getStatus();
    }

    private MeetingSessionStatusResponseData deliverySnapshot(String sessionId) throws Exception {
        String json = mockMvc.perform(get(SESSION_URL, sessionId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var root = new tools.jackson.databind.ObjectMapper().readTree(json);
        return new MeetingSessionStatusResponseData(root.at("/data/deliveries/0/deliveryId").asText());
    }

    private String deliveryId(String sessionId, int index) throws Exception {
        String json = mockMvc.perform(get(SESSION_URL, sessionId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(json)
                .at("/data/deliveries/" + index + "/deliveryId").asText();
    }

    private record MeetingSessionStatusResponseData(String emailDeliveryId) { }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder retry(
            String sessionId, String deliveryId, String key) {
        var request = post(RETRY_URL, sessionId, deliveryId);
        if (key != null) request.header("Idempotency-Key", key);
        return request;
    }

    @Test
    void participantLookupFailureKeepsDocumentSavedAndDoesNotCountAnEmailAttempt() throws Exception {
        when(providerFactory.create(any())).thenReturn(new FakePublishProvider(false, true));
        mockMvc.perform(publish(SESSION_ID, "7", "publish-list-error", REQUEST)).andExpect(status().isAccepted());

        workerExecutor.drain();

        verify(emailProvider, never()).sendMeetingEmail(any());
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("DOCUMENT_SAVED");
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(12);
        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.documentId").value("doc_publish"))
                .andExpect(jsonPath("$.data.deliveries[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.deliveries[0].attemptCount").value(0))
                .andExpect(jsonPath("$.data.deliveries[0].retryable").value(true))
                .andExpect(jsonPath("$.data.deliveries[0].errorCode").value("EMAIL_FAILED"));
    }

    @Test
    void ambiguousEmailProviderExceptionIsNotMarkedSafeToRetry() throws Exception {
        doThrow(new IllegalStateException("provider timeout detail")).when(emailProvider).sendMeetingEmail(any());
        mockMvc.perform(publish(SESSION_ID, "7", "publish-email-ambiguous", REQUEST)).andExpect(status().isAccepted());

        workerExecutor.drain();

        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("DOCUMENT_SAVED");
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(13);
        String response = mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveries[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.deliveries[0].retryable").value(false))
                .andExpect(jsonPath("$.data.deliveries[0].errorCode").value("EMAIL_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("provider timeout detail");
    }

    @Test
    void mixedEmailResultsRemainIndependentAndDoNotExposeAddressesInStatus() throws Exception {
        doAnswer(invocation -> {
            MeetingEmailCommand command = invocation.getArgument(0);
            return command.recipients().stream().map(recipient -> recipient.participantId().equals("pt_ada")
                    ? RecipientDeliveryResult.sent(recipient.participantId())
                    : RecipientDeliveryResult.failed(recipient.participantId(), true)).toList();
        }).when(emailProvider).sendMeetingEmail(any());
        mockMvc.perform(publish(SESSION_ID, "7", "publish-email-mixed",
                        "{\"emailRecipientParticipantIds\":[\"pt_ada\",\"pt_grace\"],\"notificationEnabled\":false}"))
                .andExpect(status().isAccepted());

        workerExecutor.drain();

        String response = mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveries[0].recipientParticipantId").value("pt_ada"))
                .andExpect(jsonPath("$.data.deliveries[0].status").value("SENT"))
                .andExpect(jsonPath("$.data.deliveries[1].recipientParticipantId").value("pt_grace"))
                .andExpect(jsonPath("$.data.deliveries[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data.deliveries[1].retryable").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("ada@example.com", "grace@example.com");
    }

    @Test
    void missingRecipientEmailFailsOnlyThatRecipientWithoutCallingProviderForIt() throws Exception {
        when(providerFactory.create(any())).thenReturn(new FakePublishProvider(false, false, "pt_ada"));
        mockMvc.perform(publish(SESSION_ID, "7", "publish-email-missing-address",
                        "{\"emailRecipientParticipantIds\":[\"pt_ada\",\"pt_grace\"],\"notificationEnabled\":false}"))
                .andExpect(status().isAccepted());

        workerExecutor.drain();

        org.mockito.ArgumentCaptor<MeetingEmailCommand> command = org.mockito.ArgumentCaptor.forClass(MeetingEmailCommand.class);
        verify(emailProvider).sendMeetingEmail(command.capture());
        assertThat(command.getValue().recipients()).containsExactly(
                new MeetingEmailCommand.Recipient("pt_grace", "grace@example.com"));
        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveries[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.deliveries[0].attemptCount").value(0))
                .andExpect(jsonPath("$.data.deliveries[0].retryable").value(true))
                .andExpect(jsonPath("$.data.deliveries[1].status").value("SENT"))
                .andExpect(jsonPath("$.data.deliveries[1].attemptCount").value(1));
    }

    @Test
    void malformedBodyAndRosterRecipientAreRejectedWithoutChangingSessionOrQueue() throws Exception {
        MeetingSession before = sessionStore.find(SESSION_ID).orElseThrow();
        for (String body : List.of("", "{", "{}", "null",
                "{\"emailRecipientParticipantIds\":null,\"notificationEnabled\":true}",
                "{\"emailRecipientParticipantIds\":[],\"notificationEnabled\":null}",
                "{\"emailRecipientParticipantIds\":[\"pt_ada\",\"pt_ada\"],\"notificationEnabled\":true}",
                "{\"emailRecipientParticipantIds\":[],\"notificationEnabled\":true,\"extra\":1}")) {
            mockMvc.perform(publish(SESSION_ID, "7", "publish-invalid-" + UUID.randomUUID(), body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(publish(SESSION_ID, "7", "publish-outside-roster",
                        "{\"emailRecipientParticipantIds\":[\"pt_outside\"],\"notificationEnabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(sessionStore.find(SESSION_ID).orElseThrow()).isEqualTo(before);
        assertThat(workerExecutor.pendingCount()).isZero();
        assertThat(FakePublishProvider.PUBLISH_CALLS.get()).isZero();
        mockMvc.perform(post(PUBLISH_URL, SESSION_ID).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isBadRequest());
        mockMvc.perform(publish(SESSION_ID, "not-an-etag", "bad-etag", REQUEST))
                .andExpect(status().isBadRequest());
    }

    @Test
    void idempotencyKeyConflictAndSwitchAlreadyHeldDoNotMutateSession() throws Exception {
        MeetingSession before = sessionStore.find(SESSION_ID).orElseThrow();
        try (var switchLease = gate.beginSwitch(globalSettingsStore.read().version())) {
            mockMvc.perform(publish(SESSION_ID, "7", "switch-held", REQUEST))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("DOCUMENT_CHANGE_BUSY"));
            assertThat(sessionStore.find(SESSION_ID).orElseThrow()).isEqualTo(before);
            assertThat(workerExecutor.pendingCount()).isZero();
        }
        mockMvc.perform(publish(SESSION_ID, "7", "conflict-key", REQUEST)).andExpect(status().isAccepted());
        mockMvc.perform(publish(SESSION_ID, "7", "conflict-key",
                        "{\"emailRecipientParticipantIds\":[],\"notificationEnabled\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        assertThat(workerExecutor.pendingCount()).isEqualTo(1);
        workerExecutor.drain();
    }

    @Test
    void differentKeyWhilePublishIsPendingIsRejectedWithoutSecondWorker() throws Exception {
        mockMvc.perform(publish(SESSION_ID, "7", "publish-key-one", REQUEST)).andExpect(status().isAccepted());
        mockMvc.perform(publish(SESSION_ID, "7", "publish-key-two", REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));
        assertThat(workerExecutor.pendingCount()).isEqualTo(1);
        assertThat(FakePublishProvider.PUBLISH_CALLS.get()).isZero();
        workerExecutor.drain();
        assertThat(FakePublishProvider.PUBLISH_CALLS.get()).isEqualTo(1);
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("DOCUMENT_SAVED");
    }

    @Test
    void missingSessionStateConflictAndStaleVersionDoNotQueuePublish() throws Exception {
        mockMvc.perform(publish("ms_publish_missing", "1", "missing-session-key", REQUEST))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));

        sessionStore.save(new MeetingSession("ms_publish_review", 7, "REVIEW", 1,
                new MeetingSession.Meeting("Planning", "default.md", "1.0.0", List.of("pt_ada"),
                        "Asia/Seoul", List.of(new MeetingSession.ParticipantReference("pt_ada", "Ada"))),
                "DRAFT_READY", 100, List.of(), List.of(), null,
                new StructuredMinutes("default.md", "1.0.0", "Summary", List.of(), List.of(), List.of(), List.of())), null);
        mockMvc.perform(publish("ms_publish_review", "7", "review-session-key", REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));

        mockMvc.perform(publish(SESSION_ID, "6", "stale-session-key", REQUEST))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error.code").value("SESSION_VERSION_CONFLICT"));
        assertThat(workerExecutor.pendingCount()).isZero();
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("CONFIRMED");
    }

    @Test
    void providerFailureSetsDocumentFailedReleasesLeaseAndRetryCompletes() throws Exception {
        FakePublishProvider failing = new FakePublishProvider(true);
        when(providerFactory.create(any())).thenReturn(failing);
        String first = mockMvc.perform(publish(SESSION_ID, "7", "publish-fail-first", REQUEST))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        workerExecutor.drain();
        MeetingSession failed = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(failed.status()).isEqualTo("DOCUMENT_FAILED");
        assertThat(failed.version()).isEqualTo(9);
        assertThat(sessionStore.findPublishSnapshot(SESSION_ID).errorCode()).isEqualTo("DOCUMENT_FAILED");
        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowedActions[0]").value("PUBLISH"))
                .andExpect(jsonPath("$.data.documentFailure.errorCode").value("DOCUMENT_FAILED"))
                .andExpect(jsonPath("$.data.documentFailure.retryable").value(true))
                .andExpect(jsonPath("$.data.document").doesNotExist())
                .andExpect(jsonPath("$.data.deliveries").doesNotExist())
                .andExpect(jsonPath("$.data.publish").doesNotExist());
        assertThat(mockMvc.perform(publish(SESSION_ID, "7", "publish-fail-first", REQUEST))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).isEqualTo(first);
        assertThat(workerExecutor.pendingCount()).isZero();
        try (var switchLease = gate.beginSwitch(globalSettingsStore.read().version())) { assertThat(switchLease).isNotNull(); }

        MeetingSession failedBeforeRetry = sessionStore.find(SESSION_ID).orElseThrow();
        MeetingSessionStore.PublishSnapshot failureBeforeRetry = sessionStore.findPublishSnapshot(SESSION_ID);
        workerExecutor.rejectNext();
        mockMvc.perform(publish(SESSION_ID, "9", "publish-retry-rejected", REQUEST))
                .andExpect(status().isInternalServerError());
        assertThat(sessionStore.find(SESSION_ID).orElseThrow()).isEqualTo(failedBeforeRetry);
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("DOCUMENT_FAILED");
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(9);
        assertThat(sessionStore.findPublishSnapshot(SESSION_ID)).isEqualTo(failureBeforeRetry);
        assertThat(sessionStore.findPublishSnapshot(SESSION_ID).errorCode()).isEqualTo("DOCUMENT_FAILED");
        try (var switchLease = gate.beginSwitch(globalSettingsStore.read().version())) { assertThat(switchLease).isNotNull(); }

        when(providerFactory.create(any())).thenReturn(new FakePublishProvider(false));
        mockMvc.perform(publish(SESSION_ID, "9", "publish-retry", REQUEST)).andExpect(status().isAccepted());
        workerExecutor.drain();
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("DOCUMENT_SAVED");
    }

    @Test
    void nonRetryableDocumentFailureExposesCauseAndDoesNotAdvertisePublishRetry() throws Exception {
        when(providerFactory.create(any())).thenReturn(new FakePublishProvider(
                com.meetingautomation.document.DocumentProviderException.structureNotFound()));
        mockMvc.perform(publish(SESSION_ID, "7", "publish-structure-failure", REQUEST))
                .andExpect(status().isAccepted());
        workerExecutor.drain();

        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DOCUMENT_FAILED"))
                .andExpect(jsonPath("$.data.documentFailure.errorCode").value("DOCUMENT_STRUCTURE_NOT_FOUND"))
                .andExpect(jsonPath("$.data.documentFailure.retryable").value(false))
                .andExpect(jsonPath("$.data.allowedActions[0]").value("PUBLISH"));
    }

    @Test
    void rejectedQueueRestoresSnapshotAndReleasesLease() throws Exception {
        MeetingSession before = sessionStore.find(SESSION_ID).orElseThrow();
        workerExecutor.rejectNext();
        mockMvc.perform(publish(SESSION_ID, "7", "publish-rejected", REQUEST)).andExpect(status().isInternalServerError());
        assertThat(sessionStore.find(SESSION_ID).orElseThrow()).isEqualTo(before);
        assertThat(workerExecutor.pendingCount()).isZero();
        try (var switchLease = gate.beginSwitch(globalSettingsStore.read().version())) { assertThat(switchLease).isNotNull(); }
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publish(
            String sessionId, String version, String key, String body) {
        return post(PUBLISH_URL, sessionId).header("If-Match", "\"" + version + "\"")
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestFixtures {
        @Bean @Primary
        GlobalSettingsStore activeSettings() {
            return DocumentSettingsFixtures.activeNotion("fake-token", "fake-root");
        }

        @Bean @Primary @Qualifier("processingJobExecutor")
        ManualPublishExecutor manualPublishExecutor() {
            return new ManualPublishExecutor();
        }
    }

    static final class ManualPublishExecutor implements Executor {
        private final ConcurrentLinkedQueue<Runnable> queued = new ConcurrentLinkedQueue<>();
        private boolean reject;
        @Override public void execute(Runnable command) { if (reject) { reject = false; throw new IllegalStateException("queue rejected"); } queued.add(command); }
        int pendingCount() { return queued.size(); }
        void reset() { queued.clear(); reject = false; }
        void rejectNext() { reject = true; }
        void drain() { Runnable task; while ((task = queued.poll()) != null) task.run(); }
    }

    static final class FakePublishProvider implements ParticipantListingProvider, DocumentTransferPort {
        private static final java.util.concurrent.atomic.AtomicInteger PUBLISH_CALLS = new java.util.concurrent.atomic.AtomicInteger();
        private final boolean fail;
        private RuntimeException publishFailure;
        private final boolean failParticipantList;
        private final String missingEmailParticipantId;
        private final String adaEmailAddress;
        FakePublishProvider() { this(false, false, null); }
        FakePublishProvider(boolean fail) { this(fail, false, null); }
        FakePublishProvider(RuntimeException publishFailure) { this(false, false, null); this.publishFailure = publishFailure; }
        FakePublishProvider(boolean fail, boolean failParticipantList) {
            this(fail, failParticipantList, null);
        }
        FakePublishProvider(boolean fail, boolean failParticipantList, String missingEmailParticipantId) {
            this(fail, failParticipantList, missingEmailParticipantId, "ada@example.com");
        }
        FakePublishProvider(boolean fail, boolean failParticipantList, String missingEmailParticipantId,
                String adaEmailAddress) {
            this.fail = fail;
            this.publishFailure = null;
            this.failParticipantList = failParticipantList;
            this.missingEmailParticipantId = missingEmailParticipantId;
            this.adaEmailAddress = adaEmailAddress;
        }
        @Override public ProviderHealth validateConnection() { return null; }
        @Override public DocumentStructure discoverStructure(String rootId) { return null; }
        @Override public DocumentStructure discoverParticipantStructure(String rootId) {
            return new DocumentStructure(rootId, "meetings", "participants");
        }
        @Override public List<Participant> listParticipants(String participantsPageId) {
            assertThat(participantsPageId).isEqualTo("participants");
            if (failParticipantList) throw com.meetingautomation.document.DocumentProviderException.participantListFailed(true);
            return List.of(new Participant("pt_ada", "Ada", "pt_ada".equals(missingEmailParticipantId) ? null : adaEmailAddress),
                    new Participant("pt_grace", "Grace", "grace@example.com"));
        }
        @Override public TransferManifest readManifest() { return new TransferManifest("sha256:" + "0".repeat(64), List.of(), List.of()); }
        @Override public TransferRecord exportMeeting(String sourceDocumentId) { return null; }
        @Override public Participant importParticipant(TransferRecord record, String sourceConnectionFingerprint) { return null; }
        @Override public MeetingDocumentRef importMeeting(TransferRecord record, java.util.Map<String, String> map,
                String sourceConnectionFingerprint) { return null; }
        @Override public MeetingDocumentRef publishMeeting(TransferRecord record) {
            PUBLISH_CALLS.incrementAndGet();
            if (publishFailure != null) throw publishFailure;
            if (fail) throw new IllegalStateException("provider failure");
            assertThat(record.marker().meetingAt().toString()).isEqualTo("2026-10-01T12:00+09:00");
            assertThat(new TransferCodec().canonicalDigest(record)).isNotBlank();
            return new MeetingDocumentRef("doc_publish", "https://docs.example/doc_publish");
        }
    }
}

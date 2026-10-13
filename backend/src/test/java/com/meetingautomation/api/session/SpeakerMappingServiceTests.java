package com.meetingautomation.api.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.meetingautomation.api.participant.ParticipantNotFoundException;
import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentProviderResolver;
import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ParticipantListingProvider;
import com.meetingautomation.document.ProviderHealth;
import com.meetingautomation.document.Speaker;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SpeakerMappingServiceTests {
    private final MeetingSessionStore store = new MeetingSessionStore();
    private final DocumentProviderResolver resolver = mock(DocumentProviderResolver.class);
    private final StubParticipantProvider provider = new StubParticipantProvider();
    private final SpeakerMappingService service = new SpeakerMappingService(store, resolver);

    @BeforeEach
    void setUp() {
        store.remove("session");
        store.save(new MeetingSession("session", 4, "REVIEW", 1,
                new MeetingSession.Meeting("Meeting", "template", List.of("pt_ada", "pt_grace"), "UTC"),
                "DRAFT_READY", 100,
                List.of(new Speaker("speaker_a", "A"), new Speaker("speaker_b", "B")), List.of(), null), null);
        provider.roster = List.of(new Participant("pt_ada", "Ada", "private@example.com"),
                new Participant("pt_grace", "Grace", "secret@example.com"));
        when(resolver.selectedProvider()).thenReturn(Optional.of(provider));
        when(resolver.configured()).thenReturn(true);
        when(resolver.rootId()).thenReturn("root");
    }

    @Test
    void mapsAllSpeakersAndResolvesCurrentRosterNamesWithoutRestrictingParticipantReuse() {
        SpeakerMappingResponse result = service.replace("session", "\"4\"", new SpeakerMappingRequest(List.of(
                new SpeakerMappingRequest.Mapping("speaker_b", "pt_ada"),
                new SpeakerMappingRequest.Mapping("speaker_a", "pt_ada"))), "trace-test");

        assertThat(result.data().version()).isEqualTo(5);
        assertThat(result.data().mappings()).containsExactly(
                new SpeakerMappingResponse.Mapping("speaker_a", "pt_ada", "Ada"),
                new SpeakerMappingResponse.Mapping("speaker_b", "pt_ada", "Ada"));
        assertThat(store.find("session").orElseThrow().speakers()).containsExactly(
                new Speaker("speaker_a", "A", "pt_ada"), new Speaker("speaker_b", "B", "pt_ada"));
    }

    @Test
    void rejectsMalformedProviderRosterRowsAsSafeFailureWithoutChangingSession() {
        List<List<Participant>> invalidRosters = List.of(
                Arrays.asList((Participant) null),
                List.of(new Participant(" ", "Ada", "private@example.com")),
                List.of(new Participant("pt_ada", " ", "private@example.com")),
                List.of(new Participant("pt_ada", "Ada", "private@example.com"),
                        new Participant("pt_ada", "Duplicate", "other@example.com")));
        for (List<Participant> invalidRoster : invalidRosters) {
            provider.roster = invalidRoster;
            assertThatThrownBy(() -> service.replace("session", "\"4\"", request(), "trace-test"))
                    .isInstanceOf(DocumentProviderException.class)
                    .extracting("code").isEqualTo("PARTICIPANT_LIST_FAILED");
            assertThat(store.find("session").orElseThrow().version()).isEqualTo(4);
            assertThat(store.find("session").orElseThrow().speakers())
                    .containsExactly(new Speaker("speaker_a", "A"), new Speaker("speaker_b", "B"));
        }
    }

    @Test
    void missingLiveRosterParticipantReturnsNotFoundWithoutPartialCommit() {
        provider.roster = List.of(new Participant("pt_ada", "Ada", "private@example.com"));

        assertThatThrownBy(() -> service.replace("session", "\"4\"", request(), "trace-test"))
                .isInstanceOf(ParticipantNotFoundException.class);
        assertThat(store.find("session").orElseThrow().version()).isEqualTo(4);
        assertThat(store.find("session").orElseThrow().speakers())
                .containsExactly(new Speaker("speaker_a", "A"), new Speaker("speaker_b", "B"));
    }

    @Test
    void preservesDocumentStructureFailureCodeAndProviderTextIsNotRetained() {
        provider.discoveryFailure = DocumentProviderException.structureNotFound();

        assertThatThrownBy(() -> service.replace("session", "\"4\"", request(), "trace-test"))
                .isInstanceOf(DocumentProviderException.class)
                .extracting("code").isEqualTo("DOCUMENT_STRUCTURE_NOT_FOUND");
        assertThat(store.find("session").orElseThrow().version()).isEqualTo(4);

        provider.discoveryFailure = new IllegalStateException("private-provider-response-secret");
        assertThatThrownBy(() -> service.replace("session", "\"4\"", request(), "trace-test"))
                .isInstanceOf(DocumentProviderException.class)
                .hasMessageNotContaining("private-provider-response-secret");
    }

    @Test
    void failureLogsContainCorrelationAndStageButNoParticipantOrProviderDetails() {
        provider.discoveryFailure = new IllegalStateException("private-provider-response-secret");
        Logger logger = (Logger) LoggerFactory.getLogger(SpeakerMappingService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> service.replace("session", "\"4\"", request(), "trace-safe"))
                    .isInstanceOf(DocumentProviderException.class);
        } finally {
            logger.detachAppender(appender);
        }
        String events = appender.list.toString();
        assertThat(events).contains("trace-safe", "session", "SPEAKER_MAPPING", "PARTICIPANT_LIST_FAILED");
        assertThat(events).doesNotContain("private-provider-response-secret", "private@example.com", "secret@example.com");
    }

    private static SpeakerMappingRequest request() {
        return new SpeakerMappingRequest(List.of(
                new SpeakerMappingRequest.Mapping("speaker_a", "pt_ada"),
                new SpeakerMappingRequest.Mapping("speaker_b", "pt_grace")));
    }

    private static final class StubParticipantProvider implements ParticipantListingProvider {
        private List<Participant> roster = List.of();
        private RuntimeException discoveryFailure;
        @Override public ProviderHealth validateConnection() { return new ProviderHealth(true, true, true); }
        @Override public DocumentStructure discoverStructure(String rootId) {
            if (discoveryFailure != null) throw discoveryFailure;
            return new DocumentStructure(rootId, "meetings", "participants");
        }
        @Override public List<Participant> listParticipants(String participantsPageId) { return roster; }
    }
}

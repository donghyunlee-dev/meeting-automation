package com.meetingautomation.api.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.meetingautomation.api.audio.AudioAssemblyStorage;
import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.UploadPolicy;
import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.TranscriptResult;
import com.meetingautomation.document.TranscriptSegment;
import com.meetingautomation.transcription.DiarizationResultAdapter;
import com.meetingautomation.transcription.ProviderTranscriptionResponse;
import com.meetingautomation.transcription.TranscriptionCommand;
import com.meetingautomation.transcription.TranscriptionException;
import com.meetingautomation.transcription.TranscriptionProvider;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.mockito.InOrder;

class ProcessingPipelineTests {
    private static final String SESSION_ID = "ms_processing-pipeline-test";
    private static final String TRACE_ID = "tr_processing-pipeline-test";
    private static final ProcessingJob JOB = new ProcessingJob(SESSION_ID, 1, 1_000, "audio/webm");
    private static final UploadPolicy POLICY = new UploadPolicy(15, 128, List.of("audio/webm"));
    private static final Path AUDIO_REFERENCE = Path.of("private-audio-reference.webm");

    private final MeetingSessionStore sessionStore = new MeetingSessionStore();
    private final AudioAssemblyUseCase audioAssembly = mock(AudioAssemblyUseCase.class);
    private final TranscriptionProvider transcriptionProvider = mock(TranscriptionProvider.class);
    private final DiarizationResultAdapter normalizer = mock(DiarizationResultAdapter.class);
    private final ProcessingPipeline pipeline = new ProcessingPipeline(
            sessionStore, audioAssembly, transcriptionProvider, normalizer);

    @BeforeEach
    void createProcessingSession() {
        sessionStore.remove(SESSION_ID);
        sessionStore.save(uploadingSession(), null, POLICY);
        sessionStore.startProcessing(SESSION_ID);
    }

    @Test
    void runsAssemblyTranscriptionAndNormalizationInOrderThenCommitsBothResultLists() {
        AudioAssemblyStorage.Assembly assembly = assembly();
        ProviderTranscriptionResponse response = providerResponse();
        TranscriptResult normalized = new TranscriptResult(
                List.of(new Speaker("speaker_a", "Speaker A")),
                List.of(new TranscriptSegment("seg_001", "speaker_a", 10, 200, "normalized text")));
        when(audioAssembly.assemble(JOB, POLICY, TRACE_ID)).thenReturn(assembly);
        when(transcriptionProvider.transcribeWithDiarization(any(TranscriptionCommand.class))).thenReturn(response);
        when(normalizer.normalize(response, JOB.durationMs())).thenReturn(normalized);

        pipeline.process(JOB, TRACE_ID);

        InOrder order = inOrder(audioAssembly, transcriptionProvider, normalizer);
        order.verify(audioAssembly).assemble(JOB, POLICY, TRACE_ID);
        order.verify(transcriptionProvider).transcribeWithDiarization(any(TranscriptionCommand.class));
        order.verify(normalizer).normalize(response, JOB.durationMs());
        MeetingSession saved = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(saved.status()).isEqualTo("PROCESSING");
        assertThat(saved.processingStage()).isEqualTo("DIARIZATION");
        assertThat(saved.progressPercent()).isEqualTo(100);
        assertThat(saved.speakers()).containsExactlyElementsOf(normalized.speakers());
        assertThat(saved.transcript()).containsExactlyElementsOf(normalized.segments());
        assertThat(saved.processingFailure()).isNull();
        verify(transcriptionProvider).transcribeWithDiarization(org.mockito.ArgumentMatchers.argThat(command ->
                command.audioReference().equals(AUDIO_REFERENCE)
                        && command.mimeType().equals("audio/webm")
                        && command.languageHint() == null
                        && command.meetingDurationMs() == JOB.durationMs()));
    }

    @Test
    void laterStageFailurePreservesSuccessfullySavedDiarizationResult() {
        List<Speaker> speakers = List.of(new Speaker("speaker_a", "Speaker A"));
        List<TranscriptSegment> transcript = List.of(
                new TranscriptSegment("seg_001", "speaker_a", 10, 200, "normalized text"));
        sessionStore.recordProcessingProgress(SESSION_ID, "DIARIZATION", 67);
        sessionStore.saveDiarizationResult(SESSION_ID, speakers, transcript);

        MeetingSession failed = sessionStore.failProcessing(
                SESSION_ID, "MINUTES", 100, "MINUTES_GENERATION_FAILED", true);

        assertThat(failed.status()).isEqualTo("PROCESSING_FAILED");
        assertThat(failed.processingStage()).isEqualTo("MINUTES");
        assertThat(failed.progressPercent()).isEqualTo(100);
        assertThat(failed.processingFailure().errorCode()).isEqualTo("MINUTES_GENERATION_FAILED");
        assertThat(failed.processingFailure().retryable()).isTrue();
        assertThat(failed.speakers()).containsExactlyElementsOf(speakers);
        assertThat(failed.transcript()).containsExactlyElementsOf(transcript);
    }

    @Test
    void assemblyFailureStopsLaterStagesAndStoresSafeFailureAtZeroProgress() {
        when(audioAssembly.assemble(JOB, POLICY, TRACE_ID))
                .thenThrow(new AudioAssemblyException("AUDIO_ASSEMBLY_CHUNKS_INCOMPLETE"));

        pipeline.process(JOB, TRACE_ID);

        MeetingSession failed = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(failed.status()).isEqualTo("PROCESSING_FAILED");
        assertThat(failed.processingStage()).isEqualTo("AUDIO_ASSEMBLY");
        assertThat(failed.progressPercent()).isZero();
        assertThat(failed.processingFailure().errorCode()).isEqualTo("AUDIO_ASSEMBLY_CHUNKS_INCOMPLETE");
        assertThat(failed.processingFailure().retryable()).isFalse();
        assertThat(failed.speakers()).isEmpty();
        assertThat(failed.transcript()).isEmpty();
        verify(transcriptionProvider, never()).transcribeWithDiarization(any());
        verify(normalizer, never()).normalize(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void transcriptionFailureKeepsCompletedAssemblyProgressAndDoesNotNormalize() {
        when(audioAssembly.assemble(JOB, POLICY, TRACE_ID)).thenReturn(assembly());
        when(transcriptionProvider.transcribeWithDiarization(any(TranscriptionCommand.class)))
                .thenThrow(new TranscriptionException(TranscriptionException.Code.PROCESSING_FAILED, true));

        pipeline.process(JOB, TRACE_ID);

        MeetingSession failed = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(failed.status()).isEqualTo("PROCESSING_FAILED");
        assertThat(failed.processingStage()).isEqualTo("TRANSCRIPTION");
        assertThat(failed.progressPercent()).isEqualTo(33);
        assertThat(failed.processingFailure().errorCode()).isEqualTo("TRANSCRIPTION_PROCESSING_FAILED");
        assertThat(failed.processingFailure().retryable()).isTrue();
        verify(normalizer, never()).normalize(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void invalidDiarizationOutputFailsWithoutSavingAnyPartialReviewDataOrRawError() {
        ProviderTranscriptionResponse response = providerResponse();
        when(audioAssembly.assemble(JOB, POLICY, TRACE_ID)).thenReturn(assembly());
        when(transcriptionProvider.transcribeWithDiarization(any(TranscriptionCommand.class))).thenReturn(response);
        when(normalizer.normalize(response, JOB.durationMs()))
                .thenThrow(new IllegalArgumentException("private provider transcript response"));

        ListAppender<ILoggingEvent> appender = capturePipelineLogs();
        pipeline.process(JOB, TRACE_ID);

        MeetingSession failed = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(failed.status()).isEqualTo("PROCESSING_FAILED");
        assertThat(failed.processingStage()).isEqualTo("DIARIZATION");
        assertThat(failed.progressPercent()).isEqualTo(66);
        assertThat(failed.processingFailure().errorCode()).isEqualTo("DIARIZATION_INVALID_RESULT");
        assertThat(failed.processingFailure().retryable()).isFalse();
        assertThat(failed.speakers()).isEmpty();
        assertThat(failed.transcript()).isEmpty();
        String logs = loggedContent(appender);
        assertThat(logs).contains(SESSION_ID, TRACE_ID, "DIARIZATION", "DIARIZATION_INVALID_RESULT");
        assertThat(logs).doesNotContain("private provider transcript response", "provider-speaker", "secret-key");
    }

    private ListAppender<ILoggingEvent> capturePipelineLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProcessingPipeline.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static String loggedContent(ListAppender<ILoggingEvent> appender) {
        StringBuilder content = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            content.append(event.getFormattedMessage()).append(event.getKeyValuePairs());
        }
        appender.stop();
        return content.toString();
    }

    private static MeetingSession uploadingSession() {
        return new MeetingSession(SESSION_ID, 1, "UPLOADING", 1,
                new MeetingSession.Meeting("Planning", "default.md", List.of("participant-1"), "Asia/Seoul"));
    }

    private static AudioAssemblyStorage.Assembly assembly() {
        return new AudioAssemblyStorage.Assembly(AUDIO_REFERENCE, "audio/webm", 42, "a".repeat(64));
    }

    private static ProviderTranscriptionResponse providerResponse() {
        return new ProviderTranscriptionResponse(
                List.of(new ProviderTranscriptionResponse.Speaker("provider-speaker")),
                List.of(new ProviderTranscriptionResponse.Segment(
                        "provider-segment", "provider-speaker", 10, 200, "private provider transcript")));
    }
}

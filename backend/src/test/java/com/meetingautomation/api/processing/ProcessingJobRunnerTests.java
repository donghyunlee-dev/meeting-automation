package com.meetingautomation.api.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStatusResponse;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.MeetingSessionStatusService;
import com.meetingautomation.api.session.UploadPolicy;
import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.TranscriptSegment;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcessingJobRunnerTests {
    private static final String SESSION_ID = "ms_processing-runner-test";
    private static final ProcessingJob JOB = new ProcessingJob(SESSION_ID, 1, 1_000, "audio/webm");

    private final MeetingSessionStore sessionStore = new MeetingSessionStore();
    private final ProcessingPipeline pipeline = mock(ProcessingPipeline.class);
    private final ProcessingJobRunner runner = new ProcessingJobRunner(sessionStore, pipeline);

    @BeforeEach
    void createProcessingSession() {
        sessionStore.remove(SESSION_ID);
        sessionStore.save(new MeetingSession(SESSION_ID, 1, "UPLOADING", 1,
                new MeetingSession.Meeting("Planning", "default.md", List.of("participant-1"), "Asia/Seoul")),
                null, new UploadPolicy(15, 128, List.of("audio/webm")));
        sessionStore.startProcessing(SESSION_ID);
    }

    @Test
    void concurrentDuplicateDeliveryRunsPipelineOnlyOnceForSession() throws Exception {
        CountDownLatch pipelineEntered = new CountDownLatch(1);
        CountDownLatch allowPipelineToFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            sessionStore.recordProcessingProgress(SESSION_ID, "TRANSCRIPTION", 34);
            pipelineEntered.countDown();
            assertThat(allowPipelineToFinish.await(5, TimeUnit.SECONDS)).isTrue();
            sessionStore.recordProcessingProgress(SESSION_ID, "DIARIZATION", 67);
            sessionStore.saveDiarizationResult(SESSION_ID,
                    List.of(new Speaker("speaker_a", "Speaker A")),
                    List.of(new TranscriptSegment("seg_001", "speaker_a", 10, 20, "one result")));
            return null;
        }).when(pipeline).process(any(ProcessingJob.class), anyString());

        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<?> first = executor.submit(() -> runner.run(JOB));
            assertThat(pipelineEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> duplicate = executor.submit(() -> runner.run(JOB));
            Future<MeetingSessionStatusResponse> statusRead = executor.submit(
                    () -> new MeetingSessionStatusService(sessionStore).get(SESSION_ID));
            MeetingSessionStatusResponse status = statusRead.get(2, TimeUnit.SECONDS);
            assertThat(status.data().processing().stage()).isEqualTo("TRANSCRIPTION");
            assertThat(status.data().processing().progressPercent()).isEqualTo(34);
            assertThat(duplicate.get(2, TimeUnit.SECONDS)).isNull();
            allowPipelineToFinish.countDown();
            first.get(5, TimeUnit.SECONDS);
            duplicate.get(5, TimeUnit.SECONDS);
        } finally {
            allowPipelineToFinish.countDown();
        }

        verify(pipeline).process(any(ProcessingJob.class), anyString());
        MeetingSession completed = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(completed.transcript()).hasSize(1);
        assertThat(completed.processingStage()).isEqualTo("DIARIZATION");
        assertThat(completed.progressPercent()).isEqualTo(100);
    }

    @Test
    void skipsDuplicateDeliveryAfterSessionHasFailedAndMissingSession() {
        sessionStore.failProcessing(SESSION_ID, "AUDIO_ASSEMBLY", 0,
                "AUDIO_ASSEMBLY_FAILED", false);

        runner.run(JOB);
        runner.run(new ProcessingJob("ms_missing-runner-session", 1, 1_000, "audio/webm"));

        verify(pipeline, never()).process(any(ProcessingJob.class), anyString());
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("PROCESSING_FAILED");
    }

    @Test
    void inMemoryQueueReturnsWhileTheRunnerIsStillProcessing() throws Exception {
        CountDownLatch pipelineEntered = new CountDownLatch(1);
        CountDownLatch allowPipelineToFinish = new CountDownLatch(1);
        CountDownLatch pipelineFinished = new CountDownLatch(1);
        doAnswer(invocation -> {
            pipelineEntered.countDown();
            try {
                assertThat(allowPipelineToFinish.await(5, TimeUnit.SECONDS)).isTrue();
                sessionStore.recordProcessingProgress(SESSION_ID, "DIARIZATION", 67);
                sessionStore.saveDiarizationResult(SESSION_ID,
                        List.of(new Speaker("speaker_a", "Speaker A")),
                        List.of(new TranscriptSegment("seg_001", "speaker_a", 10, 20, "queued result")));
            } finally {
                pipelineFinished.countDown();
            }
            return null;
        }).when(pipeline).process(any(ProcessingJob.class), anyString());

        try (var worker = Executors.newSingleThreadExecutor(); var caller = Executors.newSingleThreadExecutor()) {
            InMemoryProcessingJobQueue queue = new InMemoryProcessingJobQueue(runner, worker);
            Future<?> enqueue = caller.submit(() -> queue.enqueue(JOB));
            assertThat(pipelineEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(enqueue.isDone()).isTrue();
            allowPipelineToFinish.countDown();
            enqueue.get(5, TimeUnit.SECONDS);
            assertThat(pipelineFinished.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            allowPipelineToFinish.countDown();
        }

        assertThat(sessionStore.find(SESSION_ID).orElseThrow().transcript())
                .containsExactly(new TranscriptSegment("seg_001", "speaker_a", 10, 20, "queued result"));
    }
}

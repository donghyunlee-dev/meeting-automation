package com.meetingautomation.document;

import static org.junit.jupiter.api.Assertions.*;
import com.meetingautomation.document.settings.*;
import com.meetingautomation.DocumentSettingsFixtures;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobalSettingsTests {
    @TempDir(factory = com.meetingautomation.OutsideRepositoryTempDirectory.class) Path directory;
    EncryptedFileGlobalSettingsStore store() { return new EncryptedFileGlobalSettingsStore(directory.toString(), DocumentSettingsFixtures.KEY, false); }
    GlobalSettingsUseCase useCase() { return new GlobalSettingsUseCase(store(), draft -> {}); }
    static DocumentDraftInput notion(String token) {
        return new DocumentDraftInput(" notion ", Map.of("parentPageId", "https://www.notion.so/Parent-aaaaaaaabbbbccccddddeeeeeeeeeeee"), Map.of("token", token), null);
    }
    static DocumentDraftInput confluence() {
        return new DocumentDraftInput("CONFLUENCE", Map.of("baseUrl", "https://TEAM.atlassian.net/", "spaceId", "100", "parentPageId", "101"),
            Map.of("accountEmail", " Synthetic@Example.Test ", "apiToken", "synthetic-atlassian-secret"), null);
    }
    @Test void setupPersistRestoresNormalizedCredentialsRevisionAndDurableReplay() throws Exception {
        var first = useCase().saveDraft(0, "key-1", notion("synthetic-notion-secret"));
        var restored = store().read();
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", restored.draft().location().get("parentPageId"));
        assertEquals("synthetic-notion-secret", restored.draft().credentials().get("token"));
        assertEquals(first, useCase().saveDraft(0, "key-1", notion("synthetic-notion-secret")));
        useCase().saveDraft(1, "key-2", confluence());
        var next = store().read();
        assertEquals(2, next.draft().revision());
        assertEquals("https://team.atlassian.net", next.draft().location().get("baseUrl"));
        assertEquals("synthetic@example.test", next.draft().credentials().get("accountEmail"));
        assertEquals(first, useCase().saveDraft(0, "key-1", notion("synthetic-notion-secret")));
        String file = Files.readString(directory.resolve("settings.enc"));
        assertFalse(file.contains("synthetic"));
        assertFalse(file.contains("example.test"));
        assertFalse(file.contains(next.draft().draftId()));
        assertFalse(next.toString().contains("synthetic"));
    }
    @Test void setupConcurrencyOnlyOneSameVersionMutationCommitsAcrossStoreInstances() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                String key = "request-" + i;
                results.add(executor.submit(() -> { start.await(); try { useCase().saveDraft(0, key, notion("secret")); return 201; }
                    catch (SettingsException failure) { return failure.status(); } }));
            }
            start.countDown();
            List<Integer> codes = List.of(results.get(0).get(), results.get(1).get());
            assertTrue(codes.containsAll(List.of(201, 412)));
            assertEquals(1, store().read().version());
        }
    }
    @Test void setupStorageCorruptionPreservesFileAndRefusesReadAndMutation() throws Exception {
        useCase().saveDraft(0, "key", notion("secret"));
        Path file = directory.resolve("settings.enc");
        Files.writeString(file, "corrupted-snapshot");
        assertEquals("STORAGE_UNAVAILABLE", useCase().status().status());
        assertEquals(503, assertThrows(SettingsException.class, () -> useCase().saveDraft(0, "new", notion("secret"))).status());
        assertEquals("corrupted-snapshot", Files.readString(file));
        assertNull(useCase().status().version());
    }
    @Test void setupStorageMissingWrongKeyAndUnavailableDirectoryAreNeverEmpty() throws Exception {
        useCase().saveDraft(0, "key", confluence());
        byte[] before = Files.readAllBytes(directory.resolve("settings.enc"));
        for (String key : List.of("", "not-base64", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=")) {
            var broken = new EncryptedFileGlobalSettingsStore(directory.toString(), key, false);
            assertThrows(SettingsException.class, broken::read);
        }
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("settings.enc")));
        var missing = new EncryptedFileGlobalSettingsStore(directory.resolve("missing").toString(), DocumentSettingsFixtures.KEY, false);
        assertThrows(SettingsException.class, missing::read);
        var fileDirectory = new EncryptedFileGlobalSettingsStore(directory.resolve("settings.enc").toString(), DocumentSettingsFixtures.KEY, false);
        assertThrows(SettingsException.class, fileDirectory::read);
    }
    @Test void setupStorageTransactionFailurePreservesPriorSnapshot() throws Exception {
        useCase().saveDraft(0, "key", notion("secret"));
        byte[] before = Files.readAllBytes(directory.resolve("settings.enc"));
        // The previous snapshot is replaced only at the atomic commit point.
        assertThrows(IllegalStateException.class, () -> store().transaction(current -> { throw new IllegalStateException("stop-before-save"); }));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("settings.enc")));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
    @Test void setupStorageCommitFailurePreservesPriorSnapshotAndRejectsSave() throws Exception {
        useCase().saveDraft(0, "draft", notion("secret"));
        byte[] before = Files.readAllBytes(directory.resolve("settings.enc"));
        var failedStore = new EncryptedFileGlobalSettingsStore(directory.toString(), DocumentSettingsFixtures.KEY, false,
            (path, bytes) -> { throw new java.io.IOException("synthetic-disk-failure-secret"); });
        var useCase = new GlobalSettingsUseCase(failedStore, draft -> {});
        var failure = assertThrows(SettingsException.class, () -> useCase.saveDraft(1, "edit", confluence()));
        assertEquals("DOCUMENT_SETTINGS_UNAVAILABLE", failure.code());
        assertFalse(failure.getMessage().contains("synthetic"));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("settings.enc")));
        assertEquals(1, store().read().version());
    }
    @Test void setupStorageRenderRootFilesystemIsRejected() {
        var ephemeral = new EncryptedFileGlobalSettingsStore(directory.toString(), DocumentSettingsFixtures.KEY, true);
        assertThrows(SettingsException.class, ephemeral::read);
    }
    @Test void setupTestReadOnlyResultExpiresInTenMinutesAndDraftChangeInvalidatesIt() {
        Instant now = Instant.parse("2026-10-10T00:00:00Z");
        var useCase = new GlobalSettingsUseCase(store(), draft -> {}, Clock.fixed(now, ZoneOffset.UTC));
        useCase.saveDraft(0, "draft", notion("secret"));
        var draft = store().read().draft();
        var result = useCase.testDraft(1, "test", draft.draftId(), 1);
        assertEquals("TESTED", store().read().draft().state());
        assertEquals(now.plusSeconds(600), store().read().draft().testResult().expiresAt());
        assertEquals("UNVERIFIED", store().read().draft().testResult().writeCapability());
        assertEquals(result, useCase.testDraft(1, "test", draft.draftId(), 1));
        useCase.saveDraft(2, "edit", notion("changed-secret"));
        assertEquals("DRAFT", store().read().draft().state());
        assertNull(store().read().draft().testResult());
        assertEquals(412, assertThrows(SettingsException.class, () -> useCase.testDraft(3, "stale-test", draft.draftId(), 1)).status());
    }
    @Test void setupTestFailureDoesNotChangeActiveDraftRevisionOrVersion() throws Exception {
        useCase().saveDraft(0, "draft", notion("secret"));
        byte[] before = Files.readAllBytes(directory.resolve("settings.enc"));
        var useCase = new GlobalSettingsUseCase(store(), draft -> { throw DocumentProviderException.documentFailed(false); });
        assertThrows(DocumentProviderException.class, () -> useCase.testDraft(1, "test", store().read().draft().draftId(), 1));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("settings.enc")));
    }
    @Test void setupTestProviderWaitDoesNotBlockSettingsStatusOrStoreReads() throws Exception {
        useCase().saveDraft(0, "draft", notion("secret"));
        String draftId = store().read().draft().draftId();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var settings = new GlobalSettingsUseCase(store(), draft -> awaitProvider(entered, release));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var testing = executor.submit(() -> settings.testDraft(1, "test", draftId, 1));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS), "Provider verification did not start");
                var reading = executor.submit(() -> List.of(settings.status().version(), store().read().version()));
                assertEquals(List.of(1L, 1L), reading.get(2, TimeUnit.SECONDS));
            } finally { release.countDown(); }
            assertEquals(200, testing.get(5, TimeUnit.SECONDS).status());
        }
    }
    @Test void setupTestConcurrentDraftChangeRejectsStaleVerificationWithoutSavingTestedState() throws Exception {
        useCase().saveDraft(0, "draft", notion("secret"));
        String draftId = store().read().draft().draftId();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var settings = new GlobalSettingsUseCase(store(), draft -> awaitProvider(entered, release));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var testing = executor.submit(() -> settings.testDraft(1, "test", draftId, 1));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var editing = executor.submit(() -> useCase().saveDraft(1, "edit", confluence()));
                assertEquals(201, editing.get(2, TimeUnit.SECONDS).status());
            } finally { release.countDown(); }
            var error = assertThrows(ExecutionException.class, () -> testing.get(5, TimeUnit.SECONDS));
            assertEquals(412, ((SettingsException) error.getCause()).status());
            assertEquals(2, store().read().version());
            assertEquals("CONFLUENCE", store().read().draft().provider());
            assertEquals("DRAFT", store().read().draft().state());
            assertNull(store().read().draft().testResult());
            assertFalse(store().read().idempotencyRecords().containsKey("test"));
        }
    }
    @Test void setupTestConcurrentSameKeyReturnsOneDurableResult() throws Exception {
        useCase().saveDraft(0, "draft", notion("secret"));
        String draftId = store().read().draft().draftId();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var settings = new GlobalSettingsUseCase(store(), draft -> awaitProvider(entered, release));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> settings.testDraft(1, "test", draftId, 1));
            var second = executor.submit(() -> settings.testDraft(1, "test", draftId, 1));
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            var result = first.get(5, TimeUnit.SECONDS);
            assertEquals(result, second.get(5, TimeUnit.SECONDS));
            assertEquals(2, store().read().version());
            assertEquals(result, useCase().testDraft(1, "test", draftId, 1));
        }
    }
    @Test void setupTestDraftExpiryDuringProviderCallRejectsVerification() throws Exception {
        var time = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-10T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var settings = new GlobalSettingsUseCase(store(), draft -> awaitProvider(entered, release), clock);
        settings.saveDraft(0, "draft", notion("secret"));
        String draftId = store().read().draft().draftId();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var testing = executor.submit(() -> settings.testDraft(1, "test", draftId, 1));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                time.updateAndGet(now -> now.plusSeconds(86401));
            } finally { release.countDown(); }
            var error = assertThrows(ExecutionException.class, () -> testing.get(5, TimeUnit.SECONDS));
            assertEquals(412, ((SettingsException) error.getCause()).status());
            assertEquals(1, store().read().version());
            assertNull(store().read().draft().testResult());
            assertFalse(store().read().idempotencyRecords().containsKey("test"));
        }
    }
    @Test void setupTestProviderReceivesAnImmutableDraftSnapshot() {
        useCase().saveDraft(0, "draft", notion("secret"));
        String draftId = store().read().draft().draftId();
        var settings = new GlobalSettingsUseCase(store(), draft -> {
            assertThrows(UnsupportedOperationException.class, () -> draft.credentials().put("token", "mutated"));
            assertThrows(UnsupportedOperationException.class, () -> draft.location().put("parentPageId", "mutated"));
        });
        assertEquals(200, settings.testDraft(1, "test", draftId, 1).status());
        assertEquals("secret", store().read().draft().credentials().get("token"));
    }
    private static void awaitProvider(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Provider test was not released");
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    @Test void setupSecretExpiredDraftCredentialsAreRemovedDurably() {
        Instant now = Instant.parse("2026-10-10T00:00:00Z");
        new GlobalSettingsUseCase(store(), draft -> {}, Clock.fixed(now, ZoneOffset.UTC)).saveDraft(0, "draft", notion("expired-secret"));
        var later = new GlobalSettingsUseCase(store(), draft -> {}, Clock.fixed(now.plusSeconds(86401), ZoneOffset.UTC));
        assertEquals("UNCONFIGURED", later.status().status());
        assertNull(store().read().draft());
    }
    @Test void setupSecretRejectsUrlCredentialsPrivateHostsAndMalformedProviderIds() {
        for (String base : List.of("http://site.atlassian.net", "https://user:password@site.atlassian.net", "https://127.0.0.1", "https://192.168.1.1", "https://site.atlassian.net:443", "https://site.atlassian.net/path", "https://site.atlassian.net.evil.test")) {
            assertThrows(SettingsException.class, () -> new DocumentDraftInput("CONFLUENCE", Map.of("baseUrl", base, "spaceId", "1"), confluence().credentials(), null).normalized());
        }
        assertThrows(SettingsException.class, () -> new DocumentDraftInput("OTHER", Map.of(), Map.of(), null).normalized());
        assertThrows(SettingsException.class, () -> new DocumentDraftInput("NOTION", Map.of("parentPageId", "https://user:pass@www.notion.so/aaaaaaaabbbbccccddddeeeeeeeeeeee"), Map.of("token", "secret"), null).normalized());
    }
}

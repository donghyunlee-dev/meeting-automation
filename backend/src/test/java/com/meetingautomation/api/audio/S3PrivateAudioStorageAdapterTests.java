package com.meetingautomation.api.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

class S3PrivateAudioStorageAdapterTests {
    private static final String BUCKET = "private-audio";
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private static final String SESSION = "ms_synthetic-session";

    @Test
    void storeUsesPrivateOpaqueObjectKeyAndPersistsExpiryMetadata() {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag").build());
        S3PrivateAudioStorageAdapter storage = storage(client);

        PrivateAudioStoragePort.StoredObject stored = storage.store(
                SESSION, PrivateAudioStoragePort.ObjectType.CHUNK, 3, "audio/webm",
                NOW.plusSeconds(86400), new ByteArrayInputStream(new byte[] {1, 2, 3}),
                3, "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");

        assertThat(stored.objectId()).doesNotContain(SESSION).matches("[a-f0-9-]{36}");
        assertThat(stored.sessionId()).isEqualTo(SESSION);
        assertThat(stored.expiresAt()).isEqualTo(NOW.plusSeconds(86400));
        org.mockito.ArgumentCaptor<PutObjectRequest> request = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).doesNotContain(SESSION);
        assertThat(request.getValue().metadata()).containsEntry("session-id", SESSION)
                .containsEntry("expires-at", NOW.plusSeconds(86400).toString())
                .containsEntry("object-type", "chunk");
        assertThat(request.getValue().acl()).isNull();
    }

    @Test
    void activeRecordingUploadHasNoExpiryUntilProcessingFailure() {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag").build());
        S3PrivateAudioStorageAdapter storage = storage(client);

        var stored = storage.store(SESSION, PrivateAudioStoragePort.ObjectType.CHUNK, 0, "audio/webm",
                null, new ByteArrayInputStream(new byte[] {1, 2, 3}), 3,
                "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");

        assertThat(stored.expiresAt()).isNull();
        org.mockito.ArgumentCaptor<PutObjectRequest> request = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
        assertThat(request.getValue().metadata()).doesNotContainKey("expires-at");
    }

    @Test
    void openUsesPrivateBucketAndOpaqueHandleWithoutReturningProviderUrl() throws java.io.IOException {
        S3Client client = mock(S3Client.class);
        byte[] bytes = {4, 5, 6};
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(
                new ResponseInputStream<>(GetObjectResponse.builder().build(), new ByteArrayInputStream(bytes)));
        S3PrivateAudioStorageAdapter storage = storage(client);

        byte[] result = storage.open("00000000-0000-0000-0000-000000000001").readAllBytes();

        assertThat(result).containsExactly(bytes);
        org.mockito.ArgumentCaptor<GetObjectRequest> request = org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).contains("00000000-0000-0000-0000-000000000001");
        assertThat(storage.toString()).doesNotContain("https://");
    }

    @Test
    void expiredObjectListingUsesDurableObjectMetadataAndDeleteIsIdempotent() {
        S3Client client = mock(S3Client.class);
        String objectId = "00000000-0000-0000-0000-000000000002";
        when(client.listObjectsV2(any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(S3Object.builder()
                        .key("meeting-audio/" + objectId).build()).isTruncated(false).build());
        when(client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentType("audio/webm").contentLength(3L)
                        .metadata(Map.of("session-id", SESSION, "object-type", "assembled", "sequence", "0",
                                "expires-at", NOW.toString(), "sha256", "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                                "bytes", "3")).build());
        S3PrivateAudioStorageAdapter storage = storage(client);

        List<PrivateAudioStoragePort.StoredObject> expired = storage.listExpired(NOW);
        storage.delete(objectId);
        storage.delete(objectId);

        assertThat(expired).singleElement().satisfies(object -> {
            assertThat(object.objectId()).isEqualTo(objectId);
            assertThat(object.sessionId()).isEqualTo(SESSION);
            assertThat(object.type()).isEqualTo(PrivateAudioStoragePort.ObjectType.ASSEMBLED);
            assertThat(object.expiresAt()).isEqualTo(NOW);
        });
        org.mockito.ArgumentCaptor<software.amazon.awssdk.services.s3.model.DeleteObjectRequest> request =
                org.mockito.ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class);
        verify(client, org.mockito.Mockito.times(2)).deleteObject(request.capture());
        assertThat(request.getAllValues()).allSatisfy(value -> {
            assertThat(value.bucket()).isEqualTo(BUCKET);
            assertThat(value.key()).isEqualTo("meeting-audio/" + objectId);
        });
    }

    private static S3PrivateAudioStorageAdapter storage(S3Client client) {
        return new S3PrivateAudioStorageAdapter(client, BUCKET,
                Clock.fixed(NOW, ZoneOffset.UTC), "meeting-audio");
    }
}

package com.meetingautomation.api.audio;

import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** S3-compatible implementation. All provider keys stay inside this adapter; no ACL/public URL is created. */
public final class S3PrivateAudioStorageAdapter implements PrivateAudioStoragePort {
    private static final String SESSION_ID = "session-id";
    private static final String OBJECT_TYPE = "object-type";
    private static final String SEQUENCE = "sequence";
    private static final String EXPIRES_AT = "expires-at";
    private static final String SHA256 = "sha256";
    private static final String BYTES = "bytes";

    private final S3Client client;
    private final String bucket;
    private final Clock clock;
    private final String prefix;

    S3PrivateAudioStorageAdapter(S3Client client, String bucket, Clock clock, String prefix) {
        if (client == null || bucket == null || bucket.isBlank() || clock == null
                || prefix == null || prefix.isBlank()) throw new IllegalArgumentException("Private Audio storage is invalid.");
        this.client = client;
        this.bucket = bucket;
        this.clock = clock;
        this.prefix = prefix.replaceAll("/+$", "");
    }

    @Override
    public StoredObject store(String sessionId, ObjectType type, int sequence, String mimeType, Instant expiresAt,
            InputStream body, long bytes, String sha256) {
        if (sessionId == null || sessionId.isBlank() || type == null || sequence < 0
                || mimeType == null || mimeType.isBlank()
                || (expiresAt != null && !expiresAt.isAfter(clock.instant()))
                || body == null || bytes < 1 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new AudioStorageException();
        }
        String objectId = UUID.randomUUID().toString();
        Map<String, String> metadata = new java.util.HashMap<>(Map.of(
                SESSION_ID, sessionId,
                OBJECT_TYPE, type.name().toLowerCase(Locale.ROOT),
                SEQUENCE, Integer.toString(sequence),
                SHA256, sha256,
                BYTES, Long.toString(bytes)));
        if (expiresAt != null) metadata.put(EXPIRES_AT, expiresAt.toString());
        try {
            VerifyingInputStream verifiedBody = new VerifyingInputStream(body, bytes, sha256);
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key(objectId))
                    .contentType(mimeType).metadata(metadata).build(), RequestBody.fromInputStream(verifiedBody, bytes));
            verifiedBody.verifyComplete();
            return new StoredObject(objectId, sessionId, type, sequence, mimeType, bytes, sha256, expiresAt);
        } catch (RuntimeException failure) {
            try { client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(objectId)).build()); }
            catch (RuntimeException ignored) { /* a later expiry sweep removes an unconfirmed object */ }
            throw new AudioStorageException();
        } catch (InvalidAudioUploadStreamException invalid) {
            try { client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(objectId)).build()); }
            catch (RuntimeException ignored) { /* a later expiry sweep removes an unconfirmed object */ }
            throw new AudioChunkInvalidException();
        } catch (IOException failure) {
            try { client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(objectId)).build()); }
            catch (RuntimeException ignored) { /* a later expiry sweep removes an unconfirmed object */ }
            throw new AudioStorageException();
        }
    }

    @Override
    public InputStream open(String objectId) {
        try {
            return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key(objectId)).build());
        } catch (RuntimeException failure) {
            throw new AudioStorageException();
        }
    }

    @Override
    public Optional<StoredObject> find(String objectId) {
        try {
            var response = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key(objectId)).build());
            return Optional.of(from(objectId, response.metadata(), response.contentType(), response.contentLength()));
        } catch (S3Exception missing) {
            if (missing.statusCode() == 404) return Optional.empty();
            throw new AudioStorageException();
        } catch (RuntimeException failure) {
            throw new AudioStorageException();
        }
    }

    @Override
    public List<StoredObject> listForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new AudioStorageException();
        return listAll().stream().filter(object -> object.sessionId().equals(sessionId)).toList();
    }

    @Override
    public List<StoredObject> listExpired(Instant now) {
        if (now == null) throw new AudioStorageException();
        return listAll().stream().filter(object -> object.expiresAt() != null
                && !object.expiresAt().isAfter(now)).toList();
    }

    @Override
    public void extendExpiry(String objectId, Instant expiresAt) {
        StoredObject object = find(objectId).orElseThrow(AudioStorageException::new);
        String key = key(objectId);
        Map<String, String> metadata = metadata(object, expiresAt);
        try {
            client.copyObject(CopyObjectRequest.builder().sourceBucket(bucket).sourceKey(key)
                    .destinationBucket(bucket).destinationKey(key).metadataDirective(MetadataDirective.REPLACE)
                    .metadata(metadata).contentType(object.mimeType()).build());
        } catch (RuntimeException failure) {
            throw new AudioStorageException();
        }
    }

    @Override
    public void delete(String objectId) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(objectId)).build());
        } catch (RuntimeException failure) {
            throw new AudioStorageException();
        }
    }

    private List<StoredObject> listAll() {
        List<StoredObject> results = new ArrayList<>();
        String token = null;
        try {
            do {
                var page = client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix + "/")
                        .continuationToken(token).build());
                for (var item : page.contents()) {
                    String objectId = item.key().substring((prefix + "/").length());
                    if (!objectId.matches("[a-f0-9-]{36}")) continue;
                    var head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(item.key()).build());
                    results.add(from(objectId, head.metadata(), head.contentType(), head.contentLength()));
                }
                token = page.isTruncated() ? page.nextContinuationToken() : null;
            } while (token != null);
            return List.copyOf(results);
        } catch (RuntimeException failure) {
            throw new AudioStorageException();
        }
    }

    private StoredObject from(String objectId, Map<String, String> metadata, String mimeType, Long contentLength) {
        try {
            String sessionId = metadata.get(SESSION_ID);
            ObjectType type = ObjectType.valueOf(metadata.get(OBJECT_TYPE).toUpperCase(Locale.ROOT));
            int sequence = Integer.parseInt(metadata.get(SEQUENCE));
            long bytes = Long.parseLong(metadata.get(BYTES));
            String sha = metadata.get(SHA256);
            Instant expiry = metadata.get(EXPIRES_AT) == null ? null : Instant.parse(metadata.get(EXPIRES_AT));
            if (sessionId == null || sequence < 0 || bytes < 1 || sha == null || !sha.matches("[0-9a-f]{64}")
                    || contentLength == null || contentLength != bytes || mimeType == null || mimeType.isBlank()) {
                throw new IllegalArgumentException();
            }
            return new StoredObject(objectId, sessionId, type, sequence, mimeType, bytes, sha, expiry);
        } catch (RuntimeException invalidMetadata) {
            throw new AudioStorageException();
        }
    }

    private static Map<String, String> metadata(StoredObject object, Instant expiresAt) {
        Map<String, String> metadata = new java.util.HashMap<>(Map.of(SESSION_ID, object.sessionId(),
                OBJECT_TYPE, object.type().name().toLowerCase(Locale.ROOT), SEQUENCE,
                Integer.toString(object.sequence()), SHA256, object.sha256(), BYTES,
                Long.toString(object.bytes())));
        if (expiresAt != null) metadata.put(EXPIRES_AT, expiresAt.toString());
        return metadata;
    }

    private String key(String objectId) {
        try {
            return prefix + "/" + UUID.fromString(objectId);
        } catch (RuntimeException invalid) {
            throw new AudioStorageException();
        }
    }

    private static final class VerifyingInputStream extends FilterInputStream {
        private final long expectedBytes;
        private final String expectedSha256;
        private final MessageDigest digest;
        private long count;
        private boolean verified;

        private VerifyingInputStream(InputStream input, long expectedBytes, String expectedSha256) {
            super(input);
            this.expectedBytes = expectedBytes;
            this.expectedSha256 = expectedSha256;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) update(new byte[] {(byte) value}, 0, 1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) update(bytes, offset, read);
            return read;
        }

        private void update(byte[] bytes, int offset, int length) throws IOException {
            count += length;
            if (count > expectedBytes) throw new IOException("Audio upload length mismatch.");
            digest.update(bytes, offset, length);
        }

        private void verifyComplete() throws IOException {
            byte[] buffer = new byte[8192];
            while (read(buffer) >= 0) { }
            if (count != expectedBytes || !HexFormat.of().formatHex(digest.digest()).equals(expectedSha256)) {
                throw new InvalidAudioUploadStreamException();
            }
            verified = true;
        }

        @Override
        public void close() throws IOException {
            try {
                if (!verified) verifyComplete();
            } finally {
                super.close();
            }
        }
    }

    private static final class InvalidAudioUploadStreamException extends IOException { }
}

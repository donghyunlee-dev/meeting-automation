package com.meetingautomation.api.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Private Vercel Blob adapter. Audio bytes stay private and provider operations are delegated to Vercel Functions. */
public final class VercelBlobPrivateAudioStorageAdapter implements PrivateAudioStoragePort, ClientUploadedAudioStoragePort, ExpiredAudioStoragePort {
    private static final int PART_SIZE = 4 * 1024 * 1024;
    private final HttpClient client;
    private final ObjectMapper json;
    private final URI controlUrl;
    private final String secret;
    private final Clock clock;

    public VercelBlobPrivateAudioStorageAdapter(HttpClient client, ObjectMapper json, URI controlUrl, String secret, Clock clock) {
        if (client == null || json == null || controlUrl == null || secret == null || secret.isBlank() || clock == null) {
            throw new IllegalArgumentException("Vercel Blob storage configuration is invalid.");
        }
        this.client = client;
        this.json = json;
        this.controlUrl = controlUrl;
        this.secret = secret;
        this.clock = clock;
    }

    @Override
    public StoredObject store(String sessionId, ObjectType type, int sequence, String mimeType, Instant expiresAt,
            InputStream body, long bytes, String sha256) {
        validate(sessionId, type, sequence, mimeType, body, bytes, sha256);
        String uploadId = UUID.randomUUID().toString();
        String objectId = objectPath(sessionId, type, sequence);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            int partCount = 0;
            byte[] buffer = new byte[PART_SIZE];
            while (true) {
                int read = readPart(body, buffer);
                if (read == 0) break;
                count += read;
                if (count > bytes) throw new AudioChunkInvalidException();
                digest.update(buffer, 0, read);
                byte[] part = java.util.Arrays.copyOf(buffer, read);
                sendPart(uploadId, partCount++, part);
            }
            if (count != bytes || !HexFormat.of().formatHex(digest.digest()).equals(sha256)) throw new AudioChunkInvalidException();
            AudioBlobEntry entry = new AudioBlobEntry(objectId, type.name(), sequence, mimeType, bytes, sha256,
                    expiresAt == null ? null : expiresAt.toString(), true);
            MapResponse response = post(new Request("object:complete", sessionId, entry, null, null, uploadId, partCount));
            if (response.statusCode() >= 300) throw new AudioStorageException();
            return stored(sessionId, entry);
        } catch (AudioChunkInvalidException | AudioStorageException failure) {
            throw failure;
        } catch (IOException | InterruptedException | NoSuchAlgorithmException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AudioStorageException();
        }
    }

    @Override
    public InputStream open(String objectId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(objectUri(objectId)).header("Authorization", "Bearer " + secret).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) { response.body().close(); throw new AudioStorageException(); }
            return response.body();
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AudioStorageException();
        }
    }

    @Override
    public Optional<StoredObject> find(String objectId) {
        String sessionId = sessionFromPath(objectId);
        return manifest(sessionId).stream().filter(item -> item.pathname().equals(objectId)).findFirst().map(item -> stored(sessionId, item));
    }

    @Override
    public void confirmClientUpload(String sessionId, int sequence, String mimeType, long bytes, String sha256) {
        String pathname = objectPath(sessionId, ObjectType.CHUNK, sequence);
        HttpResponse<InputStream> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(objectUri(pathname)).header("Authorization", "Bearer " + secret).GET().build();
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AudioStorageException();
        }
        try (InputStream input = response.body()) {
            long reportedBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            String reportedMime = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() != 200 || reportedBytes != bytes || !reportedMime.equalsIgnoreCase(mimeType)) {
                discardUnconfirmed(sessionId, pathname);
                throw new AudioChunkInvalidException();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                count += read;
                if (count > bytes) {
                    discardUnconfirmed(sessionId, pathname);
                    throw new AudioChunkInvalidException();
                }
                digest.update(buffer, 0, read);
            }
            if (count != bytes || !HexFormat.of().formatHex(digest.digest()).equals(sha256)) {
                discardUnconfirmed(sessionId, pathname);
                throw new AudioChunkInvalidException();
            }
        } catch (AudioChunkInvalidException invalid) {
            throw invalid;
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new AudioStorageException();
        }
        Instant expiresAt = clock.instant().plus(TEMPORARY_LEASE);
        AudioBlobEntry entry = new AudioBlobEntry(pathname, ObjectType.CHUNK.name(), sequence, mimeType,
                bytes, sha256, expiresAt.toString(), true);
        MapResponse manifest = post(new Request("manifest:add", sessionId, entry, null, null, null, null));
        if (manifest.statusCode() >= 300) throw new AudioStorageException();
    }

    private void discardUnconfirmed(String sessionId, String pathname) {
        MapResponse response = post(new Request("object:discardUnconfirmed", sessionId, null, pathname, null, null, null));
        if (response.statusCode() >= 300) throw new AudioStorageException();
    }

    @Override
    public List<StoredObject> listForSession(String sessionId) {
        return manifest(sessionId).stream().filter(item -> item.confirmed()).map(item -> stored(sessionId, item)).toList();
    }

    @Override
    public List<StoredObject> listExpired(Instant now) {
        MapResponse response = post(new Request("manifest:listExpired", null, null, null, now.toString(), null, null));
        if (response.statusCode() >= 300) throw new AudioStorageException();
        try {
            List<ExpiredEntry> entries = json.convertValue(response.body().get("expired"), json.getTypeFactory().constructCollectionType(List.class, ExpiredEntry.class));
            return entries.stream().map(item -> stored(item.sessionId(), item.entry())).toList();
        } catch (RuntimeException failure) { throw new AudioStorageException(); }
    }

    @Override
    public void extendExpiry(String objectId, Instant expiresAt) {
        String sessionId = sessionFromPath(objectId);
        MapResponse response = post(new Request("manifest:extend", sessionId, null, objectId, expiresAt.toString(), null, null));
        if (response.statusCode() >= 300) throw new AudioStorageException();
    }

    @Override
    public void delete(String objectId) {
        String sessionId = sessionFromPath(objectId);
        MapResponse response = post(new Request("object:delete", sessionId, null, objectId, null, null, null));
        if (response.statusCode() >= 300) throw new AudioStorageException();
    }

    @Override
    public boolean deleteIfExpired(String objectId, Instant now) {
        String sessionId = sessionFromPath(objectId);
        MapResponse response = post(new Request("object:deleteExpired", sessionId, null, objectId, now.toString(), null, null));
        if (response.statusCode() >= 300) throw new AudioStorageException();
        return Boolean.TRUE.equals(response.body().get("deleted"));
    }

    private List<AudioBlobEntry> manifest(String sessionId) {
        MapResponse response = post(new Request("manifest:get", sessionId, null, null, null, null, null));
        if (response.statusCode() >= 300) throw new AudioStorageException();
        Object raw = response.body().get("manifest");
        if (raw == null) return List.of();
        try { return json.convertValue(((java.util.Map<?, ?>) raw).get("objects"), json.getTypeFactory().constructCollectionType(List.class, AudioBlobEntry.class)); }
        catch (RuntimeException failure) { throw new AudioStorageException(); }
    }

    private void sendPart(String uploadId, int part, byte[] content) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(controlUrl).header("Authorization", "Bearer " + secret)
                .header("X-Audio-Upload-Id", uploadId).header("X-Audio-Part-Number", Integer.toString(part))
                .header("Content-Type", "application/octet-stream").PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 300) throw new AudioStorageException();
    }

    private MapResponse post(Request payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder(controlUrl).header("Authorization", "Bearer " + secret)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            java.util.Map<String, Object> body = response.body().isBlank() ? java.util.Map.of() : json.readValue(response.body(), json.getTypeFactory().constructMapType(java.util.Map.class, String.class, Object.class));
            return new MapResponse(response.statusCode(), body);
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AudioStorageException();
        }
    }

    private URI objectUri(String objectId) {
        if (objectId == null || !objectId.matches("audio/[\\w.-]{1,128}/(?:chunks/\\d+|assembled)")) throw new AudioStorageException();
        return URI.create(controlUrl.toString() + "?pathname=" + java.net.URLEncoder.encode(objectId, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sessionFromPath(String objectId) {
        if (objectId == null || !objectId.matches("audio/[\\w.-]{1,128}/(?:chunks/\\d+|assembled)")) throw new AudioStorageException();
        return objectId.split("/")[1];
    }

    private static String objectPath(String sessionId, ObjectType type, int sequence) {
        if (!sessionId.matches("[\\w.-]{1,128}")) throw new AudioChunkInvalidException();
        return type == ObjectType.ASSEMBLED ? "audio/" + sessionId + "/assembled" : "audio/" + sessionId + "/chunks/" + sequence;
    }

    private static int readPart(InputStream input, byte[] buffer) throws IOException {
        int count = 0;
        while (count < buffer.length) {
            int read = input.read(buffer, count, buffer.length - count);
            if (read < 0) break;
            count += read;
        }
        return count;
    }

    private static void validate(String sessionId, ObjectType type, int sequence, String mimeType, InputStream body, long bytes, String sha256) {
        if (sessionId == null || !sessionId.matches("[\\w.-]{1,128}") || type == null || (type == ObjectType.CHUNK && sequence < 0)
                || (type == ObjectType.ASSEMBLED && sequence != -1) || mimeType == null || mimeType.isBlank()
                || body == null || bytes < 1 || sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw new AudioStorageException();
    }

    private static StoredObject stored(String sessionId, AudioBlobEntry entry) {
        try { return new StoredObject(entry.pathname(), sessionId, ObjectType.valueOf(entry.type()), entry.sequence(), entry.mimeType(), entry.bytes(), entry.sha256(), entry.expiresAt() == null ? null : Instant.parse(entry.expiresAt())); }
        catch (RuntimeException failure) { throw new AudioStorageException(); }
    }

    private record AudioBlobEntry(String pathname, String type, int sequence, String mimeType, long bytes, String sha256, String expiresAt, boolean confirmed) { }
    private record ExpiredEntry(String sessionId, AudioBlobEntry entry) { }
    private record Request(String operation, String sessionId, AudioBlobEntry entry, String pathname, String expiresAt, String uploadId, Integer partCount) { }
    private record MapResponse(int statusCode, java.util.Map<String, Object> body) { }
}

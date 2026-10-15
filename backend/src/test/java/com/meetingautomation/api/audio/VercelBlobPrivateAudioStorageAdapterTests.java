package com.meetingautomation.api.audio;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VercelBlobPrivateAudioStorageAdapterTests {
    @Test
    void streamsAudioAsBoundedPrivatePartsThenCompletesWithManifestMetadata() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> methods = new ArrayList<>();
        List<String> authorization = new ArrayList<>();
        List<byte[]> bodies = new ArrayList<>();
        server.createContext("/api/audio-storage", exchange -> {
            methods.add(exchange.getRequestMethod());
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(exchange.getRequestBody().readAllBytes());
            byte[] response = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var adapter = new VercelBlobPrivateAudioStorageAdapter(HttpClient.newHttpClient(), new ObjectMapper(),
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/audio-storage"),
                    "server-secret", Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC));
            var stored = adapter.store("session-1", PrivateAudioStoragePort.ObjectType.CHUNK, 2, "audio/webm",
                    Instant.parse("2026-10-16T00:00:00Z"), new ByteArrayInputStream(new byte[] {1, 2, 3}), 3,
                    "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");

            assertThat(stored.objectId()).isEqualTo("audio/session-1/chunks/2");
            assertThat(methods).containsExactly("PUT", "POST");
            assertThat(authorization).containsOnly("Bearer server-secret");
            assertThat(bodies.get(0)).containsExactly(1, 2, 3);
            assertThat(new String(bodies.get(1), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("object:complete", "session-1", "audio/webm", "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void clientUploadConfirmationVerifiesBytesBeforeMarkingTheManifestReceived() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> methods = new ArrayList<>();
        List<String> authorization = new ArrayList<>();
        List<byte[]> bodies = new ArrayList<>();
        server.createContext("/api/audio-storage", exchange -> {
            methods.add(exchange.getRequestMethod());
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if ("GET".equals(exchange.getRequestMethod())) {
                byte[] audio = {1, 2, 3};
                exchange.getResponseHeaders().add("Content-Type", "audio/webm");
                exchange.sendResponseHeaders(200, audio.length);
                exchange.getResponseBody().write(audio);
            } else {
                bodies.add(exchange.getRequestBody().readAllBytes());
                byte[] response = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();
        try {
            var adapter = new VercelBlobPrivateAudioStorageAdapter(HttpClient.newHttpClient(), new ObjectMapper(),
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/audio-storage"),
                    "server-secret", Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC));
            adapter.confirmClientUpload("session-1", 2, "audio/webm", 3,
                    "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");

            assertThat(methods).containsExactly("GET", "POST");
            assertThat(authorization).containsOnly("Bearer server-secret");
            assertThat(new String(bodies.getFirst(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("manifest:add", "audio/session-1/chunks/2", "audio/webm", "true");
        } finally {
            server.stop(0);
        }
    }
}

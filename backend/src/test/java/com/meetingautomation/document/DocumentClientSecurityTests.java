package com.meetingautomation.document;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class DocumentClientSecurityTests {
    @Test void setupSecretDefaultClientRejectsPrivateDestinationBeforeHttpRequest() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(200, 0); exchange.close(); });
        server.start();
        try {
            var client = new DocumentClientConfiguration().documentRestClientBuilder().build();
            assertThrows(ResourceAccessException.class, () -> client.get().uri("http://127.0.0.1:" + server.getAddress().getPort()).retrieve().toBodilessEntity());
            assertEquals(0, calls.get());
        } finally { server.stop(0); }
    }
}

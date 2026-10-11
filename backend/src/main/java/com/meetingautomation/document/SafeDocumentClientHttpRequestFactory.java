package com.meetingautomation.document;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.*;

/** Constrains authenticated traffic to public Provider hosts with no HTTP redirects. */
final class SafeDocumentClientHttpRequestFactory implements ClientHttpRequestFactory {
    private final JdkClientHttpRequestFactory delegate;
    SafeDocumentClientHttpRequestFactory() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();
        delegate = new JdkClientHttpRequestFactory(client);
        delegate.setReadTimeout(Duration.ofSeconds(20));
    }
    @Override public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                || !("api.notion.com".equals(host) || host.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.atlassian\\.net")))
            throw new IOException("Provider destination rejected.");
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (!publicAddress(address)) throw new IOException("Provider destination rejected.");
        }
        return delegate.createRequest(uri, method);
    }
    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        if (bytes.length == 16) return (first & 0xfe) != 0xfc;
        int second = Byte.toUnsignedInt(bytes[1]);
        return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
            && !(first == 198 && (second == 18 || second == 19)) && !(first == 192 && second == 0);
    }
}

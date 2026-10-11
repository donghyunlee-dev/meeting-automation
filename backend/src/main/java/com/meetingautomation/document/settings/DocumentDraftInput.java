package com.meetingautomation.document.settings;

import java.net.URI;
import java.util.*;

public record DocumentDraftInput(String provider, Map<String, String> location,
        Map<String, String> credentials, String reuseExistingRootId) {
    @Override public String toString() { return "DocumentDraftInput[redacted]"; }
    public DocumentDraftInput normalized() {
        if (provider == null || location == null || credentials == null) throw SettingsException.invalid();
        String selected = provider.trim().toUpperCase(Locale.ROOT);
        Map<String, String> normalizedLocation = new TreeMap<>();
        Map<String, String> normalizedCredentials = new TreeMap<>();
        switch (selected) {
            case "NOTION" -> {
                keys(location, Set.of("parentPageId"));
                keys(credentials, Set.of("token"));
                normalizedLocation.put("parentPageId", notionId(required(location, "parentPageId")));
                normalizedCredentials.put("token", required(credentials, "token"));
            }
            case "CONFLUENCE" -> {
                keys(location, Set.of("baseUrl", "spaceId", "parentPageId"));
                keys(credentials, Set.of("accountEmail", "apiToken"));
                normalizedLocation.put("baseUrl", cloudUrl(required(location, "baseUrl")));
                normalizedLocation.put("spaceId", numericId(required(location, "spaceId")));
                if (location.get("parentPageId") != null && !location.get("parentPageId").isBlank())
                    normalizedLocation.put("parentPageId", numericId(location.get("parentPageId").trim()));
                String email = required(credentials, "accountEmail").toLowerCase(Locale.ROOT);
                if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || email.contains(":")) throw SettingsException.invalid();
                normalizedCredentials.put("accountEmail", email);
                normalizedCredentials.put("apiToken", required(credentials, "apiToken"));
            }
            default -> throw SettingsException.invalid();
        }
        String reuse = reuseExistingRootId == null || reuseExistingRootId.isBlank() ? null
            : "NOTION".equals(selected) ? notionId(reuseExistingRootId.trim()) : numericId(reuseExistingRootId.trim());
        return new DocumentDraftInput(selected, Collections.unmodifiableMap(normalizedLocation),
                Collections.unmodifiableMap(normalizedCredentials), reuse);
    }
    private static void keys(Map<String, String> values, Set<String> allowed) {
        if (!allowed.containsAll(values.keySet())) throw SettingsException.invalid();
    }
    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank() || value.length() > 4096 || value.chars().anyMatch(c -> c < 32))
            throw SettingsException.invalid();
        return value.trim();
    }
    static String numericId(String value) {
        if (!value.matches("[1-9][0-9]{0,19}")) throw SettingsException.invalid();
        return value;
    }
    static String notionId(String value) {
        if (value.contains("://")) {
            URI uri = uri(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 || !Set.of("notion.so", "www.notion.so", "notion.site", "www.notion.site").contains(uri.getHost().toLowerCase(Locale.ROOT)))
                throw SettingsException.invalid();
            String path = uri.getPath();
            String compact = path.replace("-", "");
            if (compact.length() < 32) throw SettingsException.invalid();
            value = compact.substring(compact.length() - 32);
        }
        String compact = value.replace("-", "").toLowerCase(Locale.ROOT);
        if (!compact.matches("[0-9a-f]{32}")) throw SettingsException.invalid();
        return compact.substring(0, 8) + "-" + compact.substring(8, 12) + "-" + compact.substring(12, 16)
            + "-" + compact.substring(16, 20) + "-" + compact.substring(20);
    }
    static String cloudUrl(String value) {
        URI uri = uri(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null
                || !Set.of("", "/").contains(uri.getPath())
                || !uri.getHost().toLowerCase(Locale.ROOT).matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.atlassian\\.net"))
            throw SettingsException.invalid();
        return "https://" + uri.getHost().toLowerCase(Locale.ROOT);
    }
    private static URI uri(String value) {
        try { return URI.create(value); } catch (RuntimeException ignored) { throw SettingsException.invalid(); }
    }
}

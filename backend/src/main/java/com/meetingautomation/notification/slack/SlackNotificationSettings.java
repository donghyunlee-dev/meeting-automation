package com.meetingautomation.notification.slack;

import java.net.URI;
import java.util.regex.Pattern;

public record SlackNotificationSettings(String provider, String webhookUrl) {
    private static final Pattern PATH = Pattern.compile("/services/[A-Za-z0-9-]+/[A-Za-z0-9-]+/[A-Za-z0-9-]+/?");

    public boolean configured() {
        return "SLACK".equals(provider) && validWebhookUrl(webhookUrl);
    }

    public static boolean validWebhookUrl(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) return false;
        try {
            URI uri = URI.create(webhookUrl.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && "hooks.slack.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1 && uri.getQuery() == null
                    && uri.getFragment() == null && PATH.matcher(uri.getPath()).matches();
        } catch (IllegalArgumentException invalid) { return false; }
    }

    @Override public String toString() { return "SlackNotificationSettings[configured=" + configured() + "]"; }
}

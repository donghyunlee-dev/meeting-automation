package com.meetingautomation.document.bootstrap;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BootstrapTemplateRenderer {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.+)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+[.)]\\s+(.+)$");

    private BootstrapTemplateRenderer() { }

    static List<Map<String, Object>> notionBlocks(String marker, String markdown) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(notionTextBlock("paragraph", marker));
        for (String line : markdown.lines().toList()) {
            if (line.isBlank()) continue;
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                int level = Math.min(heading.group(1).length(), 3);
                blocks.add(notionTextBlock("heading_" + level, plainText(heading.group(2))));
            } else {
                Matcher bullet = BULLET.matcher(line);
                Matcher numbered = NUMBERED.matcher(line);
                if (bullet.matches()) blocks.add(notionTextBlock("bulleted_list_item", plainText(bullet.group(1))));
                else if (numbered.matches()) blocks.add(notionTextBlock("numbered_list_item", plainText(numbered.group(1))));
                else blocks.add(notionTextBlock("paragraph", plainText(line)));
            }
        }
        return List.copyOf(blocks);
    }

    static String confluenceStorage(String marker, String markdown) {
        StringBuilder body = new StringBuilder("<p>").append(escape(marker)).append("</p>");
        for (String line : markdown.lines().toList()) {
            if (line.isBlank()) continue;
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                int level = Math.min(heading.group(1).length(), 6);
                body.append("<h").append(level).append(">").append(escape(plainText(heading.group(2))))
                    .append("</h").append(level).append(">");
            } else {
                Matcher bullet = BULLET.matcher(line);
                Matcher numbered = NUMBERED.matcher(line);
                if (bullet.matches()) body.append("<ul><li>").append(escape(plainText(bullet.group(1)))).append("</li></ul>");
                else if (numbered.matches()) body.append("<ol><li>").append(escape(plainText(numbered.group(1)))).append("</li></ol>");
                else body.append("<p>").append(escape(plainText(line))).append("</p>");
            }
        }
        return body.toString();
    }

    private static Map<String, Object> notionTextBlock(String type, String text) {
        return Map.of("object", "block", "type", type, type, Map.of("rich_text", List.of(
            Map.of("type", "text", "text", Map.of("content", text)))));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String plainText(String markdown) {
        return markdown.replaceAll("\\*\\*(.+?)\\*\\*", "$1").replaceAll("`([^`]+)`", "$1");
    }
}

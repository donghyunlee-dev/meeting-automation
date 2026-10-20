package com.meetingautomation.email.gmail;

import java.util.ArrayList;
import java.util.List;

/** Converts the text-focused meeting minutes Markdown into safe email HTML. */
final class EmailMarkdownRenderer {
    String render(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        List<String> lines = markdown.strip().lines().toList();
        StringBuilder html = new StringBuilder();
        List<String> listItems = new ArrayList<>();
        String listTag = null;
        StringBuilder paragraph = new StringBuilder();

        for (String sourceLine : lines) {
            String line = sourceLine.strip();
            if (line.isEmpty()) {
                flushParagraph(html, paragraph);
                listTag = flushList(html, listItems, listTag);
                continue;
            }
            Heading heading = heading(line);
            ListItem item = listItem(line);
            if (heading != null) {
                flushParagraph(html, paragraph);
                listTag = flushList(html, listItems, listTag);
                html.append("<h").append(heading.level()).append('>')
                        .append(inline(heading.text())).append("</h").append(heading.level()).append('>');
            } else if (item != null) {
                flushParagraph(html, paragraph);
                if (listTag != null && !listTag.equals(item.tag())) listTag = flushList(html, listItems, listTag);
                listTag = item.tag();
                listItems.add(inline(item.text()));
            } else {
                listTag = flushList(html, listItems, listTag);
                if (!paragraph.isEmpty()) paragraph.append(' ');
                paragraph.append(inline(line));
            }
        }
        flushParagraph(html, paragraph);
        flushList(html, listItems, listTag);
        return html.toString();
    }

    private static Heading heading(String line) {
        int level = 0;
        while (level < line.length() && level < 6 && line.charAt(level) == '#') level++;
        return level > 0 && level < line.length() && line.charAt(level) == ' '
                ? new Heading(Math.min(level, 3), line.substring(level + 1)) : null;
    }

    private static ListItem listItem(String line) {
        if (line.matches("[-*+]\\s+.*")) return new ListItem("ul", line.replaceFirst("^[-*+]\\s+", ""));
        if (line.matches("[0-9]+[.)]\\s+.*")) return new ListItem("ol", line.replaceFirst("^[0-9]+[.)]\\s+", ""));
        return null;
    }

    private static String flushList(StringBuilder html, List<String> items, String tag) {
        if (tag == null || items.isEmpty()) return null;
        html.append('<').append(tag).append('>');
        items.forEach(item -> html.append("<li>").append(item).append("</li>"));
        html.append("</").append(tag).append('>');
        items.clear();
        return null;
    }

    private static void flushParagraph(StringBuilder html, StringBuilder paragraph) {
        if (paragraph.isEmpty()) return;
        html.append("<p>").append(paragraph).append("</p>");
        paragraph.setLength(0);
    }

    private static String inline(String text) {
        String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
        safe = safe.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>")
                .replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<em>$1</em>")
                .replaceAll("`([^`]+)`", "<code>$1</code>");
        return safe;
    }

    private record Heading(int level, String text) { }
    private record ListItem(String tag, String text) { }
}

package com.meetingautomation.document.transfer;

import java.util.ArrayList;
import java.util.List;

/** Converts the AI-produced Markdown document into provider-native text blocks. */
public final class MeetingMarkdownRenderer {
    public record Block(String type, String text) { }

    public List<Block> blocks(String markdown) {
        if (markdown == null || markdown.isBlank()) throw new IllegalArgumentException("Rendered meeting document is required.");
        List<Block> blocks = new ArrayList<>();
        for (String line : markdown.strip().split("\\R")) {
            String value = line.strip();
            if (value.isEmpty()) continue;
            if (value.startsWith("### ")) blocks.add(new Block("heading_3", value.substring(4)));
            else if (value.startsWith("## ")) blocks.add(new Block("heading_2", value.substring(3)));
            else if (value.startsWith("# ")) blocks.add(new Block("heading_1", value.substring(2)));
            else if (value.matches("[-*]\\s+.*")) blocks.add(new Block("bulleted_list_item", value.replaceFirst("^[-*]\\s+", "")));
            else if (value.matches("[0-9]+[.)]\\s+.*")) blocks.add(new Block("numbered_list_item", value.replaceFirst("^[0-9]+[.)]\\s+", "")));
            else blocks.add(new Block("paragraph", value));
        }
        if (blocks.isEmpty()) throw new IllegalArgumentException("Rendered meeting document is empty.");
        return List.copyOf(blocks);
    }
}

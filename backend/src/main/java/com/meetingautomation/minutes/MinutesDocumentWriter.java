package com.meetingautomation.minutes;

@FunctionalInterface
public interface MinutesDocumentWriter {
    String render(MinutesDocumentCommand command);
}

package com.meetingautomation.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ParticipantPageMapperTests {
    @Test
    void emptyFirstEmailLineFailsEvenWhenLaterLineIsValid() {
        DocumentProviderException failure = assertThrows(DocumentProviderException.class,
                () -> ParticipantPageMapper.map("pt-1", "Ada", "Email:\nEmail: ada@example.test"));

        assertEquals("PARTICIPANT_LIST_FAILED", failure.code());
        assertEquals(502, failure.statusCode());
        assertEquals(false, failure.retryable());
    }

    @Test
    void malformedFirstEmailLineFailsEvenWhenLaterLineIsValid() {
        DocumentProviderException failure = assertThrows(DocumentProviderException.class,
                () -> ParticipantPageMapper.map("pt-1", "Ada",
                        "Email: not-an-address with extra text\nEmail: ada@example.test"));

        assertEquals("PARTICIPANT_LIST_FAILED", failure.code());
        assertEquals(502, failure.statusCode());
        assertEquals(false, failure.retryable());
    }

    @Test
    void mapsTheFirstValidEmailLineWhenSeveralArePresent() {
        assertEquals(new Participant("pt-1", "Ada", "first@example.test"),
                ParticipantPageMapper.map("pt-1", "Ada",
                        "Role: Engineer\nEmail: first@example.test\nEmail: second@example.test"));
    }
}

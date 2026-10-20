package com.meetingautomation.email;

import java.util.List;

/** Provider-neutral outbound port; callers supply only recipients selected after document save. */
public interface EmailProvider {
    boolean validateConnection();

    default boolean sendTestMessage() { return false; }

    List<RecipientDeliveryResult> sendMeetingEmail(MeetingEmailCommand command);
}

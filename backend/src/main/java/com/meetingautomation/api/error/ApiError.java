package com.meetingautomation.api.error;

import java.util.Map;

public record ApiError(
        String code,
        String message,
        String category,
        boolean retryable,
        String traceId,
        Map<String, Object> details) {
}

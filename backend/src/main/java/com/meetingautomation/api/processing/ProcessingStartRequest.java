package com.meetingautomation.api.processing;

import java.math.BigDecimal;

public record ProcessingStartRequest(BigDecimal expectedChunks, BigDecimal durationMs, String mimeType) {
}

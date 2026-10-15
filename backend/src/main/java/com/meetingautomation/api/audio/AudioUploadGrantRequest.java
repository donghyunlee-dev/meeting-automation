package com.meetingautomation.api.audio;

public record AudioUploadGrantRequest(String mimeType, long bytes, String sha256) { }

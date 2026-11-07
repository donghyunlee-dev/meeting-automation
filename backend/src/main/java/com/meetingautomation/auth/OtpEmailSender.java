package com.meetingautomation.auth;

@FunctionalInterface
public interface OtpEmailSender {
    boolean sendOtpEmail(String recipient, String code);
}

package com.meetingautomation.api.session;

public record DeliveryRetryResponse(Data data) {
    public record Data(String deliveryId, String status) { }
}

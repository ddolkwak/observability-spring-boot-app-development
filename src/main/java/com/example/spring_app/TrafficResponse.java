package com.example.spring_app;

public record TrafficResponse(Long id, String message, String clientIp) {
    public static TrafficResponse from(TrafficData data) {
        return new TrafficResponse(
                data.getId(), data.getMessage(), data.getClientIp());
    }
}
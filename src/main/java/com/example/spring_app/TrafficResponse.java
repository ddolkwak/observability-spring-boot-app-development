package com.example.spring_app;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record TrafficResponse(
        Long id,
        String message,
        String clientIp,
        String testRunId,
        @JsonFormat(shape = JsonFormat.Shape.STRING,
                pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
                timezone = "UTC")
        Instant createdAt
) {
    public static TrafficResponse from(TrafficData data) {
        return new TrafficResponse(
                data.getId(), data.getMessage(), data.getClientIp(),
                data.getTestRunId(), data.getCreatedAt());
    }
}
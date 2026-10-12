package com.example.spring_app;

import java.util.List;

public record TrafficSliceResponse(
        List<TrafficResponse> items,
        int page,
        int size,
        int numberOfElements,
        boolean hasNext
) {
}
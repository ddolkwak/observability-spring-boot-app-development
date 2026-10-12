package com.example.spring_app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTrafficRequest(
        @NotBlank(message = "message must not be blank")
        @Size(max = 255, message = "message must be at most 255 characters")
        String message
) {
}
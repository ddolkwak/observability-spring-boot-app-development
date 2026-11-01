package com.example.spring_app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTrafficRequest(
        @NotBlank(message = "message must not be blank")
        @Size(max = 255, message = "message must be at most 255 characters")
        String message,

        @Pattern(regexp = TEST_RUN_ID_PATTERN, message = "testRunId must start with an ASCII letter or digit and contain only ASCII letters, digits, '.', '_' or '-', up to 128 characters")
        String testRunId) {
        public static final String TEST_RUN_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";
}
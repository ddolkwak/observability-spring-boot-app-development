package com.example.spring_app;

public class InvalidTrafficQueryException extends RuntimeException {
    private final String field;

    public InvalidTrafficQueryException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
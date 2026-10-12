package com.example.spring_app;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidTrafficQueryException.class)
    public @Nullable ResponseEntity<Object> handleInvalidQuery(
            InvalidTrafficQueryException ex, WebRequest request) {
        return handleExceptionInternal(
                ex, null, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(Exception.class)
    public @Nullable ResponseEntity<Object> handleUnexpected(
            Exception ex, WebRequest request) {
        log.error("Unexpected traffic API failure", ex);
        return handleExceptionInternal(
                ex, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(
                statusCode, publicDetail(statusCode.value()));
        problem.setProperty("code", errorCode(ex, statusCode.value()));
        problem.setProperty("errors", fieldErrors(ex, statusCode));

        if (request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(
                    servletRequest.getRequest().getRequestURI()));
        }

        // Preserve status and headers, including Allow for HTTP 405.
        return super.handleExceptionInternal(
                ex, problem, headers, statusCode, request);
    }

    private static List<Map<String, String>> fieldErrors(
            Exception ex, HttpStatusCode statusCode) {
        if (statusCode.is5xxServerError()) {
            return List.of();
        }
        if (ex instanceof MethodArgumentNotValidException validation) {
            return validation.getBindingResult().getFieldErrors().stream()
                    .map(error -> Map.of(
                            "field", error.getField(),
                            "message", error.getDefaultMessage() == null
                                    ? "Invalid value" : error.getDefaultMessage()))
                    .toList();
        }
        if (ex instanceof InvalidTrafficQueryException query) {
            return List.of(Map.of(
                    "field", query.getField(), "message", query.getMessage()));
        }
        return List.of();
    }

    private static String errorCode(Exception ex, int status) {
        if (status >= 500) {
            return "INTERNAL_ERROR";
        }
        if (ex instanceof MethodArgumentNotValidException
                || ex instanceof InvalidTrafficQueryException) {
            return "VALIDATION_ERROR";
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return "MALFORMED_JSON";
        }
        if (ex instanceof TypeMismatchException) {
            return "INVALID_PARAMETER";
        }
        return switch (status) {
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "REQUEST_REJECTED";
        };
    }

    private static String publicDetail(int status) {
        return switch (status) {
            case 400 -> "The request body or parameters are invalid.";
            case 404 -> "The requested resource was not found.";
            case 405 -> "The HTTP method is not supported for this resource.";
            case 406 -> "The requested response media type is not supported.";
            case 415 -> "The request content type is not supported.";
            default -> status >= 500
                    ? "An unexpected server error occurred."
                    : "The request could not be processed.";
        };
    }
}
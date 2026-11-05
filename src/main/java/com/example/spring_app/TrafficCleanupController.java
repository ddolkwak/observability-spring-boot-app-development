package com.example.spring_app;

import java.util.regex.Pattern;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/experiments/traffic")
@ConditionalOnProperty(prefix = "traffic.experiments", name = "cleanup-enabled", havingValue = "true", matchIfMissing = false)
public class TrafficCleanupController {
    private static final Logger log = LoggerFactory.getLogger(TrafficCleanupController.class);
    private static final Pattern TEST_RUN_ID_PATTERN =
            Pattern.compile(CreateTrafficRequest.TEST_RUN_ID_PATTERN);
    private final TrafficService service;

    public TrafficCleanupController(TrafficService service) {
        this.service = service;
    }

    @DeleteMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public TrafficCleanupResponse cleanup(
            @RequestParam(name = "testRunId", required = false) String testRunId,
            HttpServletRequest request) {
        if (testRunId == null || !TEST_RUN_ID_PATTERN.matcher(testRunId).matches()) {
            throw new InvalidTrafficQueryException("testRunId", "testRunId must start with an ASCII letter or digit, contain only " + "ASCII letters, digits, '.', '_' or '-', and be at most 128 characters");
        }
        request.setAttribute(ApiExceptionHandler.OPERATION_ATTRIBUTE, "cleanup");
        request.setAttribute(ApiExceptionHandler.TEST_RUN_ID_ATTRIBUTE, testRunId);
        var response = service.cleanupTraffic(testRunId);
        request.removeAttribute(ApiExceptionHandler.OPERATION_ATTRIBUTE);
        request.removeAttribute(ApiExceptionHandler.TEST_RUN_ID_ATTRIBUTE);
        log.atInfo()
                .addKeyValue("operation", "cleanup")
                .addKeyValue("result", "success")
                .addKeyValue("testRunId", response.testRunId())
                .addKeyValue("deletedCount", response.deletedCount())
                .log("Traffic operation completed");
        return response;
    }
}
package com.example.spring_app;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/experiments/traffic")
@ConditionalOnProperty(prefix = "traffic.experiments", name = "full-scan-enabled",
        havingValue = "true", matchIfMissing = false)
public class ExperimentTrafficController {
    private static final Logger log = LoggerFactory.getLogger(ExperimentTrafficController.class);
    private final TrafficService service;

    public ExperimentTrafficController(TrafficService service) {
        this.service = service;
    }

    @GetMapping(value = "/full-scan", produces = MediaType.APPLICATION_JSON_VALUE)
    public FullScanResponse fullScan(HttpServletRequest request) {
        request.setAttribute(ApiExceptionHandler.OPERATION_ATTRIBUTE, "read-all");
        var response = service.readAllForExperiment();
        request.removeAttribute(ApiExceptionHandler.OPERATION_ATTRIBUTE);
        log.atInfo()
                .addKeyValue("operation", "read-all")
                .addKeyValue("result", "success")
                .addKeyValue("testRunId", (Object) null)
                .addKeyValue("returnedRows", response.recordsRead())
                .addKeyValue("experiment", response.experiment())
                .log("Traffic operation completed");
        return response;
    }
}
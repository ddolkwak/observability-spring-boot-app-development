package com.example.spring_app;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/experiments/traffic")
@ConditionalOnProperty(
        prefix = "traffic.experiments",
        name = "full-scan-enabled",
        havingValue = "true",
        matchIfMissing = false)
public class ExperimentTrafficController {
    private final TrafficService service;

    public ExperimentTrafficController(TrafficService service) {
        this.service = service;
    }

    @GetMapping(value = "/full-scan", produces = MediaType.APPLICATION_JSON_VALUE)
    public FullScanResponse fullScan() {
        return service.readAllForExperiment();
    }
}
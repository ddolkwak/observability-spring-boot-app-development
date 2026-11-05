package com.example.spring_app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/traffic")
public class TrafficController {
    private static final Logger log = LoggerFactory.getLogger(TrafficController.class);
    private final TrafficService service;

    public TrafficController(TrafficService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TrafficResponse> create(
            @Valid @RequestBody CreateTrafficRequest body,
            HttpServletRequest request) {
        request.setAttribute(ApiExceptionHandler.OPERATION_ATTRIBUTE, "create");
        request.setAttribute(ApiExceptionHandler.TEST_RUN_ID_ATTRIBUTE, body.testRunId());
        var response = service.createTraffic(
                body.message(), request.getRemoteAddr(), body.testRunId());
        request.removeAttribute(ApiExceptionHandler.OPERATION_ATTRIBUTE);
        request.removeAttribute(ApiExceptionHandler.TEST_RUN_ID_ATTRIBUTE);
        log.atInfo()
                .addKeyValue("operation", "create")
                .addKeyValue("result", "success")
                .addKeyValue("testRunId", response.testRunId())
                .addKeyValue("recordId", response.id().toString())
                .log("Traffic operation completed");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public TrafficSliceResponse read(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        request.setAttribute(ApiExceptionHandler.OPERATION_ATTRIBUTE, "read-page");
        var response = service.readTraffic(page, size);
        request.removeAttribute(ApiExceptionHandler.OPERATION_ATTRIBUTE);
        log.atInfo()
                .addKeyValue("operation", "read-page")
                .addKeyValue("result", "success")
                .addKeyValue("testRunId", (Object) null)
                .addKeyValue("returnedRows", response.numberOfElements())
                .addKeyValue("page", page)
                .addKeyValue("size", size)
                .addKeyValue("hasNext", response.hasNext())
                .log("Traffic operation completed");
        return response;
    }
}
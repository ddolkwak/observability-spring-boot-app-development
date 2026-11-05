package com.example.spring_app;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TrafficOperationLoggingTests {
    private static final String TRACE_ID = "11111111111111111111111111111111";
    private static final String SPAN_ID = "2222222222222222";
    private TrafficService service;
    private MockMvc mvc;
    private Logger packageLogger;
    private Level previousLevel;
    private Map<String, String> previousMdc;
    private ListAppender<ILoggingEvent> events;
    private LoggerContext encoderContext;
    private StructuredLogEncoder encoder;

    @BeforeEach
    void setUp() {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        service = mock(TrafficService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TrafficController(service),
                        new ExperimentTrafficController(service),
                        new TrafficCleanupController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        packageLogger = (Logger) LoggerFactory.getLogger("com.example.spring_app");
        previousLevel = packageLogger.getLevel();
        packageLogger.setLevel(Level.INFO);
        events = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        events.setContext(packageLogger.getLoggerContext());
        events.start();
        packageLogger.addAppender(events);
        encoderContext = new LoggerContext();
        encoderContext.putObject(Environment.class.getName(), new MockEnvironment());
        encoder = new StructuredLogEncoder();
        encoder.setContext(encoderContext);
        encoder.setFormat("logstash");
        encoder.start();
    }

    @AfterEach
    void tearDown() {
        packageLogger.detachAppender(events);
        packageLogger.setLevel(previousLevel);
        events.stop();
        encoder.stop();
        encoderContext.stop();
        MDC.clear();
        if (previousMdc != null) { MDC.setContextMap(previousMdc); }
    }

    @Test
    void writesPageResultAndPreservesTheActiveMdc() throws Exception {
        when(service.readTraffic(0, 20)).thenReturn(
                new TrafficSliceResponse(List.of(), 0, 20, 0, false));
        try (var trace = MDC.putCloseable("traceId", TRACE_ID);
                var span = MDC.putCloseable("spanId", SPAN_ID)) {
            mvc.perform(get("/api/traffic")).andExpect(status().isOk())
                    .andDo(result -> assertThat(result.getRequest()
                            .getAttribute(ApiExceptionHandler.OPERATION_ATTRIBUTE)).isNull());
        }
        var result = singleResult();
        assertThat(result).containsEntry("operation", "read-page")
                .containsEntry("result", "success")
                .containsEntry("traceId", TRACE_ID).containsEntry("spanId", SPAN_ID)
                .containsKey("testRunId");
        assertThat(result.get("testRunId")).isNull();
        assertThat(((Number) result.get("returnedRows")).intValue()).isZero();
        assertThat(result).doesNotContainKeys("recordId", "deletedCount", "exceptionType");
    }

    @Test
    void keepsLargeRecordIdExactWithoutChangingTheApiId() throws Exception {
        long id = 9007199254740993L;
        when(service.createTraffic("fixture", "127.0.0.1", "run-A"))
                .thenReturn(new TrafficResponse(id, "fixture", "127.0.0.1", "run-A",
                        Instant.parse("2026-11-05T00:00:00Z")));
        mvc.perform(post("/api/traffic").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"fixture\",\"testRunId\":\"run-A\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.id").value(id));
        assertThat(singleResult()).containsEntry("operation", "create")
                .containsEntry("recordId", Long.toString(id)).containsEntry("testRunId", "run-A");
    }

    @Test
    void writesFullScanCountAndCleanupDeletedCount() throws Exception {
        when(service.readAllForExperiment()).thenReturn(new FullScanResponse("full-table-read", 3));
        mvc.perform(get("/api/experiments/traffic/full-scan")).andExpect(status().isOk());
        var read = singleResult();
        assertThat(read).containsEntry("operation", "read-all");
        assertThat(((Number) read.get("returnedRows")).intValue()).isEqualTo(3);
        events.list.clear();
        when(service.cleanupTraffic("run-A")).thenReturn(new TrafficCleanupResponse("run-A", 2));
        mvc.perform(delete("/api/experiments/traffic").param("testRunId", "run-A"))
                .andExpect(status().isOk());
        var cleanup = singleResult();
        assertThat(cleanup).containsEntry("operation", "cleanup").containsEntry("testRunId", "run-A");
        assertThat(((Number) cleanup.get("deletedCount")).intValue()).isEqualTo(2);
        assertThat(cleanup).doesNotContainKeys("recordId", "returnedRows");
    }

    @Test
    void writesOneSafeFailureAndNoSuccessOnProxyFailure() throws Exception {
        var cause = new SQLException("password=do-not-leak jdbc:postgresql://private-db", "22001");
        when(service.cleanupTraffic("run-A"))
                .thenThrow(new IllegalStateException("token=do-not-leak", cause));
        try (var trace = MDC.putCloseable("traceId", TRACE_ID);
                var span = MDC.putCloseable("spanId", SPAN_ID)) {
            mvc.perform(delete("/api/experiments/traffic").param("testRunId", "run-A"))
                    .andExpect(status().isInternalServerError());
        }
        var result = singleResult();
        assertThat(result).containsEntry("operation", "cleanup").containsEntry("result", "failure")
                .containsEntry("testRunId", "run-A").containsEntry("traceId", TRACE_ID)
                .containsEntry("spanId", SPAN_ID)
                .containsEntry("exceptionType", IllegalStateException.class.getName())
                .containsEntry("exceptionCauseTypes", List.of(
                        IllegalStateException.class.getName(), SQLException.class.getName()));
        assertThat((List<?>) result.get("exceptionStackFrames")).isNotEmpty()
                .hasSizeLessThanOrEqualTo(32);
        assertThat(result).doesNotContainKeys("recordId", "returnedRows", "deletedCount", "stack_trace");
        assertThat(events.list).hasSize(1);
        assertThat(encode(events.list.get(0)))
                .doesNotContain("do-not-leak", "password=", "token=", "private-db");
    }

    @Test
    void rejectsInvalidRunBeforeOperationResultLogging() throws Exception {
        mvc.perform(delete("/api/experiments/traffic").param("testRunId", "bad/id"))
                .andExpect(status().isBadRequest());
        assertThat(events.list).isEmpty();
    }

    @Test
    void genericApiFailureDoesNotInventAnOperationResult() {
        var request = new org.springframework.web.context.request.ServletWebRequest(
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/traffic"));
        new ApiExceptionHandler().handleUnexpected(new IllegalStateException("token=do-not-leak"), request);
        assertThat(events.list).hasSize(1);
        var diagnostic = JsonParserFactory.getJsonParser().parseMap(encode(events.list.get(0)));
        assertThat(diagnostic).containsEntry("exceptionType", IllegalStateException.class.getName())
                .doesNotContainKeys("operation", "result", "testRunId", "stack_trace");
        assertThat(encode(events.list.get(0))).doesNotContain("do-not-leak");
    }

    private Map<String, Object> singleResult() {
        var results = events.list.stream().map(this::encode)
                .map(json -> JsonParserFactory.getJsonParser().parseMap(json))
                .filter(json -> json.containsKey("result")).toList();
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    private String encode(ILoggingEvent event) {
        String json = new String(encoder.encode(event), StandardCharsets.UTF_8).stripTrailing();
        assertThat(json).startsWith("{").endsWith("}").doesNotContain("\n", "\r");
        return json;
    }
}
package com.example.spring_app;

import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.test.tracing.export=true",
        "management.opentelemetry.enabled=true",
        "management.tracing.sampling.probability=1.0",
        "management.tracing.export.otlp.enabled=false",
        "management.logging.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false",
        "jdbc.includes=QUERY",
        "traffic.experiments.full-scan-enabled=true",
        "traffic.experiments.cleanup-enabled=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.example.spring_app.JdbcTracingTests$FailingSelectInspector"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TrafficTestDatabaseGuard.class, JdbcTracingTests.TraceCapture.class})
public class JdbcTracingTests {
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String UPSTREAM_SPAN_ID = "00f067aa0ba902b7";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + UPSTREAM_SPAN_ID + "-01";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecordingSpanProcessor spans;
    @Autowired ObservationRegistry observations;
    @Autowired ApplicationContext context;

    @BeforeEach
    void clearOnlyTestDatabase() {
        FailingSelectInspector.disarm();
        assertThat(context.getBeanNamesForType(SpanExporter.class))
                .as("No external span exporter is allowed in this test").isEmpty();
        assertThat(jdbc.queryForObject("select current_database()", String.class))
                .isEqualTo("traffic_test");
        assertThat(jdbc.queryForObject("select to_regclass('traffic_data_043_missing')", String.class))
                .as("Failure-injection relation must not exist").isNull();
        jdbc.update("delete from traffic_data");
        spans.clear();
    }

    @AfterEach
    void restoreTestScope() {
        FailingSelectInspector.disarm();
        assertThat(observations.getCurrentObservation()).isNull();
    }

    @Test
    void tracesSuccessfulCreateAndPageRead() throws Exception {
        mvc.perform(post("/api/traffic").header("traceparent", TRACEPARENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"jdbc-trace-test\",\"testRunId\":\"jdbc-043\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber());
        assertTrace("create", false);
        spans.clear();
        mvc.perform(get("/api/traffic").header("traceparent", TRACEPARENT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.numberOfElements").value(1));
        assertTrace("read-page", false);
    }

    @Test
    void tracesFullScanAndScopedCleanup() throws Exception {
        jdbc.update("insert into traffic_data(message, client_ip, test_run_id) values (?, ?, ?)",
                "jdbc-trace-test", "192.0.2.10", "jdbc-043");
        spans.clear();
        mvc.perform(get("/api/experiments/traffic/full-scan").header("traceparent", TRACEPARENT))
                .andExpect(status().isOk());
        assertTrace("read-all", false);
        spans.clear();
        mvc.perform(delete("/api/experiments/traffic").param("testRunId", "jdbc-043")
                        .header("traceparent", TRACEPARENT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deletedCount").value(1));
        assertTrace("cleanup", false);
    }

    @Test
    void recordsAnActualPostgresqlExecutionFailure() throws Exception {
        FailingSelectInspector.arm();
        try {
            mvc.perform(get("/api/traffic").header("traceparent", TRACEPARENT))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.detail").value("An unexpected server error occurred."));
            assertThat(FailingSelectInspector.isArmed()).isFalse();
            assertTrace("read-page", true);
        } finally {
            FailingSelectInspector.disarm();
        }
    }

    private void assertTrace(String operation, boolean failed) {
        List<SpanData> trace = spans.snapshot().stream()
                .filter(span -> TRACE_ID.equals(span.getTraceId())).toList();
        List<SpanData> http = trace.stream().filter(span -> span.getKind() == SpanKind.SERVER).toList();
        assertThat(http).hasSize(1);
        assertThat(http.get(0).getParentSpanId()).isEqualTo(UPSTREAM_SPAN_ID);
        List<SpanData> service = trace.stream()
                .filter(span -> ("traffic " + operation).equals(span.getName())).toList();
        assertThat(service).hasSize(1);
        SpanData operationSpan = service.get(0);
        assertThat(operationSpan.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(operationSpan.getParentSpanId()).isEqualTo(http.get(0).getSpanId());
        List<SpanData> queries = trace.stream().filter(span -> span.getKind() == SpanKind.CLIENT).toList();
        assertThat(queries).isNotEmpty();
        // Current ordered Slice read executes one SELECT. Do not assume INSERT has only one call.
        if ("read-page".equals(operation)) { assertThat(queries).hasSize(1); }
        assertThat(queries).allSatisfy(query -> {
            assertThat(query.getParentSpanId()).isEqualTo(operationSpan.getSpanId());
            assertThat(query.getEndEpochNanos()).isGreaterThanOrEqualTo(query.getStartEpochNanos());
        });
        if (failed) {
            assertThat(operationSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(queries).anySatisfy(query -> {
                assertThat(query.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                assertThat(query.getEvents()).anyMatch(event -> "exception".equals(event.getName()));
            });
        } else {
            assertThat(operationSpan.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
            assertThat(queries).allSatisfy(query ->
                    assertThat(query.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TraceCapture {
        @Bean
        RecordingSpanProcessor recordingSpanProcessor() { return new RecordingSpanProcessor(); }
    }

    static final class RecordingSpanProcessor implements SpanProcessor {
        private final Queue<SpanData> ended = new ConcurrentLinkedQueue<>();
        @Override public void onStart(Context context, ReadWriteSpan span) { }
        @Override public boolean isStartRequired() { return false; }
        @Override public void onEnd(ReadableSpan span) { ended.add(span.toSpanData()); }
        @Override public boolean isEndRequired() { return true; }
        List<SpanData> snapshot() { return List.copyOf(ended); }
        void clear() { ended.clear(); }
    }

    public static final class FailingSelectInspector implements StatementInspector {
        private static final Pattern TABLE = Pattern.compile("(?i)\\bfrom\\s+traffic_data\\b");
        private static final ThreadLocal<Boolean> ARMED = ThreadLocal.withInitial(() -> false);
        public FailingSelectInspector() { }
        static void arm() { ARMED.set(true); }
        static boolean isArmed() { return ARMED.get(); }
        static void disarm() { ARMED.remove(); }
        @Override
        public String inspect(String sql) {
            if (ARMED.get() && sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select")
                    && TABLE.matcher(sql).find()) {
                ARMED.set(false);
                return TABLE.matcher(sql).replaceFirst("from traffic_data_043_missing");
            }
            return sql;
        }
    }
}
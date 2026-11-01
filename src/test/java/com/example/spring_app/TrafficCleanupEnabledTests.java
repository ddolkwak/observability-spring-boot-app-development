package com.example.spring_app;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "traffic.experiments.cleanup-enabled=true",
        "traffic.experiments.full-scan-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(TrafficTestDatabaseGuard.class)
class TrafficCleanupEnabledTests {
    private static final String ENDPOINT = "/api/experiments/traffic";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void clearOnlyTestDatabase() {
        assertThat(jdbc.queryForObject("select current_database()", String.class))
                .as("Only the dedicated traffic_test database may be cleared")
                .isEqualTo("traffic_test");
        jdbc.update("delete from traffic_data");
        SqlCaptureInspector.clear();
        statistics().clear();
    }

    @Test
    void deletesOnlyTheExactRunAndReportsTheActualCountWithoutLoadingEntities() throws Exception {
        seed("a-1", "run-A");
        seed("a-2", "run-A");
        seed("a-3", "run-A");
        seed("b-1", "run-B");
        seed("b-2", "run-B");
        seed("lower-case", "run-a");
        seed("prefix-match", "run-AA");
        jdbc.update("""
                insert into traffic_data (message, client_ip, test_run_id, created_at)
                values ('legacy', '127.0.0.1', null, null)
                """);

        List<Map<String, Object>> expectedRemaining = jdbc.queryForList("""
                select id, message, client_ip, test_run_id, created_at
                from traffic_data
                where test_run_id is distinct from ?
                order by id
                """, "run-A");
        SqlCaptureInspector.clear();
        statistics().clear();

        mvc.perform(delete(ENDPOINT).param("testRunId", "run-A"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.testRunId").value("run-A"))
                .andExpect(jsonPath("$.deletedCount").value(3));

        assertSingleScopedDeleteWithoutEntityLoads();
        assertThat(jdbc.queryForObject(
                "select count(*) from traffic_data where test_run_id = ?", Long.class, "run-A"))
                .isZero();
        assertThat(jdbc.queryForList("""
                select id, message, client_ip, test_run_id, created_at
                from traffic_data order by id
                """)).containsExactlyElementsOf(expectedRemaining);

        mvc.perform(delete(ENDPOINT).param("testRunId", "run-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testRunId").value("run-A"))
                .andExpect(jsonPath("$.deletedCount").value(0));
        assertThat(jdbc.queryForList("""
                select id, message, client_ip, test_run_id, created_at
                from traffic_data order by id
                """)).containsExactlyElementsOf(expectedRemaining);
    }

    @Test
    void unknownRunReturnsZeroAndPreservesExistingRows() throws Exception {
        seed("preserved", "run-B");
        seed("legacy", null);

        mvc.perform(delete(ENDPOINT).param("testRunId", "not-found"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testRunId").value("not-found"))
                .andExpect(jsonPath("$.deletedCount").value(0));
        assertThat(jdbc.queryForObject("select count(*) from traffic_data", Long.class))
                .isEqualTo(2L);
    }

    @Test
    void acceptsTheMaximum128CharacterRunId() throws Exception {
        String runId = "A" + "x".repeat(127);
        seed("maximum-id", runId);
        seed("preserved", "run-B");

        mvc.perform(delete(ENDPOINT).param("testRunId", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testRunId").value(runId))
                .andExpect(jsonPath("$.deletedCount").value(1));
        assertThat(jdbc.queryForObject("select count(*) from traffic_data", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void missingRunIdReturnsValidationProblemWithoutDeleting() throws Exception {
        seed("preserved", "run-A");
        SqlCaptureInspector.clear();

        expectValidationProblem(mvc.perform(delete(ENDPOINT)));

        assertThat(SqlCaptureInspector.statements()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from traffic_data", Long.class))
                .isEqualTo(1L);
    }

    static Stream<String> invalidRunIds() {
        return Stream.of("", " ", "run A", "run/A", "-run", "_run", ".run",
                "runé", "a".repeat(129), " run-A", "run-A ", "a\n", "run' OR '1'='1");
    }

    @ParameterizedTest
    @MethodSource("invalidRunIds")
    void invalidRunIdReturnsValidationProblemWithoutDeleting(String runId) throws Exception {
        seed("preserved", "run-A");
        SqlCaptureInspector.clear();

        expectValidationProblem(mvc.perform(delete(ENDPOINT).param("testRunId", runId)));

        assertThat(SqlCaptureInspector.statements()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from traffic_data", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void enablingCleanupDoesNotEnableFullScan() throws Exception {
        mvc.perform(get("/api/experiments/traffic/full-scan"))
                .andExpect(status().isNotFound());
        assertThat(SqlCaptureInspector.statements()).isEmpty();
    }

    private void seed(String message, String runId) {
        jdbc.update("""
                insert into traffic_data (message, client_ip, test_run_id)
                values (?, ?, ?)
                """, message, "127.0.0.1", runId);
    }

    private ResultActions expectValidationProblem(ResultActions result) throws Exception {
        return result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").isString())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("The request body or parameters are invalid."))
                .andExpect(jsonPath("$.instance").value(ENDPOINT))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("testRunId"));
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private void assertSingleScopedDeleteWithoutEntityLoads() {
        List<String> statements = SqlCaptureInspector.statements().stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim())
                .toList();
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0))
                .startsWith("delete ")
                .contains(" from traffic_data ")
                .matches(".*\\bwhere\\b.*test_run_id\\s*=\\s*\\?.*");
        assertThat(statistics().getEntityLoadCount()).isZero();
    }
}
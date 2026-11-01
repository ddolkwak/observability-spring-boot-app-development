package com.example.spring_app;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(TrafficTestDatabaseGuard.class)
class SpringAppApplicationTests {

    private static final Pattern COUNT_QUERY = Pattern.compile("\\bcount\\s*\\(");

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
    void postCreatesRecordAndReturnsServerGeneratedValues() throws Exception {
        mvc.perform(post("/api/traffic")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"read-write-test\"}")
                .with(request -> {
                    request.setRemoteAddr("192.0.2.10");
                    return request;
                }))
            .andExpect(status().isCreated())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.message").value("read-write-test"))
            .andExpect(jsonPath("$.clientIp").value("192.0.2.10"));

        assertThat(recordCount()).isEqualTo(1L);
        Map<String, Object> stored = jdbc.queryForMap(
            "select message, client_ip from traffic_data");
        assertThat(stored.get("message")).isEqualTo("read-write-test");
        assertThat(stored.get("client_ip")).isEqualTo("192.0.2.10");
    }

    @Test
    void acceptsMessageAt255CharacterLimit() throws Exception {
        String message = "a".repeat(255);
        mvc.perform(post("/api/traffic")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"" + message + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.message").value(message));
        assertThat(recordCount()).isEqualTo(1L);
    }

    static Stream<String> invalidMessages() {
        return Stream.of(
            "{}",
            "{\"message\":null}",
            "{\"message\":\"\"}",
            "{\"message\":\"   \"}",
            "{\"message\":\"" + "a".repeat(256) + "\"}"
        );
    }

    @ParameterizedTest
    @MethodSource("invalidMessages")
    void rejectsInvalidMessagesWithoutWriting(String body) throws Exception {
        expectProblem(mvc.perform(post("/api/traffic")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)), 400, "VALIDATION_ERROR")
            .andExpect(jsonPath("$.errors[0].field").value("message"));
        assertThat(recordCount()).isZero();
    }

    @Test
    void malformedJsonReturns400WithoutWriting() throws Exception {
        expectProblem(mvc.perform(post("/api/traffic")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":")), 400, "MALFORMED_JSON");
        assertThat(recordCount()).isZero();
    }

    @Test
    void unsupportedMethodAndContentTypeHaveProblemResponses() throws Exception {
        expectProblem(mvc.perform(put("/api/traffic")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"test\"}")), 405, null);

        expectProblem(mvc.perform(post("/api/traffic")
                .contentType(MediaType.TEXT_PLAIN)
                .content("test")), 415, null);

        assertThat(recordCount()).isZero();
    }

    @Test
    void removedGetCreationEndpointCannotWrite() throws Exception {
        seed(3);
        expectProblem(mvc.perform(get("/api/generate-insert")), 404, null);
        assertThat(recordCount()).isEqualTo(3L);
    }

    @ParameterizedTest
    @CsvSource({"0,0,false", "20,20,false", "21,20,true", "1000,20,true", "10000,20,true"})
    void defaultReadRemainsBoundedAsStoredDataGrows(
            int stored, int returned, boolean hasNext) throws Exception {
        seed(stored);
        SqlCaptureInspector.clear();
        statistics().clear();

        mvc.perform(get("/api/traffic"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(returned)))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.numberOfElements").value(returned))
            .andExpect(jsonPath("$.hasNext").value(hasNext));

        assertBoundedSelectWithoutCount();
        assertThat(statistics().getEntityLoadCount())
            .as("The default Slice may materialize at most size + 1 entities")
            .isLessThanOrEqualTo(21L);
        assertThat(recordCount()).isEqualTo(stored);
    }

    @Test
    void acceptsMaximumSizeAndLoadsAtMostOneExtraEntity() throws Exception {
        seed(1000);
        SqlCaptureInspector.clear();
        statistics().clear();

        mvc.perform(get("/api/traffic").param("page", "0").param("size", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(100)))
            .andExpect(jsonPath("$.size").value(100))
            .andExpect(jsonPath("$.numberOfElements").value(100))
            .andExpect(jsonPath("$.hasNext").value(true));

        assertBoundedSelectWithoutCount();
        assertThat(statistics().getEntityLoadCount()).isLessThanOrEqualTo(101L);
    }

    @Test
    void pagesFollowIdOrderWithoutDuplicatesOrGapsOnUnchangedData() throws Exception {
        seed(45);
        List<Long> expected = jdbc.queryForList(
            "select id from traffic_data order by id asc", Long.class);
        List<Long> actual = new ArrayList<>();

        for (int page = 0; page < 3; page++) {
            int expectedSize = page < 2 ? 20 : 5;
            String body = mvc.perform(get("/api/traffic")
                    .param("page", Integer.toString(page))
                    .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(page))
                .andExpect(jsonPath("$.items", hasSize(expectedSize)))
                .andExpect(jsonPath("$.hasNext").value(page < 2))
                .andReturn().getResponse().getContentAsString();

            List<Number> ids = JsonPath.read(body, "$.items[*].id");
            actual.addAll(ids.stream().map(Number::longValue).toList());
        }

        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(actual).doesNotHaveDuplicates();
    }

    @Test
    void pageBeyondAvailableResultsReturnsAnEmptySuccessfulResponse() throws Exception {
        seed(45);
        mvc.perform(get("/api/traffic").param("page", "3").param("size", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(0)))
            .andExpect(jsonPath("$.page").value(3))
            .andExpect(jsonPath("$.numberOfElements").value(0))
            .andExpect(jsonPath("$.hasNext").value(false));
    }

    static Stream<Arguments> invalidQueryParameters() {
        return Stream.of(
            Arguments.of("page", "-1", "VALIDATION_ERROR"),
            Arguments.of("size", "0", "VALIDATION_ERROR"),
            Arguments.of("size", "-1", "VALIDATION_ERROR"),
            Arguments.of("size", "101", "VALIDATION_ERROR"),
            Arguments.of("page", "2147483647", "VALIDATION_ERROR"),
            Arguments.of("page", "abc", "INVALID_PARAMETER"),
            Arguments.of("size", "abc", "INVALID_PARAMETER"),
            Arguments.of("page", "2147483648", "INVALID_PARAMETER"),
            Arguments.of("size", "2147483648", "INVALID_PARAMETER")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidQueryParameters")
    void invalidPaginationIsRejectedBeforeReading(
            String parameter, String value, String code) throws Exception {
        seed(3);
        SqlCaptureInspector.clear();

        expectProblem(mvc.perform(get("/api/traffic").param(parameter, value)), 400, code);

        assertThat(SqlCaptureInspector.statements())
            .as("Invalid pagination must not execute a Hibernate query")
            .isEmpty();
        assertThat(recordCount()).isEqualTo(3L);
    }

    @Test
    void fullScanIsDisabledByDefault() throws Exception {
        seed(3);
        SqlCaptureInspector.clear();

        expectProblem(mvc.perform(get("/api/experiments/traffic/full-scan")), 404, null);

        assertThat(SqlCaptureInspector.statements()).isEmpty();
        assertThat(recordCount()).isEqualTo(3L);
    }

    @Test
    void testRunIdAndDatabaseGeneratedTimeRoundTripThroughCreateStorageAndRead() throws Exception {
        String runId = "Run-A_2026.10-29";
        String body = mvc.perform(post("/api/traffic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"metadata-test\",\"testRunId\":\"" + runId + "\"}")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.10");
                            return request;
                        }))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("metadata-test"))
                .andExpect(jsonPath("$.clientIp").value("192.0.2.10"))
                .andExpect(jsonPath("$.testRunId").value(runId))
                .andExpect(jsonPath("$.createdAt").isString())
                .andReturn().getResponse().getContentAsString();

        Number responseId = JsonPath.read(body, "$.id");
        long id = responseId.longValue();
        String createdAtText = JsonPath.read(body, "$.createdAt");
        String utcMicrosecondPattern =
                "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{6}Z";
        assertThat(createdAtText).matches(utcMicrosecondPattern);
        Instant createdAt = Instant.parse(createdAtText);
        Instant storedCreatedAt = jdbc.queryForObject(
                "select created_at from traffic_data where id = ?",
                (result, row) -> result.getObject(1, OffsetDateTime.class).toInstant(), id);

        assertThat(jdbc.queryForObject(
                "select test_run_id from traffic_data where id = ?", String.class, id))
                .isEqualTo(runId);
        assertThat(createdAt).isEqualTo(storedCreatedAt);

        String readBody = mvc.perform(get("/api/traffic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].testRunId").value(runId))
                .andExpect(jsonPath("$.items[0].createdAt").isString())
                .andReturn().getResponse().getContentAsString();
        String readCreatedAtText = JsonPath.read(readBody, "$.items[0].createdAt");
        assertThat(readCreatedAtText).matches(utcMicrosecondPattern);
        assertThat(Instant.parse(readCreatedAtText)).isEqualTo(storedCreatedAt);
    }

    static Stream<String> optionalRunIdRequests() {
        return Stream.of(
                "{\"message\":\"optional-run\"}",
                "{\"message\":\"optional-run\",\"testRunId\":null}");
    }

    @ParameterizedTest
    @MethodSource("optionalRunIdRequests")
    void omittedOrNullRunIdPreservesTheOriginalCreationContract(String requestBody) throws Exception {
        mvc.perform(post("/api/traffic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.testRunId").hasJsonPath())
                .andExpect(jsonPath("$.testRunId").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").isString());

        assertThat(recordCount()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select test_run_id from traffic_data", String.class)).isNull();
        assertThat(jdbc.queryForObject(
                "select created_at is not null from traffic_data", Boolean.class)).isTrue();
    }

    @Test
    void acceptsAMaximumLengthTestRunIdWithoutTruncation() throws Exception {
        String runId = "A" + "x".repeat(127);
        mvc.perform(post("/api/traffic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"maximum-run-id\",\"testRunId\":\"" + runId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.testRunId").value(runId));

        assertThat(jdbc.queryForObject("select test_run_id from traffic_data", String.class))
                .isEqualTo(runId);
    }

    static Stream<String> invalidMetadataRunIds() {
        return Stream.of("", " ", "run A", "run/A", "-run", "_run", ".run",
                "runé", "a".repeat(129), " run-A", "run-A ");
    }

    @ParameterizedTest
    @MethodSource("invalidMetadataRunIds")
    void rejectsAnInvalidMetadataRunIdWithoutWriting(String runId) throws Exception {
        expectProblem(mvc.perform(post("/api/traffic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"invalid-run-id\",\"testRunId\":\"" + runId + "\"}")),
                400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.errors[0].field").value("testRunId"));

        assertThat(SqlCaptureInspector.statements()).isEmpty();
        assertThat(recordCount()).isZero();
    }

    @Test
    void readsLegacyNullMetadataAsExplicitJsonNullWithoutChangingTheRecord() throws Exception {
        jdbc.update("""
                insert into traffic_data (message, client_ip, test_run_id, created_at)
                values ('legacy-record', '127.0.0.1', null, null)
                """);

        mvc.perform(get("/api/traffic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].message").value("legacy-record"))
                .andExpect(jsonPath("$.items[0].clientIp").value("127.0.0.1"))
                .andExpect(jsonPath("$.items[0].testRunId").hasJsonPath())
                .andExpect(jsonPath("$.items[0].testRunId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].createdAt").hasJsonPath())
                .andExpect(jsonPath("$.items[0].createdAt").value(nullValue()));

        assertThat(jdbc.queryForObject("select created_at from traffic_data", Object.class)).isNull();
        assertThat(recordCount()).isEqualTo(1L);
    }

    private ResultActions expectProblem(
            ResultActions result, int expectedStatus, String expectedCode) throws Exception {
        result.andExpect(status().is(expectedStatus))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").isString())
            .andExpect(jsonPath("$.title").isString())
            .andExpect(jsonPath("$.status").value(expectedStatus))
            .andExpect(jsonPath("$.detail").isString())
            .andExpect(jsonPath("$.instance").isString())
            .andExpect(jsonPath("$.code").isString())
            .andExpect(jsonPath("$.errors").isArray());
        if (expectedCode != null) {
            result.andExpect(jsonPath("$.code").value(expectedCode));
        }
        return result;
    }

    private void seed(int count) {
        if (count > 0) {
            jdbc.update("""
                insert into traffic_data (message, client_ip)
                select 'fixture-' || n, '127.0.0.1'
                from generate_series(1, ?) as n
                """, count);
        }
    }

    private long recordCount() {
        Long count = jdbc.queryForObject("select count(*) from traffic_data", Long.class);
        return count == null ? 0L : count;
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private void assertBoundedSelectWithoutCount() {
        List<String> statements = SqlCaptureInspector.statements().stream()
            .map(sql -> sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim())
            .toList();

        assertThat(statements).isNotEmpty();
        assertThat(statements).noneMatch(sql -> COUNT_QUERY.matcher(sql).find());
        assertThat(statements).anyMatch(sql ->
            sql.contains(" from traffic_data ") &&
                (sql.contains(" limit ") || sql.contains(" fetch first ") || sql.contains(" fetch next ")));
    }
}
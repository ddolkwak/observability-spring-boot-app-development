package com.example.spring_app;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "traffic.experiments.full-scan-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(TrafficTestDatabaseGuard.class)
class FullScanEnabledTests {

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
            .isEqualTo("traffic_test");
        jdbc.update("delete from traffic_data");
        SqlCaptureInspector.clear();
        entityManagerFactory.unwrap(SessionFactory.class).getStatistics().clear();
    }

    @Test
    void enabledExperimentActuallyReadsAllRows() throws Exception {
        jdbc.update("""
            insert into traffic_data (message, client_ip)
            select 'full-scan-' || n, '127.0.0.1'
            from generate_series(1, 135) as n
            """);
        SqlCaptureInspector.clear();
        entityManagerFactory.unwrap(SessionFactory.class).getStatistics().clear();

        mvc.perform(get("/api/experiments/traffic/full-scan"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.experiment").value("full-table-read"))
            .andExpect(jsonPath("$.recordsRead").value(135));

        List<String> statements = SqlCaptureInspector.statements().stream()
            .map(sql -> sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim())
            .toList();
        assertThat(statements).anyMatch(sql -> sql.contains(" from traffic_data "));
        assertThat(statements).noneMatch(sql -> COUNT_QUERY.matcher(sql).find());
        assertThat(statements).noneMatch(sql ->
            sql.contains(" limit ") || sql.contains(" fetch first ") ||
            sql.contains(" fetch next "));
        assertThat(entityManagerFactory.unwrap(SessionFactory.class)
            .getStatistics().getEntityLoadCount()).isEqualTo(135L);
        assertThat(jdbc.queryForObject("select count(*) from traffic_data", Long.class))
            .isEqualTo(135L);
    }

    @Test
    void enabledExperimentAlsoHandlesAnEmptyDatabase() throws Exception {
        mvc.perform(get("/api/experiments/traffic/full-scan"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.experiment").value("full-table-read"))
            .andExpect(jsonPath("$.recordsRead").value(0));
    }
}
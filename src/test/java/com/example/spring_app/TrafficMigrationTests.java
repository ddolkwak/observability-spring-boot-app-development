package com.example.spring_app;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(
        named = "TRAFFIC_MIGRATION_RESET_DB", matches = "traffic_test")
class TrafficMigrationTests {

    @Test
    void verifiesEmptyDatabaseThenPreservesExistingV1Records() throws Exception {
        String url = requiredEnvironment("SPRING_DATASOURCE_URL");
        String username = requiredEnvironment("SPRING_DATASOURCE_USERNAME");
        String password = requiredEnvironment("SPRING_DATASOURCE_PASSWORD");

        try (Connection control = DriverManager.getConnection(url, username, password)) {
            requireTestDatabase(control);
            requireExclusiveUse(control);
            resetOnlyApplicationTables(control);
            assertNoUserTables(control);

            Flyway latest = configuredFlyway(url, username, password, null);
            requireFlywayTestDatabase(latest);
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
            assertMetadataColumns(control);
            assertNewInsertMetadata(control, "empty-path");
            assertThat(latest.migrate().migrationsExecuted).isZero();

            requireExclusiveUse(control);
            resetOnlyApplicationTables(control);
            assertNoUserTables(control);

            Flyway v1 = configuredFlyway(url, username, password, "1");
            requireFlywayTestDatabase(v1);
            assertThat(v1.migrate().migrationsExecuted).isEqualTo(1);
            insertLegacyRecords(control);
            List<LegacyRow> before = readOriginalColumns(control);

            requireFlywayTestDatabase(latest);
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            assertMetadataColumns(control);
            assertThat(readOriginalColumns(control)).containsExactlyElementsOf(before);
            assertThat(scalar(control, """
                    select count(*) from public.traffic_data
                    where test_run_id is null and created_at is null
                    """)).isEqualTo(before.size());

            long newId = assertNewInsertMetadata(control, "existing-path");
            assertThat(newId).isGreaterThan(before.get(before.size() - 1).id());
            assertThat(scalar(control, "select count(*) from public.traffic_data"))
                    .isEqualTo(before.size() + 1L);
            assertThat(latest.migrate().migrationsExecuted).isZero();
        }
    }

    private static Flyway configuredFlyway(
            String url, String username, String password, String target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(url, username, password)
                .defaultSchema("public")
                .schemas("public")
                .locations("classpath:db/migration")
                .baselineOnMigrate(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static void requireFlywayTestDatabase(Flyway flyway) throws SQLException {
        try (Connection connection = flyway.getConfiguration()
                .getDataSource().getConnection()) {
            requireTestDatabase(connection);
        }
    }

    private static void requireTestDatabase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("select current_database()")) {
            if (!rows.next() || !"traffic_test".equals(rows.getString(1))) {
                throw new IllegalStateException(
                        "Migration tests may reset only the dedicated traffic_test database");
            }
        }
    }

    private static void requireExclusiveUse(Connection connection) throws SQLException {
        requireTestDatabase(connection);
        long otherClients = scalar(connection, """
                select count(*) from pg_stat_activity
                where datname = current_database()
                and pid <> pg_backend_pid()
                and backend_type = 'client backend'
                """);
        if (otherClients != 0) {
            throw new IllegalStateException(
                    "Stop all other traffic_test clients before the migration test");
        }
    }

    private static void resetOnlyApplicationTables(Connection connection)
            throws SQLException {
        requireTestDatabase(connection);
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("""
                select table_schema, table_name from information_schema.tables
                where table_schema not in ('pg_catalog', 'information_schema')
                    """)) {
            while (rows.next()) {
                String schema = rows.getString(1);
                String table = rows.getString(2);
                if (!"public".equals(schema)
                        || !List.of("traffic_data", "flyway_schema_history").contains(table)) {
                    throw new IllegalStateException(
                            "Unexpected user table: migration reset stopped");
                }
            }
        }

        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists public.traffic_data");
            statement.execute("drop table if exists public.flyway_schema_history");
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void assertNoUserTables(Connection connection) throws SQLException {
        assertThat(scalar(connection, """
                select count(*) from information_schema.tables
                where table_schema not in ('pg_catalog', 'information_schema')
                """)).isZero();
    }

    private static void assertMetadataColumns(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select data_type, datetime_precision
                from information_schema.columns
                where table_schema = 'public' and table_name = 'traffic_data'
                and column_name = 'created_at'
                """);
                ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("data_type")).isEqualTo("timestamp with time zone");
            assertThat(rows.getInt("datetime_precision")).isEqualTo(6);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                select data_type, character_maximum_length
                from information_schema.columns
                where table_schema = 'public' and table_name = 'traffic_data'
                and column_name = 'test_run_id'
                """);
                ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("data_type")).isEqualTo("character varying");
            assertThat(rows.getInt("character_maximum_length")).isEqualTo(128);
        }
    }

    private static long assertNewInsertMetadata(Connection connection, String testRunId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into public.traffic_data (message, client_ip, test_run_id)
                values (?, ?, ?)
                returning id, test_run_id, created_at
                """)) {
            statement.setString(1, "migration-probe");
            statement.setString(2, "192.0.2.40");
            statement.setString(3, testRunId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("test_run_id")).isEqualTo(testRunId);
                assertThat(rows.getObject("created_at", OffsetDateTime.class)).isNotNull();
                return rows.getLong("id");
            }
        }
    }

    private static void insertLegacyRecords(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into public.traffic_data (message, client_ip)
                values (?, ?), (?, ?)
                """)) {
            statement.setString(1, "legacy-one");
            statement.setString(2, "192.0.2.1");
            statement.setString(3, null);
            statement.setString(4, null);
            assertThat(statement.executeUpdate()).isEqualTo(2);
        }
    }

    private static List<LegacyRow> readOriginalColumns(Connection connection)
            throws SQLException {
        List<LegacyRow> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
            ResultSet result = statement.executeQuery("""
                select id, message, client_ip from public.traffic_data order by id
                    """)) {
            while (result.next()) {
                rows.add(new LegacyRow(result.getLong("id"),
                        result.getString("message"), result.getString("client_ip")));
            }
        }
        return rows;
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new IllegalStateException("Expected one result row");
            }
            return rows.getLong(1);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set");
        }
        return value;
    }

    private record LegacyRow(long id, String message, String clientIp) {}
}
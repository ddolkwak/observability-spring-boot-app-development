package com.example.spring_app;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class TrafficTestDatabaseGuard {

    @Bean
    FlywayMigrationStrategy migrateOnlyTrafficTest() {
        return flyway -> {
            try (Connection connection = flyway.getConfiguration()
                    .getDataSource().getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                    "select current_database()")) {
                if (!result.next() || !"traffic_test".equals(result.getString(1))) {
                    throw new IllegalStateException(
                        "Tests may migrate only the dedicated traffic_test database");
                }
            } catch (SQLException exception) {
                throw new IllegalStateException(
                    "Could not verify the test database before migration", exception);
            }
            flyway.migrate();
        };
    }
}
package com.gustler.backend.migration.db;

import com.gustler.backend.migration.DatabaseEnvironment;
import com.gustler.backend.migration.Sha256;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

abstract class PostgresMigrationTestSupport {

    static PostgreSQLContainer<?> postgres;
    static DatabaseEnvironment database;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer<>("postgres:18");
        postgres.start();
        database = new DatabaseEnvironment(
            DatabaseEnvironment.TargetKind.LOCAL,
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            Sha256.of("test-database"));
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    static Connection connection() throws SQLException {
        return DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}

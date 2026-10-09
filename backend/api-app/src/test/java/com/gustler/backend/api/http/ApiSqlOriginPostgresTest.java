package com.gustler.backend.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ApiSqlOriginPostgresTest {
    // CodeBuild에서 cgroup 설정 오류가 발생해 컨테이너별 CPU/메모리 제한은 지정하지 않는다.
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18")
        .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements",
            "-c", "shared_buffers=16MB");

    @BeforeEach void prepare() throws Exception {
        execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        execute("CREATE TABLE IF NOT EXISTS origin_check(id int)");
        execute("TRUNCATE origin_check");
        execute("INSERT INTO origin_check VALUES (1)");
        execute("SELECT pg_stat_statements_reset()");
    }

    @Test void PostgreSQL18은_SQL_맨_앞_주석을_통계에서_제외한다() throws Exception {
        execute("/* salmonbus:api.other */ SELECT id FROM origin_check");
        assertThat(storedQuery()).doesNotContain("salmonbus:");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT id FROM origin_check",
        "INSERT INTO origin_check VALUES (2)",
        "UPDATE origin_check SET id = 3",
        "DELETE FROM origin_check WHERE id = 1",
        "WITH rows AS (SELECT id FROM origin_check) SELECT * FROM rows"
    })
    void SQL_내부_표식은_실행_후_통계에도_남는다(String sql) throws Exception {
        execute(new ApiSqlOrigin().inspect(sql));
        assertThat(storedQuery()).contains("/* salmonbus:api.other */");
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String storedQuery() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                 "SELECT query FROM pg_stat_statements WHERE query LIKE '%origin_check%' AND toplevel")) {
            assertThat(rows.next()).isTrue();
            String sql = rows.getString(1);
            assertThat(rows.next()).isFalse();
            return sql;
        }
    }
}

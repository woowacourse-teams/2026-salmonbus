package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

class DataTransitionDatabaseTest {
    private static final PostgreSQLContainer database = new PostgreSQLContainer("postgres:18")
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withMemory(256L * 1024 * 1024).withNanoCPUs(1000000000L));
    private Connection connection;
    private String sql;

    @BeforeAll
    static void start() { database.start(); }

    @AfterAll
    static void stop() { database.stop(); }

    @BeforeEach
    void prepare() throws Exception {
        connection = connect("");
        try (var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS sal175_evaluation_transition, forecast_evaluation_pending, flyway_schema_history");
            statement.execute("CREATE TABLE flyway_schema_history(version varchar, success boolean)");
            statement.execute("INSERT INTO flyway_schema_history VALUES('33',true)");
            statement.execute("CREATE TABLE forecast_evaluation_pending(id bigint)");
            statement.execute("INSERT INTO forecast_evaluation_pending VALUES(1)");
        }
        try (var input = getClass().getResourceAsStream("/prepare-evaluation-work-transition.sql")) {
            assertNotNull(input);
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @AfterEach
    void close() throws Exception { connection.close(); }

    private Connection connect(String name) throws Exception {
        var properties = new java.util.Properties();
        properties.setProperty("user", database.getUsername());
        properties.setProperty("password", database.getPassword());
        properties.setProperty("ApplicationName", name);
        return DriverManager.getConnection(database.getJdbcUrl(), properties);
    }

    @Test
    void V38_패키지_없이_기존_대기를_보존하고_전환_승인만_저장한다() throws Exception {
        // given
        var backup = "dev-before-sal175";

        // when
        DataTransition.approve(connection, sql, backup);

        // then
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT backup_reference, expires_at>clock_timestamp()
                  AND expires_at<=clock_timestamp()+interval '30 minutes',
                  (SELECT count(*) FROM forecast_evaluation_pending)
                FROM sal175_evaluation_transition
                """)) {
            assertTrue(rows.next());
            assertEquals(backup, rows.getString(1));
            assertTrue(rows.getBoolean(2));
            assertEquals(1, rows.getInt(3));
        }
    }

    @Test
    void worker나_정비_작업이_DB에_연결돼_있으면_승인하지_않는다() throws Exception {
        for (var name : java.util.List.of("salmonbus-worker", "salmonbus-historical-migration")) {
            // given
            try (var writer = connect(name)) {
                // when & then
                assertThrows(IllegalArgumentException.class, () -> DataTransition.approve(connection, sql, "backup"));
                try (var statement = connection.createStatement();
                        var rows = statement.executeQuery("SELECT to_regclass('sal175_evaluation_transition') IS NULL")) {
                    assertTrue(rows.next());
                    assertTrue(rows.getBoolean(1));
                }
                connection.rollback();
            }
        }
    }

    @Test
    void 이미_새_스키마가_적용된_DB에는_전환_승인을_다시_만들지_않는다() throws Exception {
        // given
        try (var statement = connection.createStatement()) {
            statement.execute("INSERT INTO flyway_schema_history VALUES('39',true)");
        }

        // when & then
        assertThrows(IllegalArgumentException.class, () -> DataTransition.approve(connection, sql, "backup"));
    }
}

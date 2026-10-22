package com.gustler.backend.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 공유 PostgreSQL 서버 안에 만든 빈 DB 하나.
 *
 * <p>스키마는 만들지 않는다. 이 DB 에 처음 붙는 앱(Flyway)이 지금처럼 빈 DB 부터 마이그레이션한다.
 *
 * <p>{@link #close()} 는 DB 를 지운다. 연결이 남아 있으면 강제로 끊고 한 번 더 지운다. 그래도 실패하면 경고만 남긴다.
 * 남은 DB 는 JVM 이 끝날 때 서버와 함께 사라지고, 다른 시험은 자기 DB 만 보므로 결과에 영향이 없다.
 */
public final class TestDatabase implements AutoCloseable {
    private static final Log LOG = LogFactory.getLog(TestDatabase.class);

    private final PostgreSQLContainer server;
    private final String name;
    private boolean closed;

    private TestDatabase(PostgreSQLContainer server, String name) {
        this.server = server;
        this.name = name;
    }

    public static TestDatabase create() {
        PostgreSQLContainer server = SharedPostgres.server();
        String name = "t_" + UUID.randomUUID().toString().replace("-", "");
        try {
            execute(server, "CREATE DATABASE " + name);
        } catch (SQLException failure) {
            throw new IllegalStateException("시험 DB 를 만들지 못했다: " + name, failure);
        }
        return new TestDatabase(server, name);
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + server.getHost() + ":"
            + server.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + name;
    }

    public String username() {
        return server.getUsername();
    }

    public String password() {
        return server.getPassword();
    }

    public Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), username(), password());
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            execute(server, "DROP DATABASE IF EXISTS " + name);
        } catch (SQLException connected) {
            try {
                execute(server, "DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            } catch (SQLException failure) {
                LOG.warn("시험 DB 를 지우지 못했다: " + name, failure);
            }
        }
    }

    private static void execute(PostgreSQLContainer server, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(server.getJdbcUrl(), server.getUsername(),
            server.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}

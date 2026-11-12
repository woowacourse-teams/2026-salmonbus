package com.gustler.backend.maintenance.db;

import com.gustler.backend.maintenance.DatabaseEnvironment;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 검증된 유지보수 DB 설정으로 연결 공급자를 구성한다.
 * 대상 선택과 승인 판단은 호출 경계에서 먼저 수행한다.
 */
public final class DatabaseConnections {
    private DatabaseConnections() {
    }

    /** 서버 세션 기본값으로 쓰기를 거부한다. JDBC의 읽기 전용 힌트에만 의존하지 않는다. */
    public static DataSource readOnlyDataSource(DatabaseEnvironment environment) {
        DriverManagerDataSource source = new DriverManagerDataSource(
            environment.jdbcUrl(), environment.username(), environment.password());
        Properties properties = new Properties();
        properties.setProperty("ApplicationName", "salmonbus-alighting-history-bootstrap");
        properties.setProperty("options", "-c default_transaction_read_only=on");
        properties.setProperty("readOnlyMode", "always");
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "20");
        source.setConnectionProperties(properties);
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return readOnly(source.getConnection());
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return readOnly(source.getConnection(username, password));
            }

            private Connection readOnly(Connection connection) throws SQLException {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
                    statement.execute("SET statement_timeout=\'15s\'");
                    statement.execute("SET lock_timeout=\'1s\'");
                    connection.setReadOnly(true);
                    return connection;
                } catch (SQLException failure) {
                    try {
                        connection.close();
                    } catch (SQLException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                    throw failure;
                }
            }
        };
    }

    public static DataSource dataSource(DatabaseEnvironment environment) {
        DriverManagerDataSource source = new DriverManagerDataSource(
            environment.jdbcUrl(), environment.username(), environment.password());
        Properties properties = new Properties();
        properties.setProperty("ApplicationName", "salmonbus-historical-migration");
        source.setConnectionProperties(properties);
        return source;
    }
}

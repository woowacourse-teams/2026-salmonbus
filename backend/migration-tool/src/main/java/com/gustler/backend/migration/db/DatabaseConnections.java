package com.gustler.backend.migration.db;

import com.gustler.backend.migration.DatabaseEnvironment;
import com.gustler.backend.migration.MigrationException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class DatabaseConnections {

    private DatabaseConnections() {
    }

    public static Connection open(
        DatabaseEnvironment environment
    ) {
        Properties properties = new Properties();
        properties.setProperty("user", environment.username());
        properties.setProperty("password", environment.password());
        properties.setProperty("ApplicationName", "salmonbus-historical-migration");
        try {
            return DriverManager.getConnection(environment.jdbcUrl(), properties);
        } catch (SQLException e) {
            throw new MigrationException("DATABASE_CONNECTION_FAILED", e);
        }
    }

    public static <T> T transaction(
        DatabaseEnvironment environment,
        SqlWork<T> work
    ) {
        try (Connection connection = open(environment)) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception e) {
                connection.rollback();
                if (e instanceof MigrationException migration) {
                    throw migration;
                }
                throw new MigrationException("DATABASE_TRANSACTION_FAILED", e);
            }
        } catch (SQLException e) {
            throw new MigrationException("DATABASE_TRANSACTION_FAILED", e);
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {

        T run(
            Connection connection
        ) throws Exception;
    }
}

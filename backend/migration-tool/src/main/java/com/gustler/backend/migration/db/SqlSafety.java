package com.gustler.backend.migration.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

final class SqlSafety {

    private SqlSafety() {
    }

    static void setLocalTimeouts(
        Connection connection,
        ImportSettings settings
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT set_config('lock_timeout', ?, true), set_config('statement_timeout', ?, true)")) {
            statement.setString(1, settings.lockTimeoutMillis() + "ms");
            statement.setString(2, settings.statementTimeoutSeconds() + "s");
            statement.executeQuery().close();
        }
    }

    static void setTransactionReadOnly(
        Connection connection
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SET TRANSACTION READ ONLY")) {
            statement.execute();
        }
    }
}

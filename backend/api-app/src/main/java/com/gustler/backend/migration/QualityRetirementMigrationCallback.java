package com.gustler.backend.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

@Component
public final class QualityRetirementMigrationCallback implements Callback {
    private static final MigrationVersion VERSION = MigrationVersion.fromVersion("66");
    private final Map<Connection, PlanSettings> previous = new ConcurrentHashMap<>();

    @Override
    public boolean supports(Event event, Context context) {
        return (event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE
            || event == Event.AFTER_EACH_MIGRATE_ERROR)
            && context.getMigrationInfo() != null && VERSION.equals(context.getMigrationInfo().getVersion());
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        Connection connection = context.getConnection();
        if (event == Event.AFTER_EACH_MIGRATE_ERROR) {
            previous.remove(connection);
            return;
        }
        try {
            if (event == Event.BEFORE_EACH_MIGRATE) {
                if (connection.getAutoCommit()) {
                    throw new FlywayException("V66 query plan settings require a migration transaction");
                }
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT current_setting('enable_hashjoin'), current_setting('enable_mergejoin')")) {
                    result.next();
                    previous.put(connection, new PlanSettings(result.getString(1), result.getString(2)));
                }
                apply(connection, new PlanSettings("off", "off"));
                return;
            }
            PlanSettings settings = previous.remove(connection);
            if (settings != null) {
                apply(connection, settings);
            }
        } catch (SQLException failure) {
            previous.remove(connection);
            throw new FlywayException("Cannot apply V66 migration query plan settings", failure);
        }
    }

    private static void apply(Connection connection, PlanSettings settings) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT set_config('enable_hashjoin', ?, true), set_config('enable_mergejoin', ?, true)")) {
            statement.setString(1, settings.hashJoin());
            statement.setString(2, settings.mergeJoin());
            statement.execute();
        }
    }

    @Override
    public String getCallbackName() {
        return "qualityRetirementMigrationQueryPlan";
    }

    private record PlanSettings(String hashJoin, String mergeJoin) { }
}

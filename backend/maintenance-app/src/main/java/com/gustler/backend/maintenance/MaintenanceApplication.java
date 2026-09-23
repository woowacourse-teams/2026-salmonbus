package com.gustler.backend.maintenance;

import com.gustler.backend.maintenance.db.DatabaseConnections;
import com.gustler.backend.forecasting.infrastructure.quality.TripQualityMaintenance;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

public final class MaintenanceApplication {

    private MaintenanceApplication() {
    }

    public static void main(String[] rawArguments) {
        int exit;
        try {
            Object result = run(CliArguments.parse(rawArguments));
            System.out.println(CanonicalJson.stringOf(Map.of("status", "succeeded", "result", result)));
            exit = 0;
        } catch (MaintenanceException e) {
            System.err.println(CanonicalJson.stringOf(Map.of("status", "failed", "code", e.code())));
            exit = 1;
        } catch (RuntimeException e) {
            System.err.println(CanonicalJson.stringOf(
                Map.of("status", "failed", "code", "UNEXPECTED_SANITIZED_FAILURE")));
            exit = 1;
        }
        System.exit(exit);
    }

    static Object run(
        CliArguments arguments
    ) {
        return switch (arguments.command()) {
            case "quality-preview", "quality-rebuild", "quality-status" -> quality(arguments);
            default -> throw new MaintenanceException("UNKNOWN_COMMAND");
        };
    }

    private static Object quality(CliArguments arguments) {
        Configuration configuration = Configuration.load(arguments.requiredPath("config"));
        DatabaseEnvironment environment = DatabaseEnvironment.load(configuration);
        long version = Long.parseLong(arguments.required("route-version"));
        boolean write = arguments.command().equals("quality-rebuild");
        if (write && environment.targetKind() != DatabaseEnvironment.TargetKind.LOCAL) {
            approval(arguments, ApprovalGate.ACADEMY_TRIP_QUALITY_REBUILD, null, environment.identitySha256());
        }
        return DatabaseConnections.transaction(environment, connection -> {
            connection.setReadOnly(!write);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '100ms'");
                statement.execute("SET LOCAL statement_timeout = '500ms'");
            }
            var jdbc = JdbcClient.create(
                new SingleConnectionDataSource(connection, true));
            var maintenance = new TripQualityMaintenance(jdbc);
            if (arguments.command().equals("quality-status")) {
                return maintenance.status(version);
            }
            var until = Instant.parse(arguments.required("until"));
            return write ? maintenance.applyChunk(version, until, Integer.parseInt(arguments.required("batch-limit")))
                : maintenance.preview(version, until);
        });
    }

    private static void approval(
        CliArguments arguments,
        String action,
        String artifactSha256,
        String databaseIdentitySha256
    ) {
        new ApprovalGate(Clock.systemUTC()).require(
            arguments.requiredPath("approval"), action, artifactSha256, databaseIdentitySha256);
    }

}

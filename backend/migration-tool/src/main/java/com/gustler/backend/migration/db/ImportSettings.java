package com.gustler.backend.migration.db;

import com.gustler.backend.migration.Configuration;
import com.gustler.backend.migration.DatabaseEnvironment;
import com.gustler.backend.migration.MigrationException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

public record ImportSettings(
    DatabaseEnvironment database,
    Path archiveDirectory,
    Instant earliestTargetAuthorityFrom,
    Map<String, Instant> targetAuthorityFrom,
    RouteValidityPolicy routeValidityPolicy,
    int batchRecords,
    int jdbcBatchRows,
    int maxTransactionObservationRows,
    int throttleMillis,
    int lockTimeoutMillis,
    int statementTimeoutSeconds,
    long minimumLocalFreeBytes,
    long confirmedDatabaseFreeBytes,
    Path routeSeed1650File,
    Path receiptOutput
) {

    public enum RouteValidityPolicy {
        EXTEND_EXACT_CURRENT_VERSION,
    }

    public ImportSettings {
        targetAuthorityFrom = Map.copyOf(targetAuthorityFrom);
        if (database == null || archiveDirectory == null || earliestTargetAuthorityFrom == null
            || routeValidityPolicy == null || batchRecords < 1 || jdbcBatchRows < 1
            || maxTransactionObservationRows < 1
            || throttleMillis < 0 || lockTimeoutMillis < 1 || statementTimeoutSeconds < 1
            || minimumLocalFreeBytes < 0 || confirmedDatabaseFreeBytes < 0) {
            throw new MigrationException("IMPORT_SETTINGS_INVALID");
        }
        if (!targetAuthorityFrom.keySet().equals(java.util.Set.of("1650", "3330"))
            || targetAuthorityFrom.values().stream().anyMatch(java.util.Objects::isNull)
            || !earliestTargetAuthorityFrom.equals(
                targetAuthorityFrom.values().stream().min(Instant::compareTo).orElseThrow())) {
            throw new MigrationException("IMPORT_ROUTE_AUTHORITY_INVALID");
        }
    }

    public Instant targetAuthorityFromFor(
        String modelRoute
    ) {
        Instant authority = targetAuthorityFrom.get(modelRoute);
        if (authority == null) {
            throw new MigrationException("IMPORT_ROUTE_AUTHORITY_MISSING");
        }
        return authority;
    }

    public static ImportSettings load(
        Configuration configuration
    ) {
        RouteValidityPolicy validityPolicy;
        try {
            validityPolicy = RouteValidityPolicy.valueOf(
                configuration.required("import.route-validity-policy").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new MigrationException("CONFIG_INVALID_ROUTE_VALIDITY_POLICY", e);
        }
        String receipt = configuration.optional("import.receipt-output");
        String routeSeed = configuration.optional("route.seed-1650-file");
        Map<String, Instant> authority = Map.of(
            "3330", configuration.requiredInstant("import.target-authority-from.3330"),
            "1650", configuration.requiredInstant("import.target-authority-from.1650"));
        return new ImportSettings(
            DatabaseEnvironment.load(configuration),
            configuration.requiredPath("archive.directory"),
            authority.values().stream().min(Instant::compareTo).orElseThrow(),
            authority,
            validityPolicy,
            configuration.integer("import.batch-records", 100, 1, 5_000),
            configuration.integer("import.jdbc-batch-rows", 500, 1, 10_000),
            configuration.integer("import.max-transaction-observation-rows", 10_000, 1, 10_000),
            configuration.integer("import.throttle-millis", 100, 0, 60_000),
            configuration.integer("import.lock-timeout-millis", 2_000, 100, 60_000),
            configuration.integer("import.statement-timeout-seconds", 30, 1, 3_600),
            configuration.longValue("import.minimum-local-free-bytes", 5L * 1024 * 1024 * 1024, 0),
            configuration.longValue("import.confirmed-database-free-bytes", 0, 0),
            routeSeed == null ? null : Path.of(routeSeed).toAbsolutePath().normalize(),
            receipt == null ? null : Path.of(receipt).toAbsolutePath().normalize());
    }
}

package com.gustler.backend.migration.db;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.SecureFiles;
import com.gustler.backend.migration.Sha256;
import com.gustler.backend.processor.StopDemandAggregator;
import com.gustler.backend.processor.StopDemandHourlyTotals;
import com.gustler.backend.processor.StopDemandMeasurement;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class AggregateSeedCutover {

    private static final String PLAN_SCHEMA = "stop-demand-seed-cutover-plan-v1";
    private static final String APPLY_RECEIPT_SCHEMA = "stop-demand-seed-apply-v1";
    private static final String ROLLBACK_RECEIPT_SCHEMA = "stop-demand-seed-rollback-v1";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AggregateSeedReader reader;
    private final RdsObservationDeltaBuilder deltaBuilder;

    public AggregateSeedCutover() {
        this(new AggregateSeedReader(), new RdsObservationDeltaBuilder());
    }

    AggregateSeedCutover(
        AggregateSeedReader reader,
        RdsObservationDeltaBuilder deltaBuilder
    ) {
        this.reader = reader;
        this.deltaBuilder = deltaBuilder;
    }

    public PlanResult dryRun(
        ImportSettings settings,
        Path seedPath,
        Path seedReceiptPath,
        Path output
    ) {
        AggregateSeedPayload source = reader.read(seedPath, seedReceiptPath);
        SeedPlan plan = DatabaseConnections.transaction(settings.database(), connection -> {
            configureReplayTransaction(connection);
            SqlSafety.setLocalTimeouts(connection, settings);
            TemporaryReleaseMaintenance.CutoverBoundary boundary =
                TemporaryReleaseMaintenance.requireControlPaused(connection);
            TemporaryReleaseMaintenance.requireTemporaryActiveOnly(connection);
            TemporaryReleaseMaintenance.requireFreezeForBoundary(
                connection, boundary, "CLEANED");
            Instant computedAt = databaseNow(connection);
            return buildPlan(connection, source, boundary, computedAt, Map.of());
        });
        SecureFiles.writeNew(output, plan.bytes());
        return plan.result(false, false);
    }

    public PlanResult apply(
        ImportSettings settings,
        Path seedPath,
        Path seedReceiptPath,
        Path planPath,
        Path receiptOutput
    ) {
        AggregateSeedPayload source = reader.read(seedPath, seedReceiptPath);
        PlanAuthority authority = readPlan(planPath);
        SeedPlan verified = DatabaseConnections.transaction(settings.database(), connection -> {
            configureReplayTransaction(connection);
            SqlSafety.setLocalTimeouts(connection, settings);
            TemporaryReleaseMaintenance.CutoverBoundary boundary =
                TemporaryReleaseMaintenance.requireControlPaused(connection);
            TemporaryReleaseMaintenance.requireTemporaryActiveOnly(connection);
            TemporaryReleaseMaintenance.requireFreezeForBoundary(
                connection, boundary, "CLEANED");
            SeedPlan plan = buildPlan(
                connection, source, boundary, authority.computedAt(), authority.revisions());
            if (!java.util.Arrays.equals(plan.bytes(), authority.bytes())) {
                throw new MigrationException("SEED_PLAN_REVALIDATION_MISMATCH");
            }
            return plan;
        });
        ApplyOutcome outcome = DatabaseConnections.transaction(settings.database(), connection -> {
            SqlSafety.setLocalTimeouts(connection, settings);
            TemporaryReleaseMaintenance.CutoverBoundary boundary =
                TemporaryReleaseMaintenance.requireControlPaused(connection);
            TemporaryReleaseMaintenance.requireTemporaryActiveOnly(connection);
            TemporaryReleaseMaintenance.requireFreezeForBoundary(
                connection, boundary, "CLEANED");
            if (!boundary.equals(verified.boundary())) {
                throw new MigrationException("SEED_PLAN_CUTOVER_BOUNDARY_CHANGED");
            }
            return applyPlan(connection, verified);
        });
        Map<String, Object> receipt = applyReceipt(outcome.plan(), outcome.alreadyApplied());
        if (receiptOutput != null) {
            SecureFiles.writeNew(receiptOutput, CanonicalJson.bytesOf(receipt));
        }
        return outcome.plan().result(true, outcome.alreadyApplied());
    }

    public RollbackResult rollback(
        ImportSettings settings,
        String planSha256,
        boolean execute,
        Path receiptOutput
    ) {
        if (!Sha256.isDigest(planSha256)) {
            throw new MigrationException("SEED_PLAN_SHA256_INVALID");
        }
        RollbackResult result = DatabaseConnections.transaction(settings.database(), connection -> {
            if (!execute) {
                SqlSafety.setTransactionReadOnly(connection);
            }
            SqlSafety.setLocalTimeouts(connection, settings);
            TemporaryReleaseMaintenance.CutoverBoundary boundary =
                TemporaryReleaseMaintenance.requireControlPaused(connection);
            TemporaryReleaseMaintenance.requireTemporaryActiveOnly(connection);
            SeedLedger ledger = ledger(connection, planSha256);
            requireLedgerBoundary(ledger, boundary);
            long hourlyRows = countHourlyRows(connection, ledger.id());
            long generationRows = countGenerationRows(connection, ledger.id());
            long statisticsRows = countStatisticsRows(connection, ledger.id());
            if (execute && "APPLIED".equals(ledger.status())) {
                deleteAppliedSeed(connection, ledger.id());
                markRolledBack(connection, ledger.id(), "OPERATOR_ROLLBACK");
            }
            return new RollbackResult(
                planSha256, execute, "ROLLED_BACK".equals(ledger.status()),
                hourlyRows, generationRows, statisticsRows);
        });
        if (receiptOutput != null) {
            SecureFiles.writeNew(receiptOutput, CanonicalJson.bytesOf(rollbackReceipt(result)));
        }
        return result;
    }

    static void requireAppliedForBoundary(
        Connection connection,
        TemporaryReleaseMaintenance.CutoverBoundary boundary
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id
            FROM stop_demand_seed_import
            WHERE status = 'APPLIED'
              AND calculation_version = ? AND final_cutover_at = ?
              AND observation_batch_high_water = ?
            """)) {
            statement.setString(1, AggregateSeedReader.CALCULATION_VERSION);
            statement.setObject(2, offset(boundary.finalCutoverAt()));
            statement.setLong(3, boundary.observationBatchHighWater());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new MigrationException("FORMAL_SEED_MISSING");
                }
                UUID seedId = rows.getObject(1, UUID.class);
                if (rows.next() || countGenerationRows(connection, seedId) != 2
                    || countStatisticsRows(connection, seedId) == 0) {
                    throw new MigrationException("FORMAL_SEED_INCOMPLETE");
                }
            }
        }
    }

    static void rollbackForTemporaryRecovery(
        Connection connection,
        TemporaryReleaseMaintenance.CutoverBoundary boundary
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id FROM stop_demand_seed_import
            WHERE status = 'APPLIED' AND final_cutover_at = ?
              AND observation_batch_high_water = ?
            """)) {
            statement.setObject(1, offset(boundary.finalCutoverAt()));
            statement.setLong(2, boundary.observationBatchHighWater());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return;
                }
                UUID seedId = rows.getObject(1, UUID.class);
                if (rows.next()) {
                    throw new MigrationException("SEED_RECOVERY_ACTIVE_IMPORT_AMBIGUOUS");
                }
                deleteAppliedSeed(connection, seedId);
                markRolledBack(connection, seedId, "TEMPORARY_RECOVERY");
            }
        }
    }

    static void recordFormalActivation(
        Connection connection,
        TemporaryReleaseMaintenance.CutoverBoundary boundary,
        long formalDeploymentId,
        Instant formalActivatedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE stop_demand_seed_import
            SET formal_deployment_id = ?, formal_activated_at = ?, unpaused_at = clock_timestamp()
            WHERE status = 'APPLIED' AND final_cutover_at = ?
              AND observation_batch_high_water = ? AND formal_deployment_id IS NULL
            """)) {
            statement.setLong(1, formalDeploymentId);
            statement.setObject(2, offset(formalActivatedAt));
            statement.setObject(3, offset(boundary.finalCutoverAt()));
            statement.setLong(4, boundary.observationBatchHighWater());
            if (statement.executeUpdate() != 1) {
                throw new MigrationException("FORMAL_ACTIVATION_SEED_LINK_FAILED");
            }
        }
    }

    private SeedPlan buildPlan(
        Connection connection,
        AggregateSeedPayload source,
        TemporaryReleaseMaintenance.CutoverBoundary boundary,
        Instant computedAt,
        Map<String, Integer> expectedRevisions
    ) throws SQLException {
        if (computedAt.isBefore(boundary.finalCutoverAt())) {
            throw new MigrationException("SEED_PLAN_COMPUTED_AT_INVALID");
        }
        RdsObservationDeltaBuilder.Delta delta = deltaBuilder.build(connection, source, boundary);
        MergedSeed merged = new MergedSeed(
            delta.finalRows(), delta.finalRowsSha256(), delta.finalPrimaryKeySha256(),
            delta.finalAggregates());
        Map<String, Long> routeVersions = routeVersions(connection);
        ArrayList<GenerationPlan> generations = new ArrayList<>();
        for (String route : List.of("1650", "3330")) {
            long routeVersionId = routeVersions.get(route);
            List<StopDemandHourlyTotals> hourly = merged.rows().stream()
                .filter(row -> route.equals(row.modelRoute()))
                .map(row -> new StopDemandHourlyTotals(
                    row.stopOrder(), row.arrivalHourStart(), row.fillRateTotal().doubleValue(),
                    row.netBoardingTotal().doubleValue(), row.capacityTotal().doubleValue(), row.sampleCount()))
                .toList();
            List<StopDemandMeasurement> cells = StopDemandAggregator.aggregate(
                hourly, Clock.fixed(computedAt, KST)).stream()
                .sorted(Comparator
                    .comparing((StopDemandMeasurement value) -> value.timeSlot().name())
                    .thenComparingInt(value -> value.cell().stopOrder()))
                .toList();
            if (cells.isEmpty()) {
                throw new MigrationException("SEED_GENERATION_EMPTY");
            }
            Integer expectedRevision = expectedRevisions.get(route);
            int revision = expectedRevision == null
                ? nextNeverUsedRevision(connection, routeVersionId, source.calculationVersion())
                : expectedRevision;
            String cellSha256 = Sha256.of(CanonicalJson.bytesOf(
                cells.stream().map(AggregateSeedCutover::cellMap).toList()));
            generations.add(new GenerationPlan(
                route, routeVersionId, revision, boundary.finalCutoverAt(), computedAt,
                cellSha256, cells));
        }
        requireNoFrozenGenerationIntersection(connection, generations);
        Map<String, Object> document = planDocument(
            source, delta, merged, boundary, computedAt, generations);
        byte[] bytes = CanonicalJson.bytesOf(document);
        return new SeedPlan(
            UUID.nameUUIDFromBytes(("salmonbus-stop-demand-seed\n" + Sha256.of(bytes))
                .getBytes(StandardCharsets.UTF_8)),
            Sha256.of(bytes), bytes, document, source, delta, merged, List.copyOf(generations));
    }

    private static Map<String, Long> routeVersions(
        Connection connection
    ) throws SQLException {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT route.display_name, version.id
            FROM route JOIN route_version version ON version.route_id = route.id
            WHERE route.display_name IN ('1650', '3330') AND version.valid_to IS NULL
            ORDER BY route.display_name
            """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                if (result.put(rows.getString(1), rows.getLong(2)) != null) {
                    throw new MigrationException("SEED_ROUTE_VERSION_AMBIGUOUS");
                }
            }
        }
        if (!result.keySet().equals(java.util.Set.of("1650", "3330"))) {
            throw new MigrationException("SEED_ROUTE_BINDING_INVALID");
        }
        return Map.copyOf(result);
    }

    private static int nextNeverUsedRevision(
        Connection connection,
        long routeVersionId,
        String calculationVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT COALESCE(max(revision), 0) + 1
            FROM (
                SELECT revision FROM stop_demand_statistics
                WHERE route_version_id = ? AND calculation_version = ?
                UNION ALL
                SELECT revision FROM training_statistics_generation_exclusion
                WHERE route_version_id = ? AND calculation_version = ?
                UNION ALL
                SELECT revision FROM stop_demand_seed_generation
                WHERE route_version_id = ? AND calculation_version = ?
            ) revisions
            """)) {
            statement.setLong(1, routeVersionId);
            statement.setString(2, calculationVersion);
            statement.setLong(3, routeVersionId);
            statement.setString(4, calculationVersion);
            statement.setLong(5, routeVersionId);
            statement.setString(6, calculationVersion);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static void requireNoFrozenGenerationIntersection(
        Connection connection,
        List<GenerationPlan> generations
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT count(*)
            FROM training_statistics_generation_exclusion
            WHERE route_version_id = ? AND calculation_version = ? AND revision = ?
              AND data_until = ? AND computed_at = ?
            """)) {
            for (GenerationPlan generation : generations) {
                statement.setLong(1, generation.routeVersionId());
                statement.setString(2, AggregateSeedReader.CALCULATION_VERSION);
                statement.setInt(3, generation.revision());
                statement.setObject(4, offset(generation.dataUntil()));
                statement.setObject(5, offset(generation.computedAt()));
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    if (rows.getLong(1) != 0) {
                        throw new MigrationException("SEED_GENERATION_FROZEN_KEY_INTERSECTION");
                    }
                }
            }
        }
    }

    private static Map<String, Object> planDocument(
        AggregateSeedPayload source,
        RdsObservationDeltaBuilder.Delta delta,
        MergedSeed merged,
        TemporaryReleaseMaintenance.CutoverBoundary boundary,
        Instant computedAt,
        List<GenerationPlan> generations
    ) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", PLAN_SCHEMA);
        value.put("calculationVersion", source.calculationVersion());
        value.put("finalCutoverAt", boundary.finalCutoverAt().toString());
        value.put("observationBatchHighWater", boundary.observationBatchHighWater());
        value.put("computedAt", computedAt.toString());
        value.put("generatedAtGuardSeconds", source.generatedGuardSeconds());
        value.put("settlementAvailabilityGuardSeconds", source.settlementGuardSeconds());
        value.put("source", Map.of(
            "seedSha256", source.compressedSha256(),
            "receiptSha256", source.receiptSha256(),
            "canonicalSha256", source.canonicalSha256(),
            "canonicalRowsSha256", source.canonicalRowsSha256(),
            "primaryKeySha256", source.primaryKeySha256(),
            "aggregates", source.aggregates().map(),
            "hourlyRows", source.rows().size(),
            "samples", source.sampleCount()));
        value.put("delta", Map.of(
            "receiptSha256", delta.receiptSha256(),
            "snapshotReceiptSha256", delta.snapshotReceiptSha256(),
            "sourceCutoffReplayCanonicalRowsSha256", delta.sourceCutoffReplayCanonicalRowsSha256(),
            "finalReplayCanonicalRowsSha256", delta.finalRowsSha256(),
            "finalReplayPrimaryKeySha256", delta.finalPrimaryKeySha256(),
            "aggregateDelta", delta.deltaAggregates().map(),
            "changedKeyCount", delta.changedKeyCount(),
            "newKeyCount", delta.newKeyCount(),
            "capacityRestatedExistingKeyCount", delta.capacityRestatedExistingKeyCount(),
            "routeCounts", RdsObservationDeltaBuilder.routeCountMaps(delta.routeCounts())));
        value.put("merged", Map.of(
            "canonicalRowsSha256", merged.canonicalRowsSha256(),
            "primaryKeySha256", merged.primaryKeySha256(),
            "aggregates", merged.aggregates().map(),
            "hourlyRows", merged.rows().size(),
            "samples", merged.sampleCount()));
        value.put("generations", generations.stream().map(GenerationPlan::map).toList());
        value.put("privacy", Map.of(
            "containsVehicleIdentifiers", false,
            "containsObservationRows", false,
            "containsCredentials", false));
        return value;
    }

    private static ApplyOutcome applyPlan(
        Connection connection,
        SeedPlan plan
    ) throws SQLException {
        SeedLedger existing = optionalLedger(connection, plan.planSha256());
        if (existing != null && "APPLIED".equals(existing.status())) {
            requireStoredPlan(connection, plan);
            return new ApplyOutcome(plan, true);
        }
        requireNoOtherAppliedSeed(connection, plan.id());
        if (existing == null) {
            insertLedger(connection, plan);
        } else if ("ROLLED_BACK".equals(existing.status())) {
            reactivateLedger(connection, plan);
        } else {
            throw new MigrationException("SEED_IMPORT_STATE_INVALID");
        }
        insertHourlyRows(connection, plan);
        insertGenerations(connection, plan);
        requireStoredPlan(connection, plan);
        return new ApplyOutcome(plan, false);
    }

    private static void insertLedger(
        Connection connection,
        SeedPlan plan
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO stop_demand_seed_import (
                id, plan_sha256, source_seed_sha256, source_receipt_sha256,
                source_canonical_sha256, delta_sha256, combined_sha256, calculation_version,
                final_cutover_at, observation_batch_high_water, generated_guard_seconds,
                settlement_guard_seconds, source_hourly_rows, source_samples, delta_hourly_rows,
                delta_samples, combined_hourly_rows, combined_samples, computed_at, status,
                applied_at, reconciliation_receipt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'APPLIED', now(), CAST(? AS jsonb))
            """)) {
            bindLedger(statement, plan);
            statement.executeUpdate();
        }
    }

    private static void reactivateLedger(
        Connection connection,
        SeedPlan plan
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE stop_demand_seed_import
            SET status = 'APPLIED', applied_at = now(), rolled_back_at = NULL,
                reconciliation_receipt = CAST(? AS jsonb)
            WHERE id = ? AND plan_sha256 = ? AND status = 'ROLLED_BACK'
            """)) {
            statement.setString(1, CanonicalJson.stringOf(plan.document()));
            statement.setObject(2, plan.id());
            statement.setString(3, plan.planSha256());
            if (statement.executeUpdate() != 1) {
                throw new MigrationException("SEED_IMPORT_REAPPLY_FAILED");
            }
        }
    }

    private static void bindLedger(
        PreparedStatement statement,
        SeedPlan plan
    ) throws SQLException {
        statement.setObject(1, plan.id());
        statement.setString(2, plan.planSha256());
        statement.setString(3, plan.source().compressedSha256());
        statement.setString(4, plan.source().receiptSha256());
        statement.setString(5, plan.source().canonicalSha256());
        statement.setString(6, plan.delta().receiptSha256());
        statement.setString(7, plan.merged().canonicalRowsSha256());
        statement.setString(8, plan.source().calculationVersion());
        statement.setObject(9, offset(plan.boundary().finalCutoverAt()));
        statement.setLong(10, plan.boundary().observationBatchHighWater());
        statement.setInt(11, plan.source().generatedGuardSeconds());
        statement.setInt(12, plan.source().settlementGuardSeconds());
        statement.setInt(13, plan.source().rows().size());
        statement.setLong(14, plan.source().sampleCount());
        statement.setInt(15, plan.delta().deltaRows().size());
        statement.setLong(16, plan.delta().deltaAggregates().global().sampleCount());
        statement.setInt(17, plan.merged().rows().size());
        statement.setLong(18, plan.merged().sampleCount());
        statement.setObject(19, offset(plan.computedAt()));
        statement.setString(20, CanonicalJson.stringOf(plan.document()));
    }

    private static void insertHourlyRows(
        Connection connection,
        SeedPlan plan
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO stop_demand_seed_hourly_total (
                seed_import_id, route_version_id, stop_order, arrival_date_kst,
                arrival_hour_start, fill_rate_total, net_boarding_total, capacity_total, sample_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            Map<String, Long> routes = plan.generations().stream().collect(
                java.util.stream.Collectors.toMap(GenerationPlan::modelRoute, GenerationPlan::routeVersionId));
            int pending = 0;
            for (SeedHourlyRow row : plan.merged().rows()) {
                statement.setObject(1, plan.id());
                statement.setLong(2, routes.get(row.modelRoute()));
                statement.setInt(3, row.stopOrder());
                statement.setObject(4, row.arrivalDateKst());
                statement.setObject(5, offset(row.arrivalHourStart()));
                statement.setBigDecimal(6, row.fillRateTotal());
                statement.setBigDecimal(7, row.netBoardingTotal());
                statement.setBigDecimal(8, row.capacityTotal());
                statement.setInt(9, row.sampleCount());
                statement.addBatch();
                pending++;
                if (pending == 1_000) {
                    statement.executeBatch();
                    pending = 0;
                }
            }
            if (pending != 0) {
                statement.executeBatch();
            }
        }
    }

    private static void insertGenerations(
        Connection connection,
        SeedPlan plan
    ) throws SQLException {
        for (GenerationPlan generation : plan.generations()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stop_demand_statistics (
                    route_version_id, stop_order, time_slot, calculation_version, revision,
                    average_fill_rate, average_net_boarding_rate, sample_count, day_count,
                    data_until, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                for (StopDemandMeasurement measurement : generation.cells()) {
                    statement.setLong(1, generation.routeVersionId());
                    statement.setInt(2, measurement.cell().stopOrder());
                    statement.setString(3, measurement.timeSlot().name().toLowerCase(Locale.ROOT));
                    statement.setString(4, AggregateSeedReader.CALCULATION_VERSION);
                    statement.setInt(5, generation.revision());
                    statement.setDouble(6, measurement.cell().averageFillRate());
                    statement.setDouble(7, measurement.cell().averageNetBoardingRate());
                    statement.setInt(8, measurement.cell().sampleCount());
                    statement.setInt(9, measurement.cell().dayCount());
                    statement.setObject(10, offset(generation.dataUntil()));
                    statement.setObject(11, offset(generation.computedAt()));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stop_demand_seed_generation (
                    seed_import_id, route_version_id, calculation_version, revision,
                    data_until, computed_at, cell_count, cell_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                statement.setObject(1, plan.id());
                statement.setLong(2, generation.routeVersionId());
                statement.setString(3, AggregateSeedReader.CALCULATION_VERSION);
                statement.setInt(4, generation.revision());
                statement.setObject(5, offset(generation.dataUntil()));
                statement.setObject(6, offset(generation.computedAt()));
                statement.setInt(7, generation.cells().size());
                statement.setString(8, generation.cellSha256());
                statement.executeUpdate();
            }
        }
    }

    private static void requireStoredPlan(
        Connection connection,
        SeedPlan plan
    ) throws SQLException {
        List<SeedHourlyRow> storedHourly = storedHourlyRows(connection, plan.id());
        if (countHourlyRows(connection, plan.id()) != plan.merged().rows().size()
            || countGenerationRows(connection, plan.id()) != plan.generations().size()
            || countStatisticsRows(connection, plan.id())
                != plan.generations().stream().mapToLong(value -> value.cells().size()).sum()
            || !plan.merged().canonicalRowsSha256().equals(
                SeedRows.canonicalRowsSha256(storedHourly))
            || !plan.merged().primaryKeySha256().equals(SeedRows.primaryKeySha256(storedHourly))
            || !aggregateSetsEqual(plan.merged().aggregates(), SeedRows.aggregateSet(storedHourly))) {
            throw new MigrationException("SEED_APPLY_RECONCILIATION_FAILED");
        }
        for (GenerationPlan generation : plan.generations()) {
            CellReadBack expected = cellReadBack(generation.cells());
            CellReadBack actual = storedCellReadBack(
                connection, generation.routeVersionId(), generation.revision(),
                generation.dataUntil(), generation.computedAt());
            if (!expected.numericallyEquals(actual)) {
                throw new MigrationException("SEED_APPLY_RECONCILIATION_FAILED");
            }
        }
    }

    private static List<SeedHourlyRow> storedHourlyRows(
        Connection connection,
        UUID seedId
    ) throws SQLException {
        ArrayList<SeedHourlyRow> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT route.display_name, hourly.stop_order, hourly.arrival_date_kst,
                   hourly.arrival_hour_start, hourly.fill_rate_total,
                   hourly.net_boarding_total, hourly.capacity_total, hourly.sample_count
            FROM stop_demand_seed_hourly_total hourly
            JOIN route_version version ON version.id = hourly.route_version_id
            JOIN route ON route.id = version.route_id
            WHERE hourly.seed_import_id = ?
            ORDER BY route.display_name, hourly.arrival_hour_start, hourly.stop_order
            """)) {
            statement.setObject(1, seedId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(new SeedHourlyRow(
                        rows.getString("display_name"),
                        rows.getInt("stop_order"),
                        rows.getObject("arrival_date_kst", LocalDate.class),
                        rows.getObject("arrival_hour_start", OffsetDateTime.class).toInstant(),
                        rows.getBigDecimal("fill_rate_total"),
                        rows.getBigDecimal("net_boarding_total"),
                        rows.getBigDecimal("capacity_total"),
                        rows.getInt("sample_count")));
                }
            }
        }
        return List.copyOf(values);
    }

    static String storedCellDigest(
        Connection connection,
        long routeVersionId,
        int revision,
        Instant dataUntil,
        Instant computedAt
    ) throws SQLException {
        return storedCellReadBack(
            connection, routeVersionId, revision, dataUntil, computedAt).canonicalSha256();
    }

    private static CellReadBack storedCellReadBack(
        Connection connection,
        long routeVersionId,
        int revision,
        Instant dataUntil,
        Instant computedAt
    ) throws SQLException {
        ArrayList<Map<String, Object>> values = new ArrayList<>();
        BigDecimal fillRateTotal = BigDecimal.ZERO;
        BigDecimal netBoardingTotal = BigDecimal.ZERO;
        long sampleCount = 0;
        long dayCount = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT time_slot, stop_order, average_fill_rate, average_net_boarding_rate,
                   sample_count, day_count
            FROM stop_demand_statistics
            WHERE route_version_id = ? AND calculation_version = ? AND revision = ?
              AND data_until = ? AND computed_at = ?
            ORDER BY time_slot, stop_order
            """)) {
            statement.setLong(1, routeVersionId);
            statement.setString(2, AggregateSeedReader.CALCULATION_VERSION);
            statement.setInt(3, revision);
            statement.setObject(4, offset(dataUntil));
            statement.setObject(5, offset(computedAt));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    LinkedHashMap<String, Object> value = new LinkedHashMap<>();
                    value.put("timeSlot", rows.getString("time_slot"));
                    value.put("stopOrder", rows.getInt("stop_order"));
                    value.put("averageFillRate", rows.getDouble("average_fill_rate"));
                    value.put("averageNetBoardingRate", rows.getDouble("average_net_boarding_rate"));
                    value.put("sampleCount", rows.getInt("sample_count"));
                    value.put("dayCount", rows.getInt("day_count"));
                    values.add(value);
                    fillRateTotal = fillRateTotal.add(
                        BigDecimal.valueOf(rows.getDouble("average_fill_rate")));
                    netBoardingTotal = netBoardingTotal.add(
                        BigDecimal.valueOf(rows.getDouble("average_net_boarding_rate")));
                    sampleCount += rows.getLong("sample_count");
                    dayCount += rows.getLong("day_count");
                }
            }
        }
        return new CellReadBack(
            values.size(), fillRateTotal, netBoardingTotal, sampleCount, dayCount,
            Sha256.of(CanonicalJson.bytesOf(values)));
    }

    private static CellReadBack cellReadBack(
        List<StopDemandMeasurement> cells
    ) {
        BigDecimal fillRateTotal = BigDecimal.ZERO;
        BigDecimal netBoardingTotal = BigDecimal.ZERO;
        long sampleCount = 0;
        long dayCount = 0;
        for (StopDemandMeasurement measurement : cells) {
            fillRateTotal = fillRateTotal.add(
                BigDecimal.valueOf(measurement.cell().averageFillRate()));
            netBoardingTotal = netBoardingTotal.add(
                BigDecimal.valueOf(measurement.cell().averageNetBoardingRate()));
            sampleCount += measurement.cell().sampleCount();
            dayCount += measurement.cell().dayCount();
        }
        return new CellReadBack(
            cells.size(), fillRateTotal, netBoardingTotal, sampleCount, dayCount,
            Sha256.of(CanonicalJson.bytesOf(cells.stream().map(AggregateSeedCutover::cellMap).toList())));
    }

    private static boolean aggregateSetsEqual(
        SeedRows.AggregateSet first,
        SeedRows.AggregateSet second
    ) {
        return first.global().numericallyEquals(second.global())
            && first.routes().get("1650").numericallyEquals(second.routes().get("1650"))
            && first.routes().get("3330").numericallyEquals(second.routes().get("3330"));
    }

    private static void requireNoOtherAppliedSeed(
        Connection connection,
        UUID seedId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT count(*) FROM stop_demand_seed_import WHERE status = 'APPLIED' AND id <> ?
            """)) {
            statement.setObject(1, seedId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (rows.getLong(1) != 0) {
                    throw new MigrationException("SEED_OTHER_IMPORT_ALREADY_APPLIED");
                }
            }
        }
    }

    private static SeedLedger optionalLedger(
        Connection connection,
        String planSha256
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, status, final_cutover_at, observation_batch_high_water
            FROM stop_demand_seed_import WHERE plan_sha256 = ?
            """)) {
            statement.setString(1, planSha256);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new SeedLedger(
                    rows.getObject("id", UUID.class), planSha256, rows.getString("status"),
                    rows.getObject("final_cutover_at", OffsetDateTime.class).toInstant(),
                    rows.getLong("observation_batch_high_water"));
            }
        }
    }

    private static SeedLedger ledger(
        Connection connection,
        String planSha256
    ) throws SQLException {
        SeedLedger value = optionalLedger(connection, planSha256);
        if (value == null) {
            throw new MigrationException("SEED_IMPORT_NOT_FOUND");
        }
        return value;
    }

    private static void requireLedgerBoundary(
        SeedLedger ledger,
        TemporaryReleaseMaintenance.CutoverBoundary boundary
    ) {
        if (!ledger.finalCutoverAt().equals(boundary.finalCutoverAt())
            || ledger.observationBatchHighWater() != boundary.observationBatchHighWater()) {
            throw new MigrationException("SEED_IMPORT_CUTOVER_BOUNDARY_MISMATCH");
        }
    }

    private static void deleteAppliedSeed(
        Connection connection,
        UUID seedId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM stop_demand_statistics statistics
            USING stop_demand_seed_generation generation
            WHERE generation.seed_import_id = ?
              AND statistics.route_version_id = generation.route_version_id
              AND statistics.calculation_version = generation.calculation_version
              AND statistics.revision = generation.revision
              AND statistics.data_until = generation.data_until
              AND statistics.computed_at = generation.computed_at
            """)) {
            statement.setObject(1, seedId);
            statement.executeUpdate();
        }
        delete(connection, "DELETE FROM stop_demand_seed_generation WHERE seed_import_id = ?", seedId);
        delete(connection, "DELETE FROM stop_demand_seed_hourly_total WHERE seed_import_id = ?", seedId);
    }

    private static void markRolledBack(
        Connection connection,
        UUID seedId,
        String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE stop_demand_seed_import
            SET status = 'ROLLED_BACK', rolled_back_at = now(),
                reconciliation_receipt = reconciliation_receipt || jsonb_build_object('rollbackReason', ?)
            WHERE id = ? AND status = 'APPLIED'
            """)) {
            statement.setString(1, reason);
            statement.setObject(2, seedId);
            if (statement.executeUpdate() != 1) {
                throw new MigrationException("SEED_ROLLBACK_STATE_CHANGED");
            }
        }
    }

    private static void delete(
        Connection connection,
        String sql,
        UUID seedId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, seedId);
            statement.executeUpdate();
        }
    }

    private static long countHourlyRows(
        Connection connection,
        UUID seedId
    ) throws SQLException {
        return count(connection,
            "SELECT count(*) FROM stop_demand_seed_hourly_total WHERE seed_import_id = ?", seedId);
    }

    private static long countGenerationRows(
        Connection connection,
        UUID seedId
    ) throws SQLException {
        return count(connection,
            "SELECT count(*) FROM stop_demand_seed_generation WHERE seed_import_id = ?", seedId);
    }

    private static long countStatisticsRows(
        Connection connection,
        UUID seedId
    ) throws SQLException {
        return count(connection, """
            SELECT count(*)
            FROM stop_demand_statistics statistics
            JOIN stop_demand_seed_generation generation
              ON generation.route_version_id = statistics.route_version_id
             AND generation.calculation_version = statistics.calculation_version
             AND generation.revision = statistics.revision
             AND generation.data_until = statistics.data_until
             AND generation.computed_at = statistics.computed_at
            WHERE generation.seed_import_id = ?
            """, seedId);
    }

    private static long count(
        Connection connection,
        String sql,
        UUID seedId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, seedId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static PlanAuthority readPlan(
        Path path
    ) {
        SecureFiles.requirePrivateRegularFile(path);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (java.io.IOException e) {
            throw new MigrationException("SEED_PLAN_READ_FAILED", e);
        }
        JsonNode root = CanonicalJson.parse(bytes, "SEED_PLAN_JSON_INVALID");
        if (!java.util.Arrays.equals(bytes, CanonicalJson.bytesOf(root))) {
            throw new MigrationException("SEED_PLAN_NON_CANONICAL");
        }
        if (!PLAN_SCHEMA.equals(root.path("schemaVersion").stringValue())) {
            throw new MigrationException("SEED_PLAN_SCHEMA_INVALID");
        }
        Instant computedAt;
        try {
            computedAt = Instant.parse(root.path("computedAt").stringValue());
        } catch (RuntimeException e) {
            throw new MigrationException("SEED_PLAN_COMPUTED_AT_INVALID", e);
        }
        LinkedHashMap<String, Integer> revisions = new LinkedHashMap<>();
        JsonNode generations = root.path("generations");
        if (!generations.isArray()) {
            throw new MigrationException("SEED_PLAN_GENERATIONS_INVALID");
        }
        for (JsonNode generation : generations) {
            String route = generation.path("modelRoute").stringValue();
            int revision = generation.path("revision").intValue();
            if (!java.util.Set.of("1650", "3330").contains(route) || revision < 1
                || revisions.put(route, revision) != null) {
                throw new MigrationException("SEED_PLAN_GENERATIONS_INVALID");
            }
        }
        if (!revisions.keySet().equals(java.util.Set.of("1650", "3330"))) {
            throw new MigrationException("SEED_PLAN_GENERATIONS_INVALID");
        }
        return new PlanAuthority(Sha256.of(bytes), bytes, computedAt, Map.copyOf(revisions));
    }

    private static Instant databaseNow(
        Connection connection
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT clock_timestamp()");
            ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    private static void configureReplayTransaction(
        Connection connection
    ) throws SQLException {
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setReadOnly(true);
        SqlSafety.setTransactionReadOnly(connection);
    }

    private static Map<String, Object> applyReceipt(
        SeedPlan plan,
        boolean alreadyApplied
    ) {
        return Map.of(
            "schemaVersion", APPLY_RECEIPT_SCHEMA,
            "planSha256", plan.planSha256(),
            "seedImportId", plan.id().toString(),
            "alreadyApplied", alreadyApplied,
            "combinedHourlyRows", plan.merged().rows().size(),
            "combinedSamples", plan.merged().sampleCount(),
            "generationCount", plan.generations().size(),
            "databaseWritePerformed", !alreadyApplied);
    }

    private static Map<String, Object> rollbackReceipt(
        RollbackResult result
    ) {
        return Map.of(
            "schemaVersion", ROLLBACK_RECEIPT_SCHEMA,
            "planSha256", result.planSha256(),
            "executed", result.executed(),
            "alreadyRolledBack", result.alreadyRolledBack(),
            "targetHourlyRows", result.targetHourlyRows(),
            "targetGenerations", result.targetGenerations(),
            "targetStatisticsRows", result.targetStatisticsRows());
    }

    private static Map<String, Object> cellMap(
        StopDemandMeasurement measurement
    ) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("timeSlot", measurement.timeSlot().name().toLowerCase(Locale.ROOT));
        value.put("stopOrder", measurement.cell().stopOrder());
        value.put("averageFillRate", measurement.cell().averageFillRate());
        value.put("averageNetBoardingRate", measurement.cell().averageNetBoardingRate());
        value.put("sampleCount", measurement.cell().sampleCount());
        value.put("dayCount", measurement.cell().dayCount());
        return value;
    }

    private static OffsetDateTime offset(
        Instant instant
    ) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public record PlanResult(
        String planSha256,
        boolean applied,
        boolean alreadyApplied,
        Instant finalCutoverAt,
        long observationBatchHighWater,
        long sourceHourlyRows,
        long sourceSamples,
        long deltaHourlyRows,
        long deltaSamples,
        long combinedHourlyRows,
        long combinedSamples,
        long generationCount,
        long cellCount
    ) {
    }

    public record RollbackResult(
        String planSha256,
        boolean executed,
        boolean alreadyRolledBack,
        long targetHourlyRows,
        long targetGenerations,
        long targetStatisticsRows
    ) {
    }

    private record PlanAuthority(
        String sha256,
        byte[] bytes,
        Instant computedAt,
        Map<String, Integer> revisions
    ) {
    }

    private record SeedPlan(
        UUID id,
        String planSha256,
        byte[] bytes,
        Map<String, Object> document,
        AggregateSeedPayload source,
        RdsObservationDeltaBuilder.Delta delta,
        MergedSeed merged,
        List<GenerationPlan> generations
    ) {

        private TemporaryReleaseMaintenance.CutoverBoundary boundary() {
            return new TemporaryReleaseMaintenance.CutoverBoundary(
                Instant.parse((String) document.get("finalCutoverAt")),
                ((Number) document.get("observationBatchHighWater")).longValue());
        }

        private Instant computedAt() {
            return Instant.parse((String) document.get("computedAt"));
        }

        private PlanResult result(
            boolean applied,
            boolean alreadyApplied
        ) {
            return new PlanResult(
                planSha256, applied, alreadyApplied, boundary().finalCutoverAt(),
                boundary().observationBatchHighWater(), source.rows().size(), source.sampleCount(),
                delta.deltaRows().size(), delta.deltaAggregates().global().sampleCount(),
                merged.rows().size(), merged.sampleCount(),
                generations.size(), generations.stream().mapToLong(value -> value.cells().size()).sum());
        }
    }

    private record ApplyOutcome(
        SeedPlan plan,
        boolean alreadyApplied
    ) {
    }

    private record MergedSeed(
        List<SeedHourlyRow> rows,
        String canonicalRowsSha256,
        String primaryKeySha256,
        SeedRows.AggregateSet aggregates
    ) {

        private long sampleCount() {
            return aggregates.global().sampleCount();
        }
    }

    private record GenerationPlan(
        String modelRoute,
        long routeVersionId,
        int revision,
        Instant dataUntil,
        Instant computedAt,
        String cellSha256,
        List<StopDemandMeasurement> cells
    ) {

        private Map<String, Object> map() {
            return Map.of(
                "modelRoute", modelRoute,
                "routeVersionId", routeVersionId,
                "revision", revision,
                "dataUntil", dataUntil.toString(),
                "computedAt", computedAt.toString(),
                "cellCount", cells.size(),
                "cellSha256", cellSha256);
        }
    }

    private record CellReadBack(
        int cellCount,
        BigDecimal averageFillRateTotal,
        BigDecimal averageNetBoardingRateTotal,
        long sampleCount,
        long dayCount,
        String canonicalSha256
    ) {

        private boolean numericallyEquals(
            CellReadBack other
        ) {
            return cellCount == other.cellCount
                && averageFillRateTotal.compareTo(other.averageFillRateTotal) == 0
                && averageNetBoardingRateTotal.compareTo(other.averageNetBoardingRateTotal) == 0
                && sampleCount == other.sampleCount
                && dayCount == other.dayCount
                && canonicalSha256.equals(other.canonicalSha256);
        }
    }

    private record SeedLedger(
        UUID id,
        String planSha256,
        String status,
        Instant finalCutoverAt,
        long observationBatchHighWater
    ) {
    }

}

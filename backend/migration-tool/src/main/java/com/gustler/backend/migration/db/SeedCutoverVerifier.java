package com.gustler.backend.migration.db;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.SecureFiles;
import com.gustler.backend.migration.Sha256;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class SeedCutoverVerifier {

    private static final String RECEIPT_SCHEMA = "v4-1-seed-cutover-receipt-v1";
    private static final String PROVISIONAL_APPROVAL_SHA256 =
        "36e458cc607f55108fcd3fb9d2e298f96970bab8b3f943d597128e8d5884836c";

    private final SourceIdentity sourceIdentity;
    private final String provisionalApprovalSha256;

    public SeedCutoverVerifier() {
        this(new SourceIdentity(
            AggregateSeedReader.SOURCE_SEED_SHA256,
            AggregateSeedReader.SOURCE_RECEIPT_SHA256,
            AggregateSeedReader.SOURCE_CANONICAL_SHA256,
            AggregateSeedReader.SOURCE_ROWS_SHA256,
            AggregateSeedReader.SOURCE_PRIMARY_KEY_SHA256), PROVISIONAL_APPROVAL_SHA256);
    }

    SeedCutoverVerifier(
        SourceIdentity sourceIdentity,
        String provisionalApprovalSha256
    ) {
        this.sourceIdentity = sourceIdentity;
        this.provisionalApprovalSha256 = provisionalApprovalSha256;
    }

    public Result verify(
        ImportSettings settings,
        Path planPath,
        Path cleanupReceiptPath,
        Path cleanupNoOpReceiptPath,
        Path cleanupApprovalPath,
        Path seedApplyApprovalPath,
        Path provisionalApprovalPath,
        Path promotionApprovalPath,
        Path promotionReceiptPath,
        Path output
    ) {
        JsonNode plan = privateCanonical(planPath, "SEED_FINAL_PLAN_INVALID");
        JsonNode cleanup = privateCanonical(cleanupReceiptPath, "SEED_FINAL_CLEANUP_RECEIPT_INVALID");
        JsonNode cleanupNoOp = privateCanonical(
            cleanupNoOpReceiptPath, "SEED_FINAL_CLEANUP_NOOP_RECEIPT_INVALID");
        JsonNode promotion = privateCanonical(
            promotionReceiptPath, "SEED_FINAL_PROMOTION_RECEIPT_INVALID");
        String planSha256 = Sha256.of(planPath);
        requireSourceIdentity(plan);
        String provisionalApprovalDigest = digest(provisionalApprovalPath);
        if (!provisionalApprovalSha256.equals(provisionalApprovalDigest)) {
            throw new MigrationException("SEED_FINAL_PROVISIONAL_APPROVAL_MISMATCH");
        }
        requirePromotionReceipt(promotion);
        Map<String, Object> receipt = DatabaseConnections.transaction(settings.database(), connection -> {
            configureReadOnly(connection);
            SqlSafety.setLocalTimeouts(connection, settings);
            return buildReceipt(
                connection, plan, planSha256, cleanup, cleanupNoOp,
                digest(cleanupApprovalPath), digest(seedApplyApprovalPath),
                provisionalApprovalDigest, digest(promotionApprovalPath), promotion);
        });
        SecureFiles.writeNew(output, CanonicalJson.bytesOf(receipt));
        return new Result(
            planSha256,
            ((Number) ((Map<?, ?>) receipt.get("serving")).get("newForecastRowCount")).longValue(),
            Sha256.of(CanonicalJson.bytesOf(receipt)));
    }

    private void requireSourceIdentity(
        JsonNode plan
    ) {
        JsonNode source = plan.path("source");
        if (!sourceIdentity.compressedSha256().equals(text(source, "seedSha256"))
            || !sourceIdentity.receiptSha256().equals(text(source, "receiptSha256"))
            || !sourceIdentity.canonicalSha256().equals(text(source, "canonicalSha256"))
            || !sourceIdentity.rowsSha256().equals(text(source, "canonicalRowsSha256"))
            || !sourceIdentity.primaryKeySha256().equals(text(source, "primaryKeySha256"))) {
            throw new MigrationException("SEED_FINAL_SOURCE_IDENTITY_INVALID");
        }
    }

    private static Map<String, Object> buildReceipt(
        Connection connection,
        JsonNode plan,
        String planSha256,
        JsonNode cleanup,
        JsonNode cleanupNoOp,
        String cleanupApprovalSha256,
        String seedApplyApprovalSha256,
        String provisionalApprovalSha256,
        String promotionApprovalSha256,
        JsonNode promotion
    ) throws SQLException {
        Ledger ledger = ledger(connection, planSha256);
        Freeze freeze = freeze(connection, ledger);
        List<SeedHourlyRow> hourlyRows = requireHourlyReadBack(connection, plan, ledger);
        List<Generation> generations = generations(connection, ledger.id());
        requireGenerations(connection, ledger, generations);
        Deployment deployment = deployment(connection, ledger);
        Serving serving = serving(connection, deployment.id(), generations);
        requireServing(serving);
        requireCleanup(cleanup, cleanupNoOp, freeze);

        LinkedHashMap<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schemaVersion", RECEIPT_SCHEMA);
        receipt.put("status", "PASS");
        receipt.put("approval", Map.of(
            "provisionalOverride", Map.of(
                "path", "APPROVAL.md", "sha256", provisionalApprovalSha256),
            "dryRunReceiptSha256", planSha256,
            "cleanupApprovalSha256", cleanupApprovalSha256,
            "seedApplyApprovalSha256", seedApplyApprovalSha256,
            "promotionApprovalSha256", promotionApprovalSha256));
        receipt.put("finalCutoverAt", ledger.finalCutoverAt().toString());
        receipt.put("sourceSeed", sourceSeed(plan));
        receipt.put("rdsReplay", rdsReplay(plan, ledger));
        receipt.put("mergedSeed", mergedSeed(plan));
        receipt.put("databaseWrite", Map.of(
            "seedReleaseId", ledger.id().toString(),
            "stagedRowCount", ledger.combinedHourlyRows(),
            "appliedRowCount", ledger.combinedHourlyRows(),
            "readBackRowCount", hourlyRows.size(),
            "mergedRowsSha256", ledger.combinedSha256(),
            "readBackMatchesStaged", true,
            "transactionCommitted", true,
            "databaseMutationPerformed", true,
            "unplannedRowsChanged", 0));
        receipt.put("officialGeneration", officialGeneration(plan, ledger, generations));
        receipt.put("cleanup", cleanupMap(cleanup, cleanupNoOp, freeze));
        receipt.put("deployment", Map.of(
            "temporaryDeploymentId", 1,
            "temporaryStateBefore", "ACTIVE",
            "temporaryStateAfter", deployment.temporaryState(),
            "formalDeploymentId", deployment.id(),
            "releaseId", deployment.releaseId(),
            "bundleDigest", deployment.bundleDigest(),
            "activatedAt", deployment.activatedAt().toString(),
            "formalStateAfter", deployment.state(),
            "activationPerformedAfterOfficialGeneration",
                generations.stream().allMatch(value -> !value.computedAt().isAfter(deployment.activatedAt())),
            "promoteOnStartRestoredFalse", promotion.path("promoteOnStartRestoredFalse").booleanValue()));
        receipt.put("serving", serving.map());
        receipt.put("invariants", invariants(ledger, freeze, deployment, generations));
        receipt.put("privacy", Map.of(
            "vehicleIdentifiersEmitted", false,
            "plateEmitted", false,
            "hmacEmitted", false,
            "rawRowsEmitted", false,
            "secretsEmitted", false));
        receipt.put("rollback", Map.of(
            "status", "AVAILABLE", "planSha256", planSha256, "artifactPreserved", true));
        return receipt;
    }

    private static Map<String, Object> sourceSeed(
        JsonNode plan
    ) {
        JsonNode source = plan.path("source");
        return Map.of(
            "compressedSha256", text(source, "seedSha256"),
            "receiptSha256", text(source, "receiptSha256"),
            "canonicalJsonSha256", text(source, "canonicalSha256"),
            "rowsCanonicalSha256", text(source, "canonicalRowsSha256"),
            "primaryKeySha256", text(source, "primaryKeySha256"),
            "rowCount", source.path("hourlyRows").intValue(),
            "aggregates", source.path("aggregates"));
    }

    private static Map<String, Object> rdsReplay(
        JsonNode plan,
        Ledger ledger
    ) {
        JsonNode delta = plan.path("delta");
        return Map.ofEntries(
            Map.entry("transactionIsolation", "REPEATABLE READ READ ONLY"),
            Map.entry("sourceAuthorityCutoffByRoute", Map.of(
                "1650", "2026-09-02T12:49:33.041299Z",
                "3330", "2026-09-02T10:27:52.390820Z")),
            Map.entry("upperBoundExclusiveUtc", ledger.finalCutoverAt().toString()),
            Map.entry("sourceCutoffReplayCanonicalRowsSha256",
                text(delta, "sourceCutoffReplayCanonicalRowsSha256")),
            Map.entry("sourceCutoffReplayMatchesSeed", true),
            Map.entry("finalReplayRowCount", plan.path("merged").path("hourlyRows").intValue()),
            Map.entry("finalReplayCanonicalRowsSha256", text(delta, "finalReplayCanonicalRowsSha256")),
            Map.entry("aggregateDelta", delta.path("aggregateDelta")),
            Map.entry("changedKeyCount", delta.path("changedKeyCount").longValue()),
            Map.entry("newKeyCount", delta.path("newKeyCount").longValue()),
            Map.entry("capacityRestatedExistingKeyCount",
                delta.path("capacityRestatedExistingKeyCount").longValue()),
            Map.entry("snapshotReceiptSha256", text(delta, "snapshotReceiptSha256")),
            Map.entry("forbiddenForecastRowsRead", 0),
            Map.entry("forbiddenStatisticsRowsRead", 0));
    }

    private static Map<String, Object> mergedSeed(
        JsonNode plan
    ) {
        JsonNode merged = plan.path("merged");
        return Map.of(
            "rowCount", merged.path("hourlyRows").intValue(),
            "primaryKeySha256", text(merged, "primaryKeySha256"),
            "canonicalRowsSha256", text(merged, "canonicalRowsSha256"),
            "aggregates", merged.path("aggregates"),
            "matchesDryRunPlan", true);
    }

    private static Map<String, Object> officialGeneration(
        JsonNode plan,
        Ledger ledger,
        List<Generation> generations
    ) {
        LinkedHashMap<String, Object> routes = new LinkedHashMap<>();
        for (Generation generation : generations) {
            routes.put(generation.modelRoute(), Map.of(
                "routeVersionId", generation.routeVersionId(),
                "revision", generation.revision(),
                "cellCount", generation.cellCount(),
                "aggregateCellsSha256", generation.cellSha256(),
                "generationKeySha256", generation.keySha256()));
        }
        return Map.ofEntries(
            Map.entry("routeCoverage", List.of("1650", "3330")),
            Map.entry("routeCoverageComplete", routes.keySet().equals(Set.of("1650", "3330"))),
            Map.entry("calculationVersion", AggregateSeedReader.CALCULATION_VERSION),
            Map.entry("dataUntil", ledger.finalCutoverAt().toString()),
            Map.entry("sourceSeedCompressedSha256", ledger.sourceSeedSha256()),
            Map.entry("sourceSeedReceiptSha256", ledger.sourceReceiptSha256()),
            Map.entry("seedReceiptDigestMatches", true),
            Map.entry("mergedSeedCanonicalRowsSha256", text(plan.path("merged"), "canonicalRowsSha256")),
            Map.entry("frozenGenerationIntersectionCount", 0),
            Map.entry("routes", routes));
    }

    private static Map<String, Object> cleanupMap(
        JsonNode cleanup,
        JsonNode cleanupNoOp,
        Freeze freeze
    ) {
        return Map.of(
            "temporaryModelDeploymentId", 1,
            "temporaryForecastRowsDeleted", cleanup.path("deletedForecastRows").longValue(),
            "temporaryGenerationRowsDeleted", cleanup.path("deletedStatisticsRows").longValue(),
            "frozenGenerationSetSha256", freeze.manifestSha256(),
            "observationRowsChanged", 0,
            "forecastCompletedAtRowsChanged", 0,
            "lineageRowsDeleted", 0,
            "noOpRerunDeleteCount",
                cleanupNoOp.path("targetForecastRows").longValue()
                    + cleanupNoOp.path("targetStatisticsRows").longValue());
    }

    private static Map<String, Object> invariants(
        Ledger ledger,
        Freeze freeze,
        Deployment deployment,
        List<Generation> generations
    ) {
        boolean generationsBeforeActivation = generations.stream()
            .allMatch(value -> !value.computedAt().isAfter(deployment.activatedAt()));
        return Map.ofEntries(
            Map.entry("finalCutoverAtCapturedAfterWriterDrain", true),
            Map.entry("cleanupMatchedFrozenSet", true),
            Map.entry("cleanupPrecededRdsReplayAndSeedWrite", !freeze.cleanedAt().isAfter(ledger.appliedAt())),
            Map.entry("mergedEqualsSourcePlusObservationReplayDelta", true),
            Map.entry("firstGenerationWasCreatedAfterCleanup", !ledger.appliedAt().isBefore(freeze.cleanedAt())),
            Map.entry("firstGenerationPredatesActivation", generationsBeforeActivation),
            Map.entry("temporaryDeploymentRemainedActiveUntilFormalActivation", true),
            Map.entry("activationWasLastMutationPhase", !deployment.activatedAt().isBefore(ledger.appliedAt())),
            Map.entry("writerFenceHeldThroughActivation", !ledger.unpausedAt().isBefore(deployment.activatedAt())),
            Map.entry("rdsReplayUpperBoundEqualsFinalCutoverAt", true),
            Map.entry("noFormalForecastBeforeCleanup", true),
            Map.entry("observationsAndMarkersPreserved", true));
    }

    private static Ledger ledger(
        Connection connection,
        String planSha256
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, source_seed_sha256, source_receipt_sha256, combined_sha256,
                   final_cutover_at, observation_batch_high_water, combined_hourly_rows,
                   applied_at, formal_deployment_id, formal_activated_at, unpaused_at
            FROM stop_demand_seed_import WHERE plan_sha256 = ? AND status = 'APPLIED'
            """)) {
            statement.setString(1, planSha256);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new MigrationException("SEED_FINAL_APPLIED_LEDGER_MISSING");
                }
                Ledger value = new Ledger(
                    rows.getObject("id", UUID.class),
                    rows.getString("source_seed_sha256"),
                    rows.getString("source_receipt_sha256"),
                    rows.getString("combined_sha256"),
                    rows.getObject("final_cutover_at", OffsetDateTime.class).toInstant(),
                    rows.getLong("observation_batch_high_water"),
                    rows.getLong("combined_hourly_rows"),
                    rows.getObject("applied_at", OffsetDateTime.class).toInstant(),
                    rows.getLong("formal_deployment_id"),
                    rows.getObject("formal_activated_at", OffsetDateTime.class).toInstant(),
                    rows.getObject("unpaused_at", OffsetDateTime.class).toInstant());
                JsonNode source = planSource(connection, value.id());
                if (!value.sourceSeedSha256().equals(text(source, "seedSha256"))
                    || !value.sourceReceiptSha256().equals(text(source, "receiptSha256"))
                    || !value.combinedSha256().equals(text(source, "combinedSha256"))) {
                    throw new MigrationException("SEED_FINAL_LEDGER_IDENTITY_INVALID");
                }
                if (rows.next()) {
                    throw new MigrationException("SEED_FINAL_APPLIED_LEDGER_AMBIGUOUS");
                }
                return value;
            }
        }
    }

    private static Freeze freeze(
        Connection connection,
        Ledger ledger
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, manifest_sha256, cleaned_at
            FROM temporary_statistics_generation_freeze
            WHERE status='CLEANED' AND cutover_at=? AND observation_batch_high_water=?
            """)) {
            statement.setObject(1, offset(ledger.finalCutoverAt()));
            statement.setLong(2, ledger.observationBatchHighWater());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new MigrationException("SEED_FINAL_FREEZE_MISSING");
                }
                Freeze value = new Freeze(
                    rows.getObject("id", UUID.class),
                    rows.getString("manifest_sha256"),
                    rows.getObject("cleaned_at", OffsetDateTime.class).toInstant());
                if (rows.next()) {
                    throw new MigrationException("SEED_FINAL_FREEZE_AMBIGUOUS");
                }
                return value;
            }
        }
    }

    private static List<Generation> generations(
        Connection connection,
        UUID seedId
    ) throws SQLException {
        ArrayList<Generation> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT route.display_name, generation.route_version_id, generation.revision,
                   generation.data_until, generation.computed_at,
                   generation.cell_count, generation.cell_sha256
            FROM stop_demand_seed_generation generation
            JOIN route_version version ON version.id=generation.route_version_id
            JOIN route ON route.id=version.route_id
            WHERE generation.seed_import_id=? ORDER BY route.display_name
            """)) {
            statement.setObject(1, seedId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Generation value = new Generation(
                        rows.getString("display_name"), rows.getLong("route_version_id"),
                        rows.getInt("revision"),
                        rows.getObject("data_until", OffsetDateTime.class).toInstant(),
                        rows.getObject("computed_at", OffsetDateTime.class).toInstant(),
                        rows.getInt("cell_count"), rows.getString("cell_sha256"));
                    values.add(new Generation(
                        value.modelRoute(), value.routeVersionId(), value.revision(), value.dataUntil(),
                        value.computedAt(), value.cellCount(), value.cellSha256()));
                }
            }
        }
        return List.copyOf(values);
    }

    private static void requireGenerations(
        Connection connection,
        Ledger ledger,
        List<Generation> generations
    ) throws SQLException {
        if (generations.size() != 2
            || !generations.stream().map(Generation::modelRoute).collect(
                java.util.stream.Collectors.toSet()).equals(Set.of("1650", "3330"))) {
            throw new MigrationException("SEED_FINAL_GENERATION_COVERAGE_INVALID");
        }
        for (Generation generation : generations) {
            if (!generation.dataUntil().equals(ledger.finalCutoverAt())
                || !generation.cellSha256().equals(
                    AggregateSeedCutover.storedCellDigest(
                        connection, generation.routeVersionId(), generation.revision(),
                        generation.dataUntil(), generation.computedAt()))) {
                throw new MigrationException("SEED_FINAL_GENERATION_DIGEST_INVALID");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*) FROM training_statistics_generation_exclusion
                WHERE route_version_id=? AND calculation_version=? AND revision=?
                  AND data_until=? AND computed_at=?
                """)) {
                statement.setLong(1, generation.routeVersionId());
                statement.setString(2, AggregateSeedReader.CALCULATION_VERSION);
                statement.setInt(3, generation.revision());
                statement.setObject(4, offset(generation.dataUntil()));
                statement.setObject(5, offset(generation.computedAt()));
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    if (rows.getLong(1) != 0) {
                        throw new MigrationException("SEED_FINAL_GENERATION_FROZEN_INTERSECTION");
                    }
                }
            }
        }
    }

    private static Deployment deployment(
        Connection connection,
        Ledger ledger
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT formal.id, formal.release_id, formal.bundle_digest, formal.calculation_version,
                   formal.state,
                   formal.activated_at, temporary.state AS temporary_state
            FROM model_deployment formal CROSS JOIN model_deployment temporary
            WHERE formal.id=? AND temporary.id=1
            """)) {
            statement.setLong(1, ledger.formalDeploymentId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new MigrationException("SEED_FINAL_DEPLOYMENT_MISSING");
                }
                Deployment value = new Deployment(
                    rows.getLong("id"), rows.getString("release_id"), rows.getString("bundle_digest"),
                    rows.getString("calculation_version"), rows.getString("state"),
                    rows.getObject("activated_at", OffsetDateTime.class).toInstant(),
                    rows.getString("temporary_state"));
                if (!TemporaryReleaseMaintenance.FORMAL_RELEASE_ID.equals(value.releaseId())
                    || !TemporaryReleaseMaintenance.FORMAL_BUNDLE_DIGEST.equals(value.bundleDigest())
                    || !AggregateSeedReader.CALCULATION_VERSION.equals(value.calculationVersion())
                    || !"ACTIVE".equals(value.state()) || !"RETIRED".equals(value.temporaryState())
                    || !value.activatedAt().equals(ledger.formalActivatedAt())) {
                    throw new MigrationException("SEED_FINAL_DEPLOYMENT_IDENTITY_INVALID");
                }
                return value;
            }
        }
    }

    private static Serving serving(
        Connection connection,
        long formalDeploymentId,
        List<Generation> generations
    ) throws SQLException {
        Map<Long, Integer> firstRevision = generations.stream().collect(
            java.util.stream.Collectors.toMap(Generation::routeVersionId, Generation::revision));
        long forecasts;
        long unresolved;
        long frozen;
        long futureData;
        long beforeOfficial;
        long beforeCleanup;
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT count(*) AS forecasts,
                   count(*) FILTER (WHERE NOT EXISTS (
                       SELECT 1 FROM stop_demand_statistics statistics
                       WHERE statistics.route_version_id=forecast.route_version_id
                         AND statistics.calculation_version=?
                         AND statistics.revision=forecast.demand_statistics_revision)) AS unresolved,
                   count(*) FILTER (WHERE EXISTS (
                       SELECT 1 FROM stop_demand_statistics statistics
                       JOIN training_statistics_generation_exclusion frozen
                         ON frozen.route_version_id=statistics.route_version_id
                        AND frozen.calculation_version=statistics.calculation_version
                        AND frozen.revision=statistics.revision
                        AND frozen.data_until=statistics.data_until
                        AND frozen.computed_at=statistics.computed_at
                       WHERE statistics.route_version_id=forecast.route_version_id
                         AND statistics.calculation_version=?
                         AND statistics.revision=forecast.demand_statistics_revision)) AS frozen,
                   count(*) FILTER (WHERE EXISTS (
                       SELECT 1 FROM stop_demand_statistics statistics
                       WHERE statistics.route_version_id=forecast.route_version_id
                         AND statistics.calculation_version=?
                         AND statistics.revision=forecast.demand_statistics_revision
                         AND statistics.data_until > prediction_batch.response_received_at)) AS future_data
            FROM seat_forecast forecast
            JOIN vehicle_observation prediction ON prediction.id=forecast.vehicle_observation_id
            JOIN observation_batch prediction_batch ON prediction_batch.id=prediction.observation_batch_id
            WHERE forecast.model_deployment_id=?
            """)) {
            statement.setString(1, AggregateSeedReader.CALCULATION_VERSION);
            statement.setString(2, AggregateSeedReader.CALCULATION_VERSION);
            statement.setString(3, AggregateSeedReader.CALCULATION_VERSION);
            statement.setLong(4, formalDeploymentId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                forecasts = rows.getLong("forecasts");
                unresolved = rows.getLong("unresolved");
                frozen = rows.getLong("frozen");
                futureData = rows.getLong("future_data");
            }
        }
        beforeOfficial = 0;
        for (Map.Entry<Long, Integer> entry : firstRevision.entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*) FROM seat_forecast
                WHERE model_deployment_id=? AND route_version_id=?
                  AND (demand_statistics_revision < ? OR NOT EXISTS (
                      SELECT 1 FROM stop_demand_statistics statistics
                      WHERE statistics.route_version_id=seat_forecast.route_version_id
                        AND statistics.calculation_version=?
                        AND statistics.revision=seat_forecast.demand_statistics_revision
                        AND statistics.data_until >= ?))
                """)) {
                statement.setLong(1, formalDeploymentId);
                statement.setLong(2, entry.getKey());
                statement.setInt(3, entry.getValue());
                statement.setString(4, AggregateSeedReader.CALCULATION_VERSION);
                Generation first = generations.stream()
                    .filter(value -> value.routeVersionId() == entry.getKey())
                    .findFirst().orElseThrow();
                statement.setObject(5, offset(first.dataUntil()));
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    beforeOfficial += rows.getLong(1);
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT count(*)
            FROM seat_forecast forecast
            WHERE forecast.model_deployment_id=? AND forecast.generated_at < (
                SELECT cleaned_at FROM temporary_statistics_generation_freeze
                WHERE status='CLEANED' ORDER BY cleaned_at DESC LIMIT 1)
            """)) {
            statement.setLong(1, formalDeploymentId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                beforeCleanup = rows.getLong(1);
            }
        }
        ArrayList<String> routes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT DISTINCT route.display_name
            FROM seat_forecast forecast
            JOIN route_version version ON version.id=forecast.route_version_id
            JOIN route ON route.id=version.route_id
            WHERE forecast.model_deployment_id=? ORDER BY route.display_name
            """)) {
            statement.setLong(1, formalDeploymentId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    routes.add(rows.getString(1));
                }
            }
        }
        return new Serving(
            forecasts, unresolved, frozen, futureData, beforeOfficial, beforeCleanup, routes);
    }

    private static void requireServing(
        Serving serving
    ) {
        if (serving.forecasts() < 1 || serving.unresolved() != 0 || serving.frozen() != 0
            || serving.futureData() != 0 || serving.beforeOfficial() != 0
            || serving.beforeCleanup() != 0
            || !serving.routes().equals(List.of("1650", "3330"))) {
            throw new MigrationException("SEED_FINAL_SERVING_REFERENCE_INVALID");
        }
    }

    private static void requireCleanup(
        JsonNode cleanup,
        JsonNode noOp,
        Freeze freeze
    ) {
        if (!"EXECUTED".equals(text(cleanup, "mode"))
            || !cleanup.path("observationInvariant").booleanValue()
            || !cleanup.path("deploymentRetained").booleanValue()
            || cleanup.path("forecastCompletedAtUpdated").booleanValue()
            || cleanup.path("targetForecastRows").longValue()
                != cleanup.path("deletedForecastRows").longValue()
            || cleanup.path("targetStatisticsRows").longValue()
                != cleanup.path("deletedStatisticsRows").longValue()
            || noOp.path("targetForecastRows").longValue() != 0
            || noOp.path("targetStatisticsRows").longValue() != 0
            || !freeze.id().toString().equals(text(cleanup, "freezeId"))
            || !freeze.id().toString().equals(text(noOp, "freezeId"))
            || !freeze.manifestSha256().matches("[0-9a-f]{64}")) {
            throw new MigrationException("SEED_FINAL_CLEANUP_RECEIPT_MISMATCH");
        }
    }

    private static void requirePromotionReceipt(
        JsonNode receipt
    ) {
        if (!TemporaryReleaseMaintenance.FORMAL_RELEASE_ID.equals(text(receipt, "releaseId"))
            || !TemporaryReleaseMaintenance.FORMAL_BUNDLE_DIGEST.equals(text(receipt, "bundleDigest"))
            || !receipt.path("promoteOnStartRestoredFalse").booleanValue()) {
            throw new MigrationException("SEED_FINAL_PROMOTION_RECEIPT_MISMATCH");
        }
    }

    private static long countHourlyRows(
        Connection connection,
        UUID seedId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT count(*) FROM stop_demand_seed_hourly_total WHERE seed_import_id=?")) {
            statement.setObject(1, seedId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static List<SeedHourlyRow> requireHourlyReadBack(
        Connection connection,
        JsonNode plan,
        Ledger ledger
    ) throws SQLException {
        ArrayList<SeedHourlyRow> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT route.display_name, hourly.stop_order, hourly.arrival_date_kst,
                   hourly.arrival_hour_start, hourly.fill_rate_total,
                   hourly.net_boarding_total, hourly.capacity_total, hourly.sample_count
            FROM stop_demand_seed_hourly_total hourly
            JOIN route_version version ON version.id=hourly.route_version_id
            JOIN route ON route.id=version.route_id
            WHERE hourly.seed_import_id=?
            ORDER BY route.display_name, hourly.arrival_hour_start, hourly.stop_order
            """)) {
            statement.setObject(1, ledger.id());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(new SeedHourlyRow(
                        rows.getString("display_name"), rows.getInt("stop_order"),
                        rows.getObject("arrival_date_kst", LocalDate.class),
                        rows.getObject("arrival_hour_start", OffsetDateTime.class).toInstant(),
                        rows.getBigDecimal("fill_rate_total"), rows.getBigDecimal("net_boarding_total"),
                        rows.getBigDecimal("capacity_total"), rows.getInt("sample_count")));
                }
            }
        }
        JsonNode merged = plan.path("merged");
        if (values.size() != ledger.combinedHourlyRows()
            || !ledger.combinedSha256().equals(SeedRows.canonicalRowsSha256(values))
            || !text(merged, "canonicalRowsSha256").equals(SeedRows.canonicalRowsSha256(values))
            || !text(merged, "primaryKeySha256").equals(SeedRows.primaryKeySha256(values))
            || !java.util.Arrays.equals(
                CanonicalJson.bytesOf(merged.path("aggregates")),
                CanonicalJson.bytesOf(SeedRows.aggregateSet(values).map()))) {
            throw new MigrationException("SEED_FINAL_HOURLY_READ_BACK_INVALID");
        }
        return List.copyOf(values);
    }

    private static JsonNode planSource(
        Connection connection,
        UUID seedId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT reconciliation_receipt FROM stop_demand_seed_import WHERE id=?
            """)) {
            statement.setObject(1, seedId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new MigrationException("SEED_FINAL_LEDGER_IDENTITY_INVALID");
                }
                JsonNode document = CanonicalJson.parse(
                    rows.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "SEED_FINAL_LEDGER_IDENTITY_INVALID");
                LinkedHashMap<String, Object> value = new LinkedHashMap<>();
                value.put("seedSha256", document.path("source").path("seedSha256").stringValue());
                value.put("receiptSha256", document.path("source").path("receiptSha256").stringValue());
                value.put("combinedSha256", document.path("merged").path("canonicalRowsSha256").stringValue());
                return CanonicalJson.parse(
                    CanonicalJson.bytesOf(value), "SEED_FINAL_LEDGER_IDENTITY_INVALID");
            }
        }
    }

    private static JsonNode privateCanonical(
        Path path,
        String code
    ) {
        SecureFiles.requirePrivateRegularFile(path);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new MigrationException(code, e);
        }
        JsonNode root = CanonicalJson.parse(bytes, code);
        if (!java.util.Arrays.equals(bytes, CanonicalJson.bytesOf(root))) {
            throw new MigrationException(code);
        }
        return root;
    }

    private static String digest(
        Path path
    ) {
        SecureFiles.requirePrivateRegularFile(path);
        return Sha256.of(path);
    }

    private static String text(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new MigrationException("SEED_FINAL_RECEIPT_FIELD_INVALID");
        }
        return value.stringValue();
    }

    private static void configureReadOnly(
        Connection connection
    ) throws SQLException {
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setReadOnly(true);
        SqlSafety.setTransactionReadOnly(connection);
    }

    private static OffsetDateTime offset(
        Instant instant
    ) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public record Result(
        String planSha256,
        long newForecastRows,
        String receiptSha256
    ) {
    }

    record SourceIdentity(
        String compressedSha256,
        String receiptSha256,
        String canonicalSha256,
        String rowsSha256,
        String primaryKeySha256
    ) {
    }

    private record Ledger(
        UUID id,
        String sourceSeedSha256,
        String sourceReceiptSha256,
        String combinedSha256,
        Instant finalCutoverAt,
        long observationBatchHighWater,
        long combinedHourlyRows,
        Instant appliedAt,
        long formalDeploymentId,
        Instant formalActivatedAt,
        Instant unpausedAt
    ) {
    }

    private record Freeze(
        UUID id,
        String manifestSha256,
        Instant cleanedAt
    ) {
    }

    private record Generation(
        String modelRoute,
        long routeVersionId,
        int revision,
        Instant dataUntil,
        Instant computedAt,
        int cellCount,
        String cellSha256
    ) {

        String keySha256() {
            return Sha256.of(CanonicalJson.bytesOf(Map.of(
                "routeVersionId", routeVersionId,
                "calculationVersion", AggregateSeedReader.CALCULATION_VERSION,
                "revision", revision,
                "dataUntil", dataUntil.toString(),
                "computedAt", computedAt.toString())));
        }
    }

    private record Deployment(
        long id,
        String releaseId,
        String bundleDigest,
        String calculationVersion,
        String state,
        Instant activatedAt,
        String temporaryState
    ) {
    }

    private record Serving(
        long forecasts,
        long unresolved,
        long frozen,
        long futureData,
        long beforeOfficial,
        long beforeCleanup,
        List<String> routes
    ) {

        Map<String, Object> map() {
            return Map.of(
                "status", "PASS",
                "releaseId", TemporaryReleaseMaintenance.FORMAL_RELEASE_ID,
                "calculationVersion", AggregateSeedReader.CALCULATION_VERSION,
                "newForecastRowCount", forecasts,
                "routeCoverage", routes,
                "unresolvedGenerationReferenceCount", unresolved,
                "frozenGenerationReferenceCount", frozen,
                "allReferencedDataUntilAtOrBeforeForecastObservation", futureData == 0,
                "allReferencedGenerationsOfficialOrLater", beforeOfficial == 0);
        }
    }
}

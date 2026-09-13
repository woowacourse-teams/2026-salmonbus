package com.gustler.backend.migration.db;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.Sha256;
import com.gustler.backend.processor.ArrivalCandidate;
import com.gustler.backend.processor.ArrivalLabel;
import com.gustler.backend.processor.ArrivalLabelResolver;
import com.gustler.backend.processor.ObservedVehicle;
import com.gustler.backend.processor.PendingForecast;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

class RdsObservationDeltaBuilder {

    private static final Set<String> SUCCESS_OUTCOMES = Set.of("SUCCESS_ROWS", "SUCCESS_EMPTY");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long GENERATED_GUARD_SECONDS = 60;
    private static final long SETTLEMENT_GUARD_SECONDS = 60;

    Delta build(
        Connection connection,
        AggregateSeedPayload source,
        TemporaryReleaseMaintenance.CutoverBoundary boundary
    ) throws SQLException {
        requireRepeatableReadOnly(connection);
        SnapshotIdentity snapshot = snapshotIdentity(connection, boundary);
        Map<String, RouteBinding> bindings = routeBindings(connection);
        ArrayList<SeedHourlyRow> sourceCutoffReplay = new ArrayList<>();
        ArrayList<SeedHourlyRow> finalReplay = new ArrayList<>();
        LinkedHashMap<String, RouteReplayCounts> routeCounts = new LinkedHashMap<>();
        for (String modelRoute : List.of("1650", "3330")) {
            RouteBinding binding = bindings.get(modelRoute);
            RouteHistory history = history(connection, binding, boundary);
            requireAuthorityOrigins(history, source.sourceCutoffByRoute().get(modelRoute));
            Replay atSource = replay(
                history, binding, source.sourceCutoffByRoute().get(modelRoute),
                source.sourceCutoffByRoute().get(modelRoute));
            Replay atCutover = replay(
                history, binding, boundary.finalCutoverAt(),
                source.sourceCutoffByRoute().get(modelRoute));
            sourceCutoffReplay.addAll(atSource.rows());
            finalReplay.addAll(atCutover.rows());
            routeCounts.put(modelRoute, atCutover.counts());
        }
        sourceCutoffReplay.sort(SeedRows.order());
        finalReplay.sort(SeedRows.order());
        requireSourceParity(source, sourceCutoffReplay);
        Difference difference = difference(source.rows(), finalReplay);
        String finalRowsSha256 = SeedRows.canonicalRowsSha256(finalReplay);
        String finalPrimaryKeySha256 = SeedRows.primaryKeySha256(finalReplay);
        Map<String, Object> snapshotReceipt = snapshot.receipt();
        String snapshotReceiptSha256 = Sha256.of(CanonicalJson.bytesOf(snapshotReceipt));
        Map<String, Object> receipt = receipt(
            source, boundary, sourceCutoffReplay, finalReplay, finalRowsSha256,
            finalPrimaryKeySha256, difference, routeCounts, snapshotReceiptSha256);
        return new Delta(
            List.copyOf(finalReplay),
            difference.rows(),
            routeCounts,
            SeedRows.aggregateSet(finalReplay),
            difference.aggregates(),
            SeedRows.canonicalRowsSha256(sourceCutoffReplay),
            finalRowsSha256,
            finalPrimaryKeySha256,
            difference.changedKeyCount(),
            difference.newKeyCount(),
            difference.capacityRestatedExistingKeyCount(),
            snapshotReceiptSha256,
            Sha256.of(CanonicalJson.bytesOf(receipt)),
            receipt);
    }

    private static void requireRepeatableReadOnly(
        Connection connection
    ) throws SQLException {
        if (!connection.isReadOnly()
            || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) {
            throw new MigrationException("SEED_REPLAY_TRANSACTION_MODE_INVALID");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT current_setting('transaction_isolation'), current_setting('transaction_read_only')
            """); ResultSet rows = statement.executeQuery()) {
            rows.next();
            if (!"repeatable read".equals(rows.getString(1)) || !"on".equals(rows.getString(2))) {
                throw new MigrationException("SEED_REPLAY_TRANSACTION_MODE_INVALID");
            }
        }
    }

    private static SnapshotIdentity snapshotIdentity(
        Connection connection,
        TemporaryReleaseMaintenance.CutoverBoundary boundary
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT transaction_timestamp(), pg_current_snapshot()::text
            """); ResultSet rows = statement.executeQuery()) {
            rows.next();
            return new SnapshotIdentity(
                rows.getObject(1, OffsetDateTime.class).toInstant(), rows.getString(2), boundary);
        }
    }

    private static RouteHistory history(
        Connection connection,
        RouteBinding binding,
        TemporaryReleaseMaintenance.CutoverBoundary boundary
    ) throws SQLException {
        ArrayList<BatchBuilder> builders = new ArrayList<>();
        HashMap<String, Integer> vehicleAxes = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT batch.id AS batch_id, batch.response_received_at, batch.outcome,
                   batch.ingestion_origin, observation.id AS observation_id,
                   observation.source_row_number, observation.vehicle_id,
                   observation.passed_stop_order, observation.remaining_seats
            FROM observation_batch batch
            LEFT JOIN vehicle_observation observation ON observation.observation_batch_id = batch.id
            WHERE batch.route_version_id = ? AND batch.id <= ?
              AND batch.response_received_at < ?
            ORDER BY batch.response_received_at, batch.id, observation.source_row_number
            """)) {
            statement.setLong(1, binding.routeVersionId());
            statement.setLong(2, boundary.observationBatchHighWater());
            statement.setObject(3, offset(boundary.finalCutoverAt()));
            statement.setFetchSize(5_000);
            try (ResultSet rows = statement.executeQuery()) {
                BatchBuilder current = null;
                while (rows.next()) {
                    long batchId = rows.getLong("batch_id");
                    if (current == null || current.id != batchId) {
                        current = new BatchBuilder(
                            batchId,
                            rows.getObject("response_received_at", OffsetDateTime.class).toInstant(),
                            rows.getString("outcome"),
                            rows.getString("ingestion_origin"));
                        builders.add(current);
                    }
                    Long observationId = rows.getObject("observation_id", Long.class);
                    if (observationId == null) {
                        continue;
                    }
                    String privateVehicleId = rows.getString("vehicle_id");
                    int vehicle = privateVehicleId == null
                        ? -1 : vehicleAxes.computeIfAbsent(privateVehicleId, ignored -> vehicleAxes.size());
                    current.observations.add(new Observation(
                        observationId,
                        vehicle,
                        current.observedAt,
                        rows.getInt("passed_stop_order"),
                        rows.getObject("remaining_seats", Integer.class)));
                }
            }
        }
        return new RouteHistory(
            builders.stream().map(BatchBuilder::build).toList(), vehicleAxes.size());
    }

    private static void requireAuthorityOrigins(
        RouteHistory history,
        Instant sourceCutoff
    ) {
        long sourceViolations = history.batches().stream()
            .filter(batch -> batch.observedAt().isBefore(sourceCutoff))
            .filter(batch -> !"S3_BACKFILL".equals(batch.ingestionOrigin()))
            .count();
        long targetViolations = history.batches().stream()
            .filter(batch -> !batch.observedAt().isBefore(sourceCutoff))
            .filter(batch -> !"LIVE".equals(batch.ingestionOrigin()))
            .count();
        if (sourceViolations != 0 || targetViolations != 0) {
            throw new MigrationException("SEED_REPLAY_AUTHORITY_ORIGIN_MISMATCH");
        }
    }

    private static Replay replay(
        RouteHistory history,
        RouteBinding binding,
        Instant throughExclusive,
        Instant sourceCutoff
    ) {
        Map<Integer, Integer> capacities = capacities(history, throughExclusive);
        Map<Integer, List<Observation>> candidates = candidatesByVehicle(history, throughExclusive);
        TreeMap<HourlyKey, BinaryTotals> totals = new TreeMap<>();
        long predictionBatches = 0;
        long structuralTargets = 0;
        long invariantSettledTargets = 0;
        long crossBoundarySettledTargets = 0;
        for (Batch batch : history.batches()) {
            if (!batch.observedAt().isBefore(throughExclusive)
                || !SUCCESS_OUTCOMES.contains(batch.outcome())) {
                continue;
            }
            predictionBatches++;
            for (Observation observation : batch.observations()) {
                if (observation.vehicle() < 0 || observation.remainingSeats() == null) {
                    continue;
                }
                int targetStopOrder = observation.passedStopOrder() + 1;
                if (!binding.boardingStops().contains(targetStopOrder)) {
                    continue;
                }
                structuralTargets++;
                Integer capacity = capacities.get(observation.vehicle());
                if (capacity == null || capacity <= 0) {
                    continue;
                }
                List<Observation> vehicleCandidates =
                    candidates.getOrDefault(observation.vehicle(), List.of());
                SettledEvent settled = invariantSettled(
                    binding.routeVersionId(), batch, observation, targetStopOrder,
                    vehicleCandidates, throughExclusive);
                if (settled == null
                    || settled.observedAt().plusSeconds(SETTLEMENT_GUARD_SECONDS).isAfter(throughExclusive)) {
                    continue;
                }
                invariantSettledTargets++;
                if (batch.observedAt().isBefore(sourceCutoff)
                    && settled.observedAt().plusSeconds(SETTLEMENT_GUARD_SECONDS).isAfter(sourceCutoff)) {
                    crossBoundarySettledTargets++;
                }
                HourlyKey key = new HourlyKey(
                    targetStopOrder, settled.observedAt().truncatedTo(ChronoUnit.HOURS));
                totals.computeIfAbsent(key, ignored -> new BinaryTotals())
                    .add(observation.remainingSeats(), settled.seatsOnArrival(), capacity);
            }
        }
        ArrayList<SeedHourlyRow> rows = new ArrayList<>(totals.size());
        for (Map.Entry<HourlyKey, BinaryTotals> entry : totals.entrySet()) {
            HourlyKey key = entry.getKey();
            BinaryTotals value = entry.getValue();
            rows.add(new SeedHourlyRow(
                binding.modelRoute(), key.stopOrder(), key.hour().atZone(KST).toLocalDate(), key.hour(),
                BigDecimal.valueOf(value.fillRateTotal),
                BigDecimal.valueOf(value.netBoardingTotal),
                BigDecimal.valueOf(value.capacityTotal),
                value.sampleCount));
        }
        return new Replay(
            List.copyOf(rows),
            new RouteReplayCounts(
                predictionBatches,
                history.batches().stream().mapToLong(batch -> batch.observations().size()).sum(),
                structuralTargets,
                invariantSettledTargets,
                crossBoundarySettledTargets,
                rows.size(),
                rows.stream().mapToLong(SeedHourlyRow::sampleCount).sum()));
    }

    private static Map<Integer, Integer> capacities(
        RouteHistory history,
        Instant throughExclusive
    ) {
        HashMap<Integer, Integer> capacities = new HashMap<>();
        for (Batch batch : history.batches()) {
            if (!batch.observedAt().isBefore(throughExclusive)) {
                break;
            }
            for (Observation observation : batch.observations()) {
                if (observation.vehicle() >= 0 && observation.remainingSeats() != null) {
                    capacities.merge(
                        observation.vehicle(), Math.max(observation.remainingSeats(), 1), Math::max);
                }
            }
        }
        return Map.copyOf(capacities);
    }

    private static Map<Integer, List<Observation>> candidatesByVehicle(
        RouteHistory history,
        Instant throughExclusive
    ) {
        HashMap<Integer, List<Observation>> values = new HashMap<>();
        for (Batch batch : history.batches()) {
            if (!batch.observedAt().isBefore(throughExclusive)) {
                break;
            }
            for (Observation observation : batch.observations()) {
                if (observation.vehicle() < 0) {
                    continue;
                }
                values.computeIfAbsent(observation.vehicle(), ignored -> new ArrayList<>())
                    .add(observation);
            }
        }
        return Map.copyOf(values);
    }

    private static SettledEvent invariantSettled(
        long routeVersionId,
        Batch batch,
        Observation observation,
        int targetStopOrder,
        List<Observation> candidates,
        Instant now
    ) {
        PendingForecast baselineForecast = pending(
            routeVersionId, batch, observation, targetStopOrder, batch.observedAt());
        ArrivalLabel baseline = resolve(routeVersionId, baselineForecast, candidates, now);
        if (!(baseline instanceof ArrivalLabel.Settled settled)) {
            return null;
        }
        LinkedHashSet<Instant> cutoffs = new LinkedHashSet<>();
        cutoffs.add(batch.observedAt());
        cutoffs.add(batch.observedAt().plusSeconds(10));
        cutoffs.add(batch.observedAt().plusSeconds(30));
        cutoffs.add(batch.observedAt().plusSeconds(GENERATED_GUARD_SECONDS));
        Instant upper = batch.observedAt().plusSeconds(GENERATED_GUARD_SECONDS);
        for (Observation candidate : candidates) {
            if (candidate.observedAt().isAfter(batch.observedAt())
                && !candidate.observedAt().isAfter(upper)) {
                cutoffs.add(candidate.observedAt());
            }
        }
        for (Instant cutoff : cutoffs) {
            PendingForecast delayed = pending(
                routeVersionId, batch, observation, targetStopOrder, cutoff);
            if (!baseline.equals(resolve(routeVersionId, delayed, candidates, now))) {
                return null;
            }
        }
        Instant observedAt = candidates.stream()
            .filter(candidate -> candidate.id() == settled.arrivalObservationId())
            .map(Observation::observedAt)
            .findFirst()
            .orElseThrow(() -> new MigrationException("SEED_REPLAY_ARRIVAL_MISSING"));
        return new SettledEvent(settled.arrivalObservationId(), settled.seatsOnArrival(), observedAt);
    }

    private static PendingForecast pending(
        long routeVersionId,
        Batch batch,
        Observation observation,
        int targetStopOrder,
        Instant generatedAt
    ) {
        return new PendingForecast(
            observation.id(), targetStopOrder, routeVersionId,
            Integer.toString(observation.vehicle()), 1, batch.observedAt(), generatedAt);
    }

    private static ArrivalLabel resolve(
        long routeVersionId,
        PendingForecast forecast,
        List<Observation> candidates,
        Instant now
    ) {
        int start = firstAfter(candidates, forecast.generatedAt());
        ArrayList<ArrivalCandidate> later = new ArrayList<>();
        Instant previous = forecast.observedAt();
        int passedStopOrder = forecast.passedStopOrder();
        for (int index = start; index < candidates.size(); index++) {
            Observation candidate = candidates.get(index);
            later.add(new ArrivalCandidate(
                candidate.id(),
                new ObservedVehicle(
                    Integer.toString(candidate.vehicle()), routeVersionId,
                    candidate.passedStopOrder(), candidate.observedAt(),
                    candidate.remainingSeats(), null)));
            if (candidate.observedAt().isAfter(previous.plusSeconds(90))
                || candidate.passedStopOrder() < passedStopOrder
                || candidate.passedStopOrder() >= forecast.targetStopOrder()) {
                break;
            }
            previous = candidate.observedAt();
            passedStopOrder = candidate.passedStopOrder();
        }
        return ArrivalLabelResolver.resolve(forecast, later, now);
    }

    private static int firstAfter(
        List<Observation> candidates,
        Instant generatedAt
    ) {
        int low = 0;
        int high = candidates.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (candidates.get(middle).observedAt().isAfter(generatedAt)) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    private static void requireSourceParity(
        AggregateSeedPayload source,
        List<SeedHourlyRow> replay
    ) {
        if (!source.canonicalRowsSha256().equals(SeedRows.canonicalRowsSha256(replay))
            || !source.primaryKeySha256().equals(SeedRows.primaryKeySha256(replay))
            || !aggregateSetsEqual(source.aggregates(), SeedRows.aggregateSet(replay))) {
            throw new MigrationException("SEED_SOURCE_CUTOFF_REPLAY_PARITY_FAILED");
        }
    }

    private static boolean aggregateSetsEqual(
        SeedRows.AggregateSet first,
        SeedRows.AggregateSet second
    ) {
        return first.global().numericallyEquals(second.global())
            && first.routes().get("1650").numericallyEquals(second.routes().get("1650"))
            && first.routes().get("3330").numericallyEquals(second.routes().get("3330"));
    }

    private static Difference difference(
        List<SeedHourlyRow> source,
        List<SeedHourlyRow> finalReplay
    ) {
        Map<RowKey, SeedHourlyRow> sourceByKey = index(source);
        Map<RowKey, SeedHourlyRow> finalByKey = index(finalReplay);
        java.util.TreeSet<RowKey> keys = new java.util.TreeSet<>();
        keys.addAll(sourceByKey.keySet());
        keys.addAll(finalByKey.keySet());
        ArrayList<SeedHourlyRow> rows = new ArrayList<>();
        long changed = 0;
        long newKeys = 0;
        long restated = 0;
        for (RowKey key : keys) {
            SeedHourlyRow sourceRow = sourceByKey.get(key);
            SeedHourlyRow finalRow = finalByKey.get(key);
            if (finalRow == null) {
                throw new MigrationException("SEED_FINAL_REPLAY_DROPPED_SOURCE_KEY");
            }
            if (sourceRow == null) {
                newKeys++;
                changed++;
                rows.add(finalRow);
                continue;
            }
            SeedHourlyRow delta = subtract(finalRow, sourceRow);
            if (!zero(delta)) {
                changed++;
                rows.add(delta);
            }
            if (sourceRow.capacityTotal().compareTo(finalRow.capacityTotal()) != 0) {
                restated++;
            }
        }
        SeedRows.AggregateSet aggregates = SeedRows.aggregateSet(rows);
        return new Difference(List.copyOf(rows), aggregates, changed, newKeys, restated);
    }

    private static Map<RowKey, SeedHourlyRow> index(
        List<SeedHourlyRow> rows
    ) {
        TreeMap<RowKey, SeedHourlyRow> values = new TreeMap<>();
        for (SeedHourlyRow row : rows) {
            values.put(new RowKey(row.modelRoute(), row.stopOrder(), row.arrivalHourStart()), row);
        }
        return Map.copyOf(values);
    }

    private static SeedHourlyRow subtract(
        SeedHourlyRow finalRow,
        SeedHourlyRow sourceRow
    ) {
        return new SeedHourlyRow(
            finalRow.modelRoute(), finalRow.stopOrder(), finalRow.arrivalDateKst(),
            finalRow.arrivalHourStart(),
            finalRow.fillRateTotal().subtract(sourceRow.fillRateTotal()),
            finalRow.netBoardingTotal().subtract(sourceRow.netBoardingTotal()),
            finalRow.capacityTotal().subtract(sourceRow.capacityTotal()),
            finalRow.sampleCount() - sourceRow.sampleCount());
    }

    private static boolean zero(
        SeedHourlyRow row
    ) {
        return row.fillRateTotal().signum() == 0
            && row.netBoardingTotal().signum() == 0
            && row.capacityTotal().signum() == 0
            && row.sampleCount() == 0;
    }

    private static Map<String, RouteBinding> routeBindings(
        Connection connection
    ) throws SQLException {
        LinkedHashMap<String, RouteBinding> bindings = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT route.display_name, version.id, stop.stop_order, stop.boarding_allowed
            FROM route
            JOIN route_version version ON version.route_id = route.id AND version.valid_to IS NULL
            JOIN route_stop stop ON stop.route_version_id = version.id
            WHERE route.display_name IN ('1650', '3330')
            ORDER BY route.display_name, stop.stop_order
            """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String route = rows.getString("display_name");
                long version = rows.getLong("id");
                RouteBinding binding = bindings.computeIfAbsent(
                    route, ignored -> new RouteBinding(route, version, new LinkedHashSet<>()));
                if (binding.routeVersionId() != version) {
                    throw new MigrationException("SEED_ROUTE_VERSION_AMBIGUOUS");
                }
                if (rows.getBoolean("boarding_allowed")) {
                    binding.boardingStops().add(rows.getInt("stop_order"));
                }
            }
        }
        if (!bindings.keySet().equals(Set.of("1650", "3330"))
            || bindings.values().stream().anyMatch(value -> value.boardingStops().isEmpty())) {
            throw new MigrationException("SEED_ROUTE_BINDING_INVALID");
        }
        return Map.copyOf(bindings);
    }

    private static Map<String, Object> receipt(
        AggregateSeedPayload source,
        TemporaryReleaseMaintenance.CutoverBoundary boundary,
        List<SeedHourlyRow> sourceReplay,
        List<SeedHourlyRow> finalReplay,
        String finalRowsSha256,
        String finalPrimaryKeySha256,
        Difference difference,
        Map<String, RouteReplayCounts> routeCounts,
        String snapshotReceiptSha256
    ) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", "stop-demand-rds-full-replay-v1");
        value.put("transactionIsolation", "REPEATABLE READ READ ONLY");
        value.put("sourceSeedSha256", source.compressedSha256());
        value.put("sourceAuthorityCutoffByRoute", Map.of(
            "1650", source.sourceCutoffByRoute().get("1650").toString(),
            "3330", source.sourceCutoffByRoute().get("3330").toString()));
        value.put("upperBoundExclusiveUtc", boundary.finalCutoverAt().toString());
        value.put("observationBatchHighWater", boundary.observationBatchHighWater());
        value.put("sourceCutoffReplayCanonicalRowsSha256", SeedRows.canonicalRowsSha256(sourceReplay));
        value.put("sourceCutoffReplayMatchesSeed", true);
        value.put("finalReplayRowCount", finalReplay.size());
        value.put("finalReplayCanonicalRowsSha256", finalRowsSha256);
        value.put("finalReplayPrimaryKeySha256", finalPrimaryKeySha256);
        value.put("aggregateDelta", difference.aggregates().map());
        value.put("changedKeyCount", difference.changedKeyCount());
        value.put("newKeyCount", difference.newKeyCount());
        value.put("capacityRestatedExistingKeyCount", difference.capacityRestatedExistingKeyCount());
        value.put("routes", routeCountMaps(routeCounts));
        value.put("snapshotReceiptSha256", snapshotReceiptSha256);
        value.put("forbiddenForecastRowsRead", 0);
        value.put("forbiddenStatisticsRowsRead", 0);
        value.put("privacy", Map.of(
            "containsVehicleIdentifiers", false,
            "containsObservationRows", false,
            "containsCredentials", false));
        return value;
    }

    static Map<String, Object> routeCountMaps(
        Map<String, RouteReplayCounts> counts
    ) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        counts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            RouteReplayCounts count = entry.getValue();
            values.put(entry.getKey(), Map.of(
                "predictionBatches", count.predictionBatches(),
                "observations", count.observations(),
                "structuralTargets", count.structuralTargets(),
                "invariantSettledTargets", count.invariantSettledTargets(),
                "crossBoundarySettledTargets", count.crossBoundarySettledTargets(),
                "hourlyRows", count.hourlyRows(),
                "samples", count.samples()));
        });
        return Map.copyOf(values);
    }

    private static OffsetDateTime offset(
        Instant instant
    ) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    record Delta(
        List<SeedHourlyRow> finalRows,
        List<SeedHourlyRow> deltaRows,
        Map<String, RouteReplayCounts> routeCounts,
        SeedRows.AggregateSet finalAggregates,
        SeedRows.AggregateSet deltaAggregates,
        String sourceCutoffReplayCanonicalRowsSha256,
        String finalRowsSha256,
        String finalPrimaryKeySha256,
        long changedKeyCount,
        long newKeyCount,
        long capacityRestatedExistingKeyCount,
        String snapshotReceiptSha256,
        String receiptSha256,
        Map<String, Object> receipt
    ) {

        long sampleCount() {
            return finalAggregates.global().sampleCount();
        }
    }

    record RouteReplayCounts(
        long predictionBatches,
        long observations,
        long structuralTargets,
        long invariantSettledTargets,
        long crossBoundarySettledTargets,
        long hourlyRows,
        long samples
    ) {
    }

    private record Difference(
        List<SeedHourlyRow> rows,
        SeedRows.AggregateSet aggregates,
        long changedKeyCount,
        long newKeyCount,
        long capacityRestatedExistingKeyCount
    ) {
    }

    private record Replay(
        List<SeedHourlyRow> rows,
        RouteReplayCounts counts
    ) {
    }

    private record SnapshotIdentity(
        Instant transactionTimestamp,
        String snapshot,
        TemporaryReleaseMaintenance.CutoverBoundary boundary
    ) {

        Map<String, Object> receipt() {
            return Map.of(
                "schemaVersion", "stop-demand-rds-replay-snapshot-v1",
                "transactionIsolation", "REPEATABLE READ READ ONLY",
                "transactionTimestamp", transactionTimestamp.toString(),
                "snapshot", snapshot,
                "finalCutoverAt", boundary.finalCutoverAt().toString(),
                "observationBatchHighWater", boundary.observationBatchHighWater());
        }
    }

    private record RouteBinding(
        String modelRoute,
        long routeVersionId,
        Set<Integer> boardingStops
    ) {
    }

    private record RouteHistory(
        List<Batch> batches,
        int vehicleCount
    ) {
    }

    private record Batch(
        long id,
        Instant observedAt,
        String outcome,
        String ingestionOrigin,
        List<Observation> observations
    ) {
    }

    private record Observation(
        long id,
        int vehicle,
        Instant observedAt,
        int passedStopOrder,
        Integer remainingSeats
    ) {
    }

    private record SettledEvent(
        long observationId,
        int seatsOnArrival,
        Instant observedAt
    ) {
    }

    private record HourlyKey(
        int stopOrder,
        Instant hour
    ) implements Comparable<HourlyKey> {

        @Override
        public int compareTo(
            HourlyKey other
        ) {
            int byHour = hour.compareTo(other.hour);
            return byHour == 0 ? Integer.compare(stopOrder, other.stopOrder) : byHour;
        }
    }

    private record RowKey(
        String modelRoute,
        int stopOrder,
        Instant hour
    ) implements Comparable<RowKey> {

        @Override
        public int compareTo(
            RowKey other
        ) {
            int byRoute = modelRoute.compareTo(other.modelRoute);
            if (byRoute != 0) {
                return byRoute;
            }
            int byHour = hour.compareTo(other.hour);
            return byHour == 0 ? Integer.compare(stopOrder, other.stopOrder) : byHour;
        }
    }

    private static final class BatchBuilder {

        private final long id;
        private final Instant observedAt;
        private final String outcome;
        private final String ingestionOrigin;
        private final List<Observation> observations = new ArrayList<>();

        private BatchBuilder(
            long id,
            Instant observedAt,
            String outcome,
            String ingestionOrigin
        ) {
            this.id = id;
            this.observedAt = observedAt;
            this.outcome = outcome;
            this.ingestionOrigin = ingestionOrigin;
        }

        private Batch build() {
            return new Batch(id, observedAt, outcome, ingestionOrigin, List.copyOf(observations));
        }
    }

    private static final class BinaryTotals {

        private double fillRateTotal;
        private double netBoardingTotal;
        private double capacityTotal;
        private int sampleCount;

        private void add(
            int currentSeats,
            int arrivalSeats,
            int capacity
        ) {
            fillRateTotal += 1.0d - (double) arrivalSeats / capacity;
            netBoardingTotal += currentSeats - arrivalSeats;
            capacityTotal += capacity;
            sampleCount++;
        }
    }
}

package com.gustler.backend.forecasting.infrastructure.les;

import com.gustler.backend.forecasting.application.les.LesFeatureQuery;
import com.gustler.backend.forecasting.application.les.LesFeatureUnavailableException;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.les.LesFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.les.LesHistoryCompactor;
import com.gustler.backend.forecasting.domain.model.les.LesObservation;
import com.gustler.backend.forecasting.domain.model.les.LesPassEvent;
import com.gustler.backend.forecasting.domain.model.les.LesRouteStatistics;
import com.gustler.backend.forecasting.domain.model.les.LesVehiclePrefix;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 검증된 원점 또는 체크포인트와 DB 후속 관측으로 입력을 복구한다.
 * 다섯 필드는 하나의 복구 유스케이스에 필요한 저장소·지원 범위·자원 제한 설정이다.
 * 원본은 수신 시각, 확인 판본은 입력 확인 시각으로 읽으며 체크포인트도 판본별로 분리한다.
 */
@Repository
public class JdbcLesFeatureQuery implements LesFeatureQuery {
    private static final String READ_HISTORY = """
        WITH batches AS (
          SELECT id,response_received_at,row_number() OVER (ORDER BY response_received_at,id)-1 AS rank
          FROM observation_batch WHERE route_version_id IN (:versions) AND response_received_at IS NOT NULL
            AND (response_received_at,id)<=(:until,:batch)
            AND (:seedBatch=0 OR (response_received_at,id)>(:seedTime,:seedBatch))
        ), observations AS (
          SELECT DISTINCT ON (o.vehicle_id,b.id) o.id,b.id AS batch_id,b.rank,b.response_received_at,
            o.vehicle_id,o.passed_stop_order,o.stop_order,o.running_state,o.remaining_seats,o.crowd_level
          FROM batches b JOIN vehicle_observation o ON o.observation_batch_id=b.id
          ORDER BY o.vehicle_id,b.id,o.source_row_number,o.id
        ) SELECT * FROM observations ORDER BY response_received_at,batch_id,vehicle_id LIMIT :limit
        """;
    private static final String READ_CONFIRMED_HISTORY = """
        WITH batches AS (
          SELECT id,route_version_id,input_confirmed_at AS available_at,response_received_at AS physical_at,
            row_number() OVER (ORDER BY input_confirmed_at,id)-1 AS rank
          FROM observation_batch WHERE route_version_id IN (:versions)
            AND ingestion_origin='LIVE' AND outcome='SUCCESS_ROWS' AND stored_rows>0
            AND input_confirmed_at>=response_received_at
            AND input_confirmed_at<=response_received_at+interval '90 seconds'
            AND (input_confirmed_at,id)<=(:until,:batch)
            AND (:seedBatch=0 OR (input_confirmed_at,id)>(:seedTime,:seedBatch))
        ), observations AS (
          SELECT o.id,b.id AS batch_id,b.rank,b.available_at AS response_received_at,b.physical_at,
            b.route_version_id,o.vehicle_id,o.passed_stop_order,o.stop_order,o.running_state,o.crowd_level,
            CASE WHEN o.remaining_seats BETWEEN 0 AND 70 AND o.seat_unknown_reason IS NULL
                THEN o.remaining_seats END AS remaining_seats,
            count(*) OVER (PARTITION BY o.vehicle_id,b.id) AS identity_rows
          FROM batches b JOIN vehicle_observation o ON o.observation_batch_id=b.id
          WHERE o.vehicle_id IS NOT NULL
        ) SELECT * FROM observations WHERE identity_rows=1
          ORDER BY response_received_at,batch_id,vehicle_id LIMIT :limit
        """;
    private static final String CHECK_ROWS = """
        SELECT EXISTS (
          SELECT batch.id FROM observation_batch batch LEFT JOIN vehicle_observation observation
            ON observation.observation_batch_id=batch.id
          WHERE batch.route_version_id IN (:versions) AND batch.response_received_at IS NOT NULL
            AND (batch.response_received_at,batch.id)<=(:until,:batch)
            AND (:seedBatch=0 OR (batch.response_received_at,batch.id)>(:seedTime,:seedBatch))
          GROUP BY batch.id,batch.stored_rows,batch.outcome
          HAVING count(observation.id)<>coalesce(batch.stored_rows,0)
            OR (batch.outcome IN ('SUCCESS_ROWS','SUCCESS_EMPTY') AND batch.stored_rows IS NULL)
        )
        """;
    private static final String READ_VERSIONS = """
        SELECT DISTINCT version.id FROM route_version version JOIN route route ON route.id=version.route_id
        JOIN observation_batch batch ON batch.route_version_id=version.id
        WHERE route.display_name=:name AND (batch.response_received_at,batch.id)<=(:until,:batch)
        ORDER BY version.id
        """;

    private final JdbcClient jdbc;
    private final Map<Long, Long> origins;
    private final int maximumRows;
    private final boolean rushOnly;
    private final LesHistorySeedReader seeds;
    private final LesHistorySeedReader confirmedSeeds;

    public JdbcLesFeatureQuery(
        JdbcClient jdbc,
        @Value("${forecast.les.history-origin-batch-ids:}") String verifiedOrigins,
        @Value("${forecast.les.maximum-history-rows:250000}") final int maximumRows,
        @Value("${forecast.les.rush-only:true}") final boolean rushOnly,
        @Value("${forecast.les.history-seed-directory:}") String seedDirectory
    ) {
        this.jdbc = jdbc;
        this.maximumRows = maximumRows;
        this.rushOnly = rushOnly;
        this.origins = new HashMap<>();
        this.seeds = new LesHistorySeedReader(seedDirectory, maximumRows);
        String confirmedDirectory = "";
        if (!seedDirectory.isBlank()) {
            confirmedDirectory = Path.of(seedDirectory).resolve(LesRouteStatistics.CONFIRMED_PROFILE).toString();
        }
        this.confirmedSeeds = new LesHistorySeedReader(confirmedDirectory, maximumRows);
        if (maximumRows < 1) {
            throw new IllegalArgumentException("Les history row limit must be positive");
        }
        readOrigins(verifiedOrigins);
    }

    private void readOrigins(String verifiedOrigins) {
        if (verifiedOrigins.isBlank()) {
            return;
        }
        for (String item : verifiedOrigins.split(",")) {
            String[] pair = item.trim().split(":");
            if (pair.length != 2) {
                throw new IllegalArgumentException("Expected routeVersionId:firstBatchId");
            }
            origins.put(Long.parseLong(pair[0]), Long.parseLong(pair[1]));
        }
    }

    @Override
    public Map<Long, LesFeatureSnapshot> read(
        final long batchId,
        RouteStops stops,
        LesRouteStatistics statistics) {
        return read(batchId, stops, statistics, false);
    }

    Map<Long, LesFeatureSnapshot> readForCheckpoint(
        final long batchId,
        RouteStops stops,
        LesRouteStatistics statistics) {
        return read(batchId, stops, statistics, true);
    }

    private Map<Long, LesFeatureSnapshot> read(
        final long batchId,
        RouteStops stops,
        LesRouteStatistics statistics,
        final boolean checkpointOnly
    ) {
        validateRoute(stops, statistics);
        Target target = target(batchId, statistics.confirmedProfile());
        validateScope(stops, statistics, target, checkpointOnly);
        List<Long> versions = compatibleVersions(statistics, target.time(), batchId);
        LesHistorySeedReader reader = seedReader(statistics);
        LesHistorySeedReader.Seed seed = reader.read(stops.routeVersionId());
        validateSeedProfile(seed, statistics);
        ReplayCursor cursor = new ReplayCursor(target, batchId, versions, seed);
        validateHistoryOrigin(stops, cursor);
        List<LesObservation> history = observations(cursor, statistics);
        validateRowsAndAnchor(cursor);
        Map<String, LesVehiclePrefix> prefix = cursor.prefix();
        List<LesPassEvent> events = LesHistoryCompactor.resolve(
            new LesHistoryCompactor.History(prefix, history, cursor.pastEvents()), statistics.realStopOrders());
        Map<Long, LesFeatureSnapshot> snapshots = snapshots(stops, statistics, cursor, history, events);
        saveCheckpoint(stops.routeVersionId(), cursor, history, events, reader);
        return Map.copyOf(snapshots);
    }

    private Target target(final long batchId, final boolean confirmed) {
        String sql = "SELECT route_version_id,response_received_at FROM observation_batch WHERE id=:id AND response_received_at IS NOT NULL";
        return jdbc.sql(profileSql(sql, confirmed))
            .param("id", batchId)
            .query((row, index) -> new Target(
                row.getLong(1), row.getObject(2, OffsetDateTime.class).toInstant(), confirmed))
            .optional()
            .orElseThrow(() -> new LesFeatureUnavailableException("LES_SOURCE_BATCH_MISSING"));
    }

    private void validateScope(
        RouteStops stops,
        LesRouteStatistics statistics,
        Target target,
        final boolean checkpointOnly
    ) {
        if (!checkpointOnly && target.version() != stops.routeVersionId()) {
            throw new LesFeatureUnavailableException("LES_ROUTE_VERSION_MISMATCH");
        }
        if (rushOnly && !isSupportedRush(statistics, target.time())) {
            throw new LesFeatureUnavailableException("LES_OUTSIDE_VALIDATED_RUSH_SCOPE");
        }
    }

    private static boolean isSupportedRush(LesRouteStatistics statistics, Instant at) {
        ZonedDateTime local = at.atZone(ZoneId.of("Asia/Seoul"));
        if (!statistics.dayType(local.toLocalDate()).equals("work")) {
            return false;
        }
        final int minute = local.getHour() * 60 + local.getMinute();
        if (minute >= 360 && minute < 600) {
            return true;
        }
        return minute >= 1020 && minute < 1260;
    }

    private void validateHistoryOrigin(RouteStops stops, ReplayCursor cursor) {
        if (cursor.isOlderThanSeed()) {
            validateRetainedWindow(cursor);
        }
        if (cursor.seed() != null) {
            return;
        }
        Long expected = origins.get(stops.routeVersionId());
        if (expected == null) {
            throw new LesFeatureUnavailableException("LES_HISTORY_ORIGIN_UNVERIFIED");
        }
        Long first = jdbc.sql(
            profileSql("SELECT id FROM observation_batch WHERE route_version_id IN (:versions) AND response_received_at IS NOT NULL ORDER BY response_received_at,id LIMIT 1", cursor.target().confirmed()))
            .param("versions", cursor.versions()).query(Long.class).optional().orElse(null);
        if (!expected.equals(first)) {
            throw new LesFeatureUnavailableException("LES_SOURCE_HISTORY_PURGED");
        }
    }

    private void validateRetainedWindow(ReplayCursor cursor) {
        final boolean prefixBefore = cursor.seed().prefix().values().stream()
            .allMatch(state -> cursor.isStrictlyBefore(state.previous()));
        final boolean retained = cursor.seed().observations().stream()
            .anyMatch(observation -> observation.batchId() == cursor.batchId());
        final boolean empty = jdbc
            .sql("SELECT coalesce(stored_rows=0 AND outcome='SUCCESS_EMPTY',false) FROM observation_batch WHERE id=:id")
            .param("id", cursor.batchId()).query(Boolean.class).single();
        if (isOutsideRetainedWindow(prefixBefore, retained, empty)) {
            throw new LesFeatureUnavailableException("LES_SOURCE_OUTSIDE_CHECKPOINT_WINDOW");
        }
    }

    private static boolean isOutsideRetainedWindow(
        final boolean prefixBefore,
        final boolean retained,
        final boolean empty) {
        if (!prefixBefore) {
            return true;
        }
        return !retained && !empty;
    }

    /**
     * 원본은 빈/실패 배치와 익명 첫 행도 보존한다.
     * 확인 판본은 유효 LIVE 배치만 순위를 매기며 익명과 모호한 중복 차량 행은 전부 제외한다.
     */
    private List<LesObservation> observations(ReplayCursor cursor, LesRouteStatistics statistics) {
        List<LesObservation> history = new ArrayList<>();
        if (cursor.seed() != null) {
            history.addAll(cursor.seed().observations().stream().filter(cursor::includes).toList());
        }
        final long rankOffset = cursor.lastRank() + 1;
        String sql = READ_HISTORY;
        if (statistics.confirmedProfile()) {
            sql = READ_CONFIRMED_HISTORY;
        }
        final int turn = statistics.turn();
        history.addAll(cursor.bind(jdbc.sql(sql)).param("limit", maximumRows + 1)
            .query((row, index) -> observation(row, rankOffset, statistics.confirmedProfile(), turn)).list());
        if (history.size() > maximumRows) {
            throw new LesFeatureUnavailableException("LES_HISTORY_ROW_LIMIT_EXCEEDED");
        }
        return history;
    }

    private static LesObservation observation(
        ResultSet row,
        final long rankOffset,
        final boolean confirmed,
        final int turn
    ) throws SQLException {
        String vehicleId = row.getString("vehicle_id");
        if (vehicleId == null) {
            vehicleId = LesObservation.ANONYMOUS_GROUP;
        }
        if (confirmed) {
            final int stop = row.getInt("stop_order");
            final int state = row.getInt("running_state");
            return new LesObservation(row.getLong("id"), row.getLong("batch_id"), row.getLong("rank") + rankOffset,
                row.getObject("response_received_at", OffsetDateTime.class).toInstant(), vehicleId,
                row.getInt("passed_stop_order"), stop, state, (Integer) row.getObject("remaining_seats"),
                row.getInt("crowd_level"), row.getLong("route_version_id"),
                row.getObject("physical_at", OffsetDateTime.class).toInstant(), isDown(stop, state, turn));
        }
        return new LesObservation(row.getLong("id"), row.getLong("batch_id"), row.getLong("rank") + rankOffset,
            row.getObject("response_received_at", OffsetDateTime.class).toInstant(), vehicleId,
            row.getInt("passed_stop_order"), row.getInt("stop_order"), row.getInt("running_state"),
            (Integer) row.getObject("remaining_seats"), row.getInt("crowd_level"));
    }

    private void validateRowsAndAnchor(ReplayCursor cursor) {
        final boolean incomplete = cursor.bind(jdbc.sql(profileSql(CHECK_ROWS, cursor.target().confirmed())))
            .query(Boolean.class).single();
        if (incomplete) {
            throw new LesFeatureUnavailableException("LES_SOURCE_ROWS_MISSING");
        }
        if (cursor.seed() == null) {
            return;
        }
        final boolean anchorExists = jdbc.sql(
            profileSql("SELECT EXISTS(SELECT 1 FROM observation_batch WHERE id=:id AND route_version_id IN (:versions) AND response_received_at=:time)", cursor.target().confirmed()))
            .param("id", cursor.seedBatchId()).param("versions", cursor.versions())
            .param("time", cursor.seedTime().atOffset(ZoneOffset.UTC)).query(Boolean.class).single();
        if (!anchorExists) {
            throw new LesFeatureUnavailableException("LES_HISTORY_SEED_CONTINUITY_UNVERIFIED");
        }
    }

    private Map<Long, LesFeatureSnapshot> snapshots(
        RouteStops stops,
        LesRouteStatistics statistics,
        ReplayCursor cursor,
        List<LesObservation> history,
        List<LesPassEvent> events
    ) {
        Set<Long> eligible = new HashSet<>(jdbc
            .sql("SELECT id FROM forecast_observation_quality WHERE observation_batch_id=:batch AND forecast_eligible")
            .param("batch", cursor.batchId()).query(Long.class).list());
        Map<Long, LesFeatureSnapshot> result = new LinkedHashMap<>();
        for (LesObservation source : history) {
            if (!isEligibleSource(source, cursor.batchId(), eligible)) {
                continue;
            }
            if (hasCapacity(source, cursor.prefix(), history, statistics.confirmedProfile())) {
                result.put(source.observationId(), new LesFeatureSnapshot(stops.routeVersionId(), source, statistics,
                    history, events, cursor.prefix()));
            }
        }
        return result;
    }

    private LesHistorySeedReader seedReader(LesRouteStatistics statistics) {
        if (statistics.confirmedProfile()) {
            return confirmedSeeds;
        }
        return seeds;
    }

    private static void validateSeedProfile(LesHistorySeedReader.Seed seed, LesRouteStatistics statistics) {
        if (seed == null) {
            return;
        }
        List<LesObservation> evidence = new ArrayList<>(seed.observations());
        seed.prefix().values().forEach(state -> evidence.add(state.previous()));
        seed.events().forEach(event -> evidence.add(event.arrival()));
        if (evidence.stream().anyMatch(row -> row.confirmedProfile() != statistics.confirmedProfile())) {
            throw new LesFeatureUnavailableException("LES_SEED_PROFILE_MISMATCH");
        }
    }

    private static boolean isDown(final int stop, final int state, final int turn) {
        return stop > turn || (stop == turn && state == 2) || (stop == 1 && state != 2);
    }

    static String profileSql(String sql, final boolean confirmed) {
        if (!confirmed) {
            return sql;
        }
        String predicate = "ingestion_origin='LIVE' AND outcome='SUCCESS_ROWS' AND stored_rows>0 "
            + "AND input_confirmed_at>=response_received_at "
            + "AND input_confirmed_at<=response_received_at+interval '90 seconds'";
        String alias = "";
        if (sql.contains("batch.response_received_at")) {
            alias = "batch.";
        }
        String qualified = predicate.replace("ingestion_origin", alias + "ingestion_origin")
            .replace("outcome", alias + "outcome").replace("stored_rows", alias + "stored_rows")
            .replace("input_confirmed_at", alias + "input_confirmed_at")
            .replace("response_received_at", alias + "response_received_at");
        String transformed = sql.replace("response_received_at", "input_confirmed_at");
        return transformed.replace("WHERE ", "WHERE " + qualified + " AND ");
    }

    private static boolean isEligibleSource(LesObservation source, final long batchId, Set<Long> eligible) {
        if (source.batchId() != batchId || !source.hasVehicleIdentity()) {
            return false;
        }
        return source.seats() != null && eligible.contains(source.observationId());
    }

    private static boolean hasCapacity(
        LesObservation source,
        Map<String, LesVehiclePrefix> prefix,
        List<LesObservation> history,
        final boolean confirmed
    ) {
        final int minimum = minimumCapacity(confirmed);
        if (prefix.containsKey(source.vehicleId()) && prefix.get(source.vehicleId()).capacity() >= minimum) {
            return true;
        }
        return history.stream().anyMatch(observation -> isCapacityObservation(observation, source.vehicleId())
            && observation.seats() >= minimum);
    }

    private static int minimumCapacity(final boolean confirmed) {
        if (confirmed) {
            return 30;
        }
        return 1;
    }

    private static boolean isCapacityObservation(LesObservation observation, String vehicleId) {
        if (!observation.vehicleId().equals(vehicleId) || observation.seats() == null) {
            return false;
        }
        return observation.seats() > 0 && observation.seats() <= 70;
    }

    private void saveCheckpoint(
        final long routeVersionId,
        ReplayCursor cursor,
        List<LesObservation> history,
        List<LesPassEvent> events,
        LesHistorySeedReader reader
    ) {
        final long newBatches = cursor.bind(jdbc.sql(
            profileSql("SELECT count(*) FROM observation_batch WHERE route_version_id IN (:versions) AND response_received_at IS NOT NULL AND (response_received_at,id)<=(:until,:batch) AND (:seedBatch=0 OR (response_received_at,id)>(:seedTime,:seedBatch))", cursor.target().confirmed())))
            .query(Long.class).single();
        if (!cursor.isOlderThanSeed()) {
            reader.write(routeVersionId, new LesHistorySeedReader.Seed(history, cursor.batchId(),
                cursor.lastRank() + newBatches, cursor.target().time(), cursor.prefix(), events));
        }
    }

    private List<Long> compatibleVersions(LesRouteStatistics statistics, Instant until, final long batchId) {
        List<Long> versions = jdbc.sql(profileSql(READ_VERSIONS, statistics.confirmedProfile())).param("name", statistics.route())
            .param("until", until.atOffset(ZoneOffset.UTC)).param("batch", batchId).query(Long.class).list();
        if (versions.isEmpty()) {
            throw new LesFeatureUnavailableException("LES_ROUTE_HISTORY_MISSING");
        }
        for (final long version : versions) {
            validateHistoricalVersion(version, statistics);
        }
        return versions;
    }

    private void validateHistoricalVersion(final long version, LesRouteStatistics statistics) {
        List<RouteStop> reference = jdbc.sql(
            "SELECT stop_order,stop_id,boarding_allowed,direction FROM route_stop WHERE route_version_id=:version ORDER BY stop_order")
            .param("version", version)
            .query((row, index) -> new RouteStop(version, row.getInt(1), row.getString(2), row.getBoolean(3),
                RouteDirection.valueOf(row.getString(4))))
            .list();
        try {
            validateRoute(new RouteStops(version, "history", reference, statistics.route()), statistics);
        } catch (LesFeatureUnavailableException failure) {
            throw new LesFeatureUnavailableException("LES_INCOMPATIBLE_HISTORICAL_ROUTE_VERSION:" + version);
        }
    }

    private static void validateRoute(RouteStops route, LesRouteStatistics statistics) {
        if (!route.routeName().isBlank() && !route.routeName().equals(statistics.route())) {
            throw new LesFeatureUnavailableException("LES_ROUTE_NAME_MISMATCH");
        }
        if (route.stops().size() != statistics.stops().size()) {
            throw new LesFeatureUnavailableException("LES_ROUTE_REFERENCE_MISMATCH");
        }
        for (RouteStop stop : route.stops()) {
            if (!matchesReference(stop, statistics.stop(stop.stopOrder()))) {
                throw new LesFeatureUnavailableException("LES_ROUTE_REFERENCE_MISMATCH");
            }
        }
    }

    private static boolean matchesReference(RouteStop stop, LesRouteStatistics.Stop reference) {
        if (!stop.stopId().equals(reference.id()) || stop.boardingAllowed() != reference.boardingAllowed()) {
            return false;
        }
        return stop.direction().name().equals(directionName(reference));
    }

    private static String directionName(LesRouteStatistics.Stop reference) {
        if (reference.down()) {
            return "DOWN";
        }
        return "UP";
    }

    private record Target(long version, Instant time, boolean confirmed) {
    }

    private record ReplayCursor(Target target, long batchId, List<Long> versions, LesHistorySeedReader.Seed seed) {
        private boolean isOlderThanSeed() {
            if (seed == null) {
                return false;
            }
            if (target.time().isBefore(seed.through())) {
                return true;
            }
            return target.time().equals(seed.through()) && batchId < seed.batchId();
        }

        private boolean isStrictlyBefore(LesObservation observation) {
            if (observation.receivedAt().isBefore(target.time())) {
                return true;
            }
            return observation.receivedAt().equals(target.time()) && observation.batchId() < batchId;
        }

        private boolean includes(LesObservation observation) {
            if (observation.receivedAt().isBefore(target.time())) {
                return true;
            }
            return observation.receivedAt().equals(target.time()) && observation.batchId() <= batchId;
        }

        private long seedBatchId() {
            if (seed == null) {
                return 0;
            }
            return seed.batchId();
        }

        private Instant seedTime() {
            if (seed == null) {
                return Instant.EPOCH;
            }
            return seed.through();
        }

        private long lastRank() {
            if (seed == null) {
                return -1;
            }
            return seed.rank();
        }

        private Map<String, LesVehiclePrefix> prefix() {
            if (seed == null) {
                return Map.of();
            }
            return seed.prefix();
        }

        private List<LesPassEvent> pastEvents() {
            if (seed == null) {
                return List.of();
            }
            return seed.events().stream().filter(this::isEventAvailable).toList();
        }

        private boolean isEventAvailable(LesPassEvent event) {
            if (event.confirmedAt().isBefore(target.time())) {
                return true;
            }
            return event.confirmedAt().equals(target.time()) && event.confirmation().batchId() <= batchId;
        }

        private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec query) {
            return query.param("versions", versions).param("until", target.time().atOffset(ZoneOffset.UTC))
                .param("batch", batchId).param("seedBatch", seedBatchId())
                .param("seedTime", seedTime().atOffset(ZoneOffset.UTC));
        }
    }
}

package com.gustler.backend.forecasting.infrastructure.alighting;

import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareFeatureQuery;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareFeatureUnavailableException;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareHistoryCompactor;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareVehiclePrefix;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 검증된 원점 또는 체크포인트와 DB 후속 관측으로 입력을 복구한다.
 * 다섯 필드는 하나의 복구 유스케이스에 필요한 저장소·지원 범위·자원 제한 설정이다.
 * 원본은 수신 시각, 확인 판본은 입력 확인 시각으로 읽으며 체크포인트도 판본별로 분리한다.
 */
@Repository
public class JdbcAlightingAwareFeatureQuery implements AlightingAwareFeatureQuery {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(JdbcAlightingAwareFeatureQuery.class);
    private static final String READ_HISTORY = """
        WITH batches AS (
          SELECT id,response_received_at,row_number() OVER (ORDER BY response_received_at,id)-1 AS rank
          FROM observation_batch WHERE route_version_id IN (:versions) AND response_received_at IS NOT NULL
            %s
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
            AND %s
            %s
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
            %s
          GROUP BY batch.id,batch.stored_rows,batch.outcome
          HAVING count(observation.id)<>coalesce(batch.stored_rows,0)
            OR (batch.outcome IN ('SUCCESS_ROWS','SUCCESS_EMPTY') AND batch.stored_rows IS NULL)
        )
        """;
    private static final String COUNT_BATCHES = """
        SELECT count(*) FROM observation_batch
        WHERE route_version_id IN (:versions) AND response_received_at IS NOT NULL
          %s
        """;
    private static final String READ_VERSIONS = """
        SELECT version.id FROM route_version version JOIN route route ON route.id=version.route_id
        WHERE route.display_name=:name AND EXISTS (%s)
        ORDER BY version.id
        """;
    private static final String VERSION_HAS_HISTORY = """
        SELECT 1 FROM observation_batch batch
        WHERE batch.route_version_id=version.id AND (batch.response_received_at,batch.id)<=(:until,:batch)
        """;
    private static final String CONFIRMED_INPUT = """
        ingestion_origin='LIVE' AND outcome='SUCCESS_ROWS' AND stored_rows>0
        AND input_confirmed_at>=response_received_at
        AND input_confirmed_at<=response_received_at+%s
        """.formatted(ConfirmedAlightingAwareInput.MAX_CONFIRMATION_DELAY).strip();

    private final JdbcClient jdbc;
    private final VehicleCapacityCache capacities;
    private final Map<Long, Long> origins;
    private final int maximumRows;
    private final boolean rushOnly;
    private final AlightingAwareHistorySeedReader seeds;
    private final AlightingAwareHistorySeedReader confirmedSeeds;

    public JdbcAlightingAwareFeatureQuery(
        JdbcClient jdbc,
        VehicleCapacityCache capacities,
        @Value("${forecast.alighting.history-origin-batch-ids:}") String verifiedOrigins,
        @Value("${forecast.alighting.maximum-history-rows:250000}") final int maximumRows,
        @Value("${forecast.alighting.rush-only:true}") final boolean rushOnly,
        @Value("${forecast.alighting.history-seed-directory:}") String seedDirectory
    ) {
        this.jdbc = jdbc;
        this.capacities = capacities;
        this.maximumRows = maximumRows;
        this.rushOnly = rushOnly;
        this.origins = new HashMap<>();
        this.seeds = new AlightingAwareHistorySeedReader(seedDirectory, maximumRows);
        this.confirmedSeeds = AlightingAwareHistorySeedReader.confirmed(seedDirectory, maximumRows);
        if (maximumRows < 1) {
            throw new IllegalArgumentException("AlightingAware history row limit must be positive");
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
    public Map<Long, AlightingAwareFeatureSnapshot> read(
        final long batchId,
        RouteStops stops,
        AlightingAwareRouteStatistics statistics) {
        return read(batchId, stops, statistics, false, false);
    }

    Map<Long, AlightingAwareFeatureSnapshot> readForCheckpoint(
        final long batchId,
        RouteStops stops,
        AlightingAwareRouteStatistics statistics) {
        return read(batchId, stops, statistics, true, false);
    }

    @Override
    public Map<Long, AlightingAwareFeatureSnapshot> readExtended(
        final long batchId, RouteStops stops, AlightingAwareRouteStatistics statistics
    ) {
        return read(batchId, stops, statistics, false, true);
    }

    private Map<Long, AlightingAwareFeatureSnapshot> read(
        final long batchId,
        RouteStops stops,
        AlightingAwareRouteStatistics statistics,
        final boolean checkpointOnly,
        final boolean extendedHours
    ) {
        validateRoute(stops, statistics);
        Target target = target(batchId, statistics.confirmedProfile());
        validateScope(stops, statistics, target, checkpointOnly, extendedHours);
        List<Long> versions = compatibleVersions(statistics, target.time(), batchId);
        AlightingAwareHistorySeedReader reader = seedReader(statistics);
        AlightingAwareHistorySeedReader.Seed seed = reader.read(stops.routeVersionId());
        validateSeedProfile(seed, statistics);
        ReplayCursor cursor = new ReplayCursor(target, batchId, versions, seed);
        validateHistoryOrigin(stops, cursor);
        List<AlightingAwareObservation> history = observations(cursor, statistics);
        validateRowsAndAnchor(cursor);
        Map<String, AlightingAwareVehiclePrefix> prefix = cursor.prefix();
        List<AlightingAwarePassEvent> events = AlightingAwareHistoryCompactor.resolve(
            new AlightingAwareHistoryCompactor.History(prefix, history, cursor.pastEvents()), statistics.realStopOrders());
        Map<Long, AlightingAwareFeatureSnapshot> snapshots = checkpointOnly
            ? Map.of() : snapshots(stops, statistics, cursor, history, events);
        saveCheckpoint(stops.routeVersionId(), cursor, history, events, reader);
        if (!checkpointOnly && cursor.target().confirmed() && reader.configured() && !cursor.isOlderThanSeed()) {
            protectCheckpoint(stops.routeVersionId(), cursor);
        }
        return Map.copyOf(snapshots);
    }

    private Target target(final long batchId, final boolean confirmed) {
        String sql = "SELECT route_version_id,response_received_at FROM observation_batch WHERE id=:id AND response_received_at IS NOT NULL";
        return jdbc.sql(profileSql(sql, confirmed))
            .param("id", batchId)
            .query((row, index) -> new Target(
                row.getLong(1), row.getObject(2, OffsetDateTime.class).toInstant(), confirmed))
            .optional()
            .orElseThrow(() -> new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_SOURCE_BATCH_MISSING"));
    }

    private void validateScope(
        RouteStops stops,
        AlightingAwareRouteStatistics statistics,
        Target target,
        final boolean checkpointOnly,
        final boolean extendedHours
    ) {
        if (!checkpointOnly && target.version() != stops.routeVersionId()) {
            throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_ROUTE_VERSION_MISMATCH");
        }
        if (rushOnly && !extendedHours && !statistics.timeCell(target.time().atZone(ZoneId.of("Asia/Seoul"))).isRush()) {
            throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_OUTSIDE_VALIDATED_RUSH_SCOPE");
        }
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
            throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_HISTORY_ORIGIN_UNVERIFIED");
        }
        Long first = jdbc.sql(
            profileSql("SELECT id FROM observation_batch WHERE route_version_id IN (:versions) AND response_received_at IS NOT NULL ORDER BY response_received_at,id LIMIT 1", cursor.target().confirmed()))
            .param("versions", cursor.versions()).query(Long.class).optional().orElse(null);
        if (!expected.equals(first)) {
            throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_SOURCE_HISTORY_PURGED");
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
            throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_SOURCE_OUTSIDE_CHECKPOINT_WINDOW");
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
    private List<AlightingAwareObservation> observations(ReplayCursor cursor, AlightingAwareRouteStatistics statistics) {
        List<AlightingAwareObservation> history = new ArrayList<>();
        if (cursor.seed() != null) {
            history.addAll(cursor.seed().observations().stream().filter(cursor::includes).toList());
        }
        final long rankOffset = cursor.lastRank() + 1;
        String window = cursor.window("");
        String sql = statistics.confirmedProfile()
            ? READ_CONFIRMED_HISTORY.formatted(CONFIRMED_INPUT, window)
            : READ_HISTORY.formatted(window);
        final int turn = statistics.turn();
        history.addAll(cursor.bind(jdbc.sql(sql)).param("limit", maximumRows + 1)
            .query((row, index) -> observation(row, rankOffset, statistics.confirmedProfile(), turn)).list());
        if (history.size() > maximumRows) {
            throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_HISTORY_ROW_LIMIT_EXCEEDED");
        }
        return history;
    }

    private static AlightingAwareObservation observation(
        ResultSet row,
        final long rankOffset,
        final boolean confirmed,
        final int turn
    ) throws SQLException {
        String vehicleId = row.getString("vehicle_id");
        if (vehicleId == null) {
            vehicleId = AlightingAwareObservation.ANONYMOUS_GROUP;
        }
        if (confirmed) {
            final int stop = row.getInt("stop_order");
            final int state = row.getInt("running_state");
            return new AlightingAwareObservation(row.getLong("id"), row.getLong("batch_id"), row.getLong("rank") + rankOffset,
                row.getObject("response_received_at", OffsetDateTime.class).toInstant(), vehicleId,
                row.getInt("passed_stop_order"), stop, state, (Integer) row.getObject("remaining_seats"),
                row.getInt("crowd_level"), row.getLong("route_version_id"),
                row.getObject("physical_at", OffsetDateTime.class).toInstant(), isDown(stop, state, turn));
        }
        return new AlightingAwareObservation(row.getLong("id"), row.getLong("batch_id"), row.getLong("rank") + rankOffset,
            row.getObject("response_received_at", OffsetDateTime.class).toInstant(), vehicleId,
            row.getInt("passed_stop_order"), row.getInt("stop_order"), row.getInt("running_state"),
            (Integer) row.getObject("remaining_seats"), row.getInt("crowd_level"));
    }

    private void validateRowsAndAnchor(ReplayCursor cursor) {
        String sql = profileSql(CHECK_ROWS, cursor.target().confirmed()).formatted(cursor.window("batch."));
        final boolean incomplete = cursor.bind(jdbc.sql(sql)).query(Boolean.class).single();
        if (incomplete) {
            throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_SOURCE_ROWS_MISSING");
        }
        if (cursor.seed() == null) {
            return;
        }
        final boolean anchorExists = jdbc.sql(
            profileSql("SELECT EXISTS(SELECT 1 FROM observation_batch WHERE id=:id AND route_version_id IN (:versions) AND response_received_at=:time)", cursor.target().confirmed()))
            .param("id", cursor.seedBatchId()).param("versions", cursor.versions())
            .param("time", cursor.seedTime().atOffset(ZoneOffset.UTC)).query(Boolean.class).single();
        if (!anchorExists) {
            throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_HISTORY_SEED_CONTINUITY_UNVERIFIED");
        }
    }

    private Map<Long, AlightingAwareFeatureSnapshot> snapshots(
        RouteStops stops,
        AlightingAwareRouteStatistics statistics,
        ReplayCursor cursor,
        List<AlightingAwareObservation> history,
        List<AlightingAwarePassEvent> events
    ) {
        String sourceId = jdbc.sql("""
            SELECT route.source_id FROM route_version version JOIN route ON route.id=version.route_id
            WHERE version.id=:version
            """).param("version", stops.routeVersionId()).query(String.class).single();
        List<AlightingAwareObservation> sources = history.stream()
            .filter(source -> isEligibleSource(source, cursor.batchId())).toList();
        var known = capacities.findKnownAt(sourceId, sources.stream().map(AlightingAwareObservation::vehicleId).toList(),
            cursor.target().time(), cursor.batchId());
        Map<Long, AlightingAwareFeatureSnapshot> result = new LinkedHashMap<>();
        sources.forEach(source -> {
            var capacity = known.get(source.vehicleId());
            if (capacity != null && capacity.seats() >= minimumCapacity(statistics.confirmedProfile())) {
                result.put(source.observationId(), new AlightingAwareFeatureSnapshot(stops.routeVersionId(), source,
                    capacity.seats(), statistics, history, events, cursor.prefix()));
            } else {
                String reason = capacity == null ? "VEHICLE_CAPACITY_UNKNOWN" : "VEHICLE_CAPACITY_OUTSIDE_MODEL_SCOPE";
                LOGGER.info("event=forecast_vehicle_withheld reason={} batchId={} vehicleId={}", reason,
                    cursor.batchId(),source.vehicleId());
            }
        });
        return result;
    }

    private AlightingAwareHistorySeedReader seedReader(AlightingAwareRouteStatistics statistics) {
        if (statistics.confirmedProfile()) {
            return confirmedSeeds;
        }
        return seeds;
    }

    private static void validateSeedProfile(AlightingAwareHistorySeedReader.Seed seed, AlightingAwareRouteStatistics statistics) {
        if (seed == null) {
            return;
        }
        List<AlightingAwareObservation> evidence = new ArrayList<>(seed.observations());
        seed.prefix().values().forEach(state -> evidence.add(state.previous()));
        seed.events().forEach(event -> evidence.add(event.arrival()));
        if (evidence.stream().anyMatch(row -> row.confirmedProfile() != statistics.confirmedProfile())) {
            throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_SEED_PROFILE_MISMATCH");
        }
    }

    private static boolean isDown(final int stop, final int state, final int turn) {
        return stop > turn || (stop == turn && state == 2) || (stop == 1 && state != 2);
    }

    static String profileSql(String sql, final boolean confirmed) {
        if (!confirmed) {
            return sql;
        }
        String alias = "";
        if (sql.contains("batch.response_received_at")) {
            alias = "batch.";
        }
        String qualified = CONFIRMED_INPUT.replace("ingestion_origin", alias + "ingestion_origin")
            .replace("outcome", alias + "outcome").replace("stored_rows", alias + "stored_rows")
            .replace("input_confirmed_at", alias + "input_confirmed_at")
            .replace("response_received_at", alias + "response_received_at");
        String transformed = sql.replace("response_received_at", "input_confirmed_at");
        return transformed.replace("WHERE ", "WHERE " + qualified + " AND ");
    }

    private static boolean isEligibleSource(AlightingAwareObservation source, final long batchId) {
        if (source.batchId() != batchId || !source.hasVehicleIdentity()) {
            return false;
        }
        return source.seats() != null;
    }

    private static int minimumCapacity(final boolean confirmed) {
        if (confirmed) {
            return 30;
        }
        return 1;
    }

    private void saveCheckpoint(
        final long routeVersionId,
        ReplayCursor cursor,
        List<AlightingAwareObservation> history,
        List<AlightingAwarePassEvent> events,
        AlightingAwareHistorySeedReader reader
    ) {
        String sql = profileSql(COUNT_BATCHES, cursor.target().confirmed()).formatted(cursor.window(""));
        final long newBatches = cursor.bind(jdbc.sql(sql)).query(Long.class).single();
        if (!cursor.isOlderThanSeed()) {
            reader.write(routeVersionId, new AlightingAwareHistorySeedReader.Seed(history, cursor.batchId(),
                cursor.lastRank() + newBatches, cursor.target().time(), cursor.prefix(), events));
        }
    }

    private void protectCheckpoint(final long routeVersionId, ReplayCursor cursor) {
        // 뒤에 확정된 배치도 체크포인트보다 최대 90초 먼저 수신됐을 수 있다.
        jdbc.sql("""
            INSERT INTO alighting_history_checkpoint(route_version_id,through_at,through_batch_id,retain_from)
            VALUES(:version,:through,:batch,:through-%s)
            ON CONFLICT(route_version_id) DO UPDATE
            SET through_at=EXCLUDED.through_at, through_batch_id=EXCLUDED.through_batch_id,
                retain_from=EXCLUDED.retain_from
            WHERE (alighting_history_checkpoint.through_at,alighting_history_checkpoint.through_batch_id)
                < (EXCLUDED.through_at,EXCLUDED.through_batch_id)
            """.formatted(ConfirmedAlightingAwareInput.MAX_CONFIRMATION_DELAY))
            .param("version", routeVersionId).param("through", cursor.target().time().atOffset(ZoneOffset.UTC))
            .param("batch", cursor.batchId()).update();
    }

    private List<Long> compatibleVersions(AlightingAwareRouteStatistics statistics, Instant until, final long batchId) {
        String history = profileSql(VERSION_HAS_HISTORY, statistics.confirmedProfile());
        List<Long> versions = jdbc.sql(READ_VERSIONS.formatted(history)).param("name", statistics.route())
            .param("until", until.atOffset(ZoneOffset.UTC)).param("batch", batchId).query(Long.class).list();
        if (versions.isEmpty()) {
            throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_ROUTE_HISTORY_MISSING");
        }
        for (final long version : versions) {
            validateHistoricalVersion(version, statistics);
        }
        return versions;
    }

    private void validateHistoricalVersion(final long version, AlightingAwareRouteStatistics statistics) {
        List<RouteStop> reference = jdbc.sql(
            "SELECT stop_order,stop_id,boarding_allowed,direction FROM route_stop WHERE route_version_id=:version ORDER BY stop_order")
            .param("version", version)
            .query((row, index) -> new RouteStop(version, row.getInt(1), row.getString(2), row.getBoolean(3),
                RouteDirection.valueOf(row.getString(4))))
            .list();
        try {
            validateRoute(new RouteStops(version, "history", reference, statistics.route()), statistics);
        } catch (AlightingAwareFeatureUnavailableException failure) {
            throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_INCOMPATIBLE_HISTORICAL_ROUTE_VERSION:" + version);
        }
    }

    private static void validateRoute(RouteStops route, AlightingAwareRouteStatistics statistics) {
        if (!route.routeName().isBlank() && !route.routeName().equals(statistics.route())) {
            throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_ROUTE_NAME_MISMATCH");
        }
        if (route.stops().size() != statistics.stops().size()) {
            throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_ROUTE_REFERENCE_MISMATCH");
        }
        for (RouteStop stop : route.stops()) {
            if (!matchesReference(stop, statistics.stop(stop.stopOrder()))) {
                throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_ROUTE_REFERENCE_MISMATCH");
            }
        }
    }

    private static boolean matchesReference(RouteStop stop, AlightingAwareRouteStatistics.Stop reference) {
        if (!stop.stopId().equals(reference.id()) || stop.boardingAllowed() != reference.boardingAllowed()) {
            return false;
        }
        return stop.direction().name().equals(directionName(reference));
    }

    private static String directionName(AlightingAwareRouteStatistics.Stop reference) {
        if (reference.down()) {
            return "DOWN";
        }
        return "UP";
    }

    private record Target(long version, Instant time, boolean confirmed) {
    }

    private record ReplayCursor(Target target, long batchId, List<Long> versions, AlightingAwareHistorySeedReader.Seed seed) {
        private boolean isOlderThanSeed() {
            if (seed == null) {
                return false;
            }
            if (target.time().isBefore(seed.through())) {
                return true;
            }
            return target.time().equals(seed.through()) && batchId < seed.batchId();
        }

        private boolean isStrictlyBefore(AlightingAwareObservation observation) {
            if (observation.receivedAt().isBefore(target.time())) {
                return true;
            }
            return observation.receivedAt().equals(target.time()) && observation.batchId() < batchId;
        }

        private boolean includes(AlightingAwareObservation observation) {
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

        private Map<String, AlightingAwareVehiclePrefix> prefix() {
            if (seed == null) {
                return Map.of();
            }
            return seed.prefix();
        }

        private List<AlightingAwarePassEvent> pastEvents() {
            if (seed == null) {
                return List.of();
            }
            return seed.events().stream().filter(this::isEventAvailable).toList();
        }

        private boolean isEventAvailable(AlightingAwarePassEvent event) {
            if (event.confirmedAt().isBefore(target.time())) {
                return true;
            }
            return event.confirmedAt().equals(target.time()) && event.confirmation().batchId() <= batchId;
        }

        private String window(String prefix) {
            String received = prefix + "response_received_at";
            String orderedAt = target.confirmed() ? prefix + "input_confirmed_at" : received;
            String order = "(" + orderedAt + "," + prefix + "id)";
            String window = "AND " + order + "<=(:until,:batch)";
            if (target.confirmed()) {
                // 확정 시각은 수신 시각부터 최대 허용 시간 이내다. 수신 시각 인덱스로 후보를 좁힌다.
                window += " AND " + received + "<=:until";
                if (seedBatchId() != 0) {
                    window += " AND " + received + ">=:seedTime-" + ConfirmedAlightingAwareInput.MAX_CONFIRMATION_DELAY;
                }
            }
            if (seedBatchId() != 0) {
                window += " AND " + order + ">(:seedTime,:seedBatch)";
            }
            return window;
        }

        private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec query) {
            query.param("versions", versions).param("until", target.time().atOffset(ZoneOffset.UTC))
                .param("batch", batchId);
            if (seedBatchId() != 0) {
                query.param("seedBatch", seedBatchId()).param("seedTime", seedTime().atOffset(ZoneOffset.UTC));
            }
            return query;
        }
    }
}

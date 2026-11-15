package com.gustler.backend.forecasting.infrastructure.pipeline;

import com.gustler.backend.forecasting.application.pipeline.ForecastBatch;
import com.gustler.backend.forecasting.application.pipeline.ForecastObservationRow;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 한 노선의 관측 흐름을 DB에 넣는다. 여러 차량의 여러 운행, 90초·30분을 넘는 공백, 좌석 모름과 범위 밖 좌석,
 * 한 배치의 같은 차량 중복과 차량 번호 없는 줄, 빈 배치·늦게 착수한 배치·정상 수집이 아닌 배치·실패 배치,
 * 노선 판본 전환을 섞는다. 같은 씨앗이면 같은 흐름이다.
 * 수집은 같은 배치에 같은 차량을 두 번 넣지 않지만(고유 색인) 예보 읽기는 그 경우를 따로 거르므로, 그 규칙까지 대조하려고
 * 테스트 트랜잭션 안에서 고유 색인을 지운다. 트랜잭션이 롤백되면 색인도 돌아온다.
 */
final class ObservationStreamFixture {
    static final String SOURCE_ID = "GBIS";
    static final int STOP_COUNT = 14;
    static final int TURN = 7;
    private static final int VIA_STOP = 10;
    private static final String ROW_PLACEHOLDERS = "(?,?,?,?,?,?,?,?,?,?,?)";

    private ObservationStreamFixture() {
    }

    /** 넣은 흐름의 노선 이름, 판본별 정류장, 통계, 배치 번호(처리 순서)다. */
    record Stream(
        String route,
        long routeId,
        Map<Long, RouteStops> stopsByVersion,
        AlightingAwareRouteStatistics statistics,
        List<Long> batches
    ) {
        RouteStops stops(final long version) {
            return stopsByVersion.get(version);
        }
    }

    static Stream insert(
        JdbcClient jdbc,
        Instant start,
        Duration length,
        Duration interval,
        Instant versionSwitch,
        final long seed
    ) {
        return insert(jdbc, start, length, interval, versionSwitch, seed, null);
    }

    /**
     * 노선 표시 이름을 정해 넣는다. 모델 계수의 노선 이름과 같아야 하는 시험(시간 확장 모델)이 쓴다.
     * 비워 두면 다른 시험과 겹치지 않는 이름을 만든다.
     */
    static Stream insert(
        JdbcClient jdbc,
        Instant start,
        Duration length,
        Duration interval,
        Instant versionSwitch,
        final long seed,
        String displayName
    ) {
        jdbc.sql("DROP INDEX IF EXISTS ux_observation_vehicle_per_batch").update();
        Random random = new Random(seed);
        String token = UUID.randomUUID().toString().substring(0, 8);
        String key = "pipeline-" + token;
        String route = routeName(displayName, key);
        final long routeId = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES(?,'GBIS',?,?,'A','B') RETURNING id
            """).params(key, key, route).query(Long.class).single();
        Map<Long, RouteStops> stops = insertVersions(jdbc, routeId, start, versionSwitch, route);
        Instant end = start.plus(length);
        List<Long> versions = List.copyOf(stops.keySet());
        List<Vehicle> vehicles = vehicles(token, random, start);
        capacities(jdbc, vehicles, start.minusSeconds(86400));
        for (Instant at = start; at.isBefore(end); at = at.plus(interval)) {
            final long version = versionAt(versions, versionSwitch, at);
            batch(jdbc, random, vehicles, version, at.truncatedTo(ChronoUnit.MICROS));
        }
        List<Long> batches = jdbc.sql("""
            SELECT id FROM observation_batch WHERE route_version_id IN (:versions)
            ORDER BY coalesce(input_confirmed_at,response_received_at),id
            """).param("versions", versions).query(Long.class).list();
        return new Stream(route, routeId, stops, statistics(route, random), batches);
    }

    private static String routeName(String displayName, String key) {
        if (displayName == null) {
            return key;
        }
        return displayName;
    }

    /** 판본 전환이 있으면 전환 전·후 두 판본을, 없으면 한 판본을 이 순서로 넣는다. */
    private static Map<Long, RouteStops> insertVersions(
        JdbcClient jdbc,
        final long routeId,
        Instant start,
        Instant versionSwitch,
        String route
    ) {
        Map<Long, RouteStops> stops = new LinkedHashMap<>();
        if (versionSwitch == null) {
            final long version = version(jdbc, routeId, start.minusSeconds(86400), null);
            stops.put(version, stops(jdbc, version, route));
            return stops;
        }
        final long first = version(jdbc, routeId, start.minusSeconds(86400), versionSwitch);
        stops.put(first, stops(jdbc, first, route));
        final long second = version(jdbc, routeId, versionSwitch, null);
        stops.put(second, stops(jdbc, second, route));
        return stops;
    }

    private static long versionAt(List<Long> versions, Instant versionSwitch, Instant at) {
        if (versionSwitch == null || at.isBefore(versionSwitch)) {
            return versions.getFirst();
        }
        return versions.getLast();
    }

    /** 배치 하나를 저장된 줄 전부와 함께 읽는다. 줄은 되감기 원본과 같은 순서(차량 번호, 관측 번호)다. */
    static ForecastBatch read(JdbcClient jdbc, final long batchId) {
        List<ForecastObservationRow> rows = jdbc.sql("""
            SELECT id,vehicle_id,passed_stop_order,stop_order,running_state,remaining_seats,seat_unknown_reason,crowd_level
            FROM vehicle_observation WHERE observation_batch_id=? ORDER BY vehicle_id,id
            """).param(batchId).query((row, index) -> new ForecastObservationRow(row.getLong(1), row.getString(2),
                row.getInt(3), row.getInt(4), row.getInt(5), (Integer) row.getObject(6), row.getString(7),
                row.getInt(8)))
            .list();
        return jdbc.sql("""
            SELECT route_version_id,response_received_at,input_confirmed_at,ingestion_origin,outcome,stored_rows
            FROM observation_batch WHERE id=?
            """).param(batchId).query((row, index) -> new ForecastBatch(batchId, row.getLong(1),
                instant(row.getObject(2, OffsetDateTime.class)), instant(row.getObject(3, OffsetDateTime.class)),
                row.getString(4), row.getString(5), (Integer) row.getObject(6), rows))
            .single();
    }

    private static long version(JdbcClient jdbc, final long routeId, Instant from, Instant to) {
        return jdbc.sql(
            "INSERT INTO route_version(route_id,content_digest,valid_from,valid_to) VALUES(?,?,?,?) RETURNING id")
            .params(routeId, "0".repeat(64), at(from), atOrNull(to)).query(Long.class).single();
    }

    private static RouteStops stops(JdbcClient jdbc, final long version, String route) {
        List<RouteStop> stops = new ArrayList<>();
        for (int order = 1; order <= STOP_COUNT; order++) {
            RouteDirection direction = direction(order);
            final boolean boarding = order != VIA_STOP;
            jdbc.sql("""
                INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
                VALUES(?,?,?,?,?,?)
                """).params(version, order, "s" + order, "정류장" + order, direction.name(), boarding).update();
            stops.add(new RouteStop(version, order, "s" + order, boarding, direction));
        }
        return new RouteStops(version, route, stops, route);
    }

    private static RouteDirection direction(final int order) {
        if (order > TURN) {
            return RouteDirection.DOWN;
        }
        return RouteDirection.UP;
    }

    private static AlightingAwareRouteStatistics statistics(String route, Random random) {
        List<AlightingAwareRouteStatistics.Stop> stops = new ArrayList<>();
        for (int order = 1; order <= STOP_COUNT; order++) {
            stops.add(new AlightingAwareRouteStatistics.Stop(order, "s" + order, "정류장" + order, order > TURN,
                order != VIA_STOP));
        }
        Map<String, Map<String, Double>> tables = new HashMap<>();
        for (String table : List.of("lam", "lam_base", "net_tab", "full_tab", "dep_tab", "stop_net_base")) {
            Map<String, Double> cells = new HashMap<>();
            for (int order = 1; order <= STOP_COUNT; order++) {
                for (String day : List.of("work", "off")) {
                    cells.put(order + "|" + day, round(random.nextDouble() * 2));
                    for (int bin = 0; bin < 48; bin++) {
                        cells.put(order + "|" + day + "|" + bin, round(random.nextDouble() * 4 - 1));
                    }
                }
            }
            tables.put(table, cells);
        }
        Map<String, List<Double>> bands = new HashMap<>();
        for (int order = 1; order <= STOP_COUNT; order++) {
            for (String band : List.of("morning", "evening")) {
                bands.put(order + "|" + band, List.of(round(random.nextDouble() * 20), round(random.nextDouble()),
                    round(random.nextDouble() * 5)));
            }
        }
        return new AlightingAwareRouteStatistics(route, stops, 2, tables, bands, Set.of(), true);
    }

    private static double round(final double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static List<Vehicle> vehicles(String token, Random random, Instant start) {
        List<Vehicle> vehicles = new ArrayList<>();
        for (int index = 1; index <= 9; index++) {
            vehicles.add(new Vehicle(token + "-v" + String.format("%02d", index), 0.4, random, start));
        }
        vehicles.add(new Vehicle(token + "-w01", 0.02, random, start));
        vehicles.add(new Vehicle(token + "-w02", 0.03, random, start));
        vehicles.add(new Vehicle(token + "-w03", 0.004, random, start));
        return vehicles;
    }

    private static void capacities(JdbcClient jdbc, List<Vehicle> vehicles, Instant evidenceAt) {
        for (int index = 0; index < vehicles.size(); index++) {
            if (index == 7) {
                continue;
            }
            final int capacity = capacity(index);
            jdbc.sql("""
                INSERT INTO vehicle_capacity(source_id,vehicle_id,capacity,evidence_at) VALUES(?,?,?,?)
                ON CONFLICT(source_id,vehicle_id) DO UPDATE SET capacity=EXCLUDED.capacity,evidence_at=EXCLUDED.evidence_at
                """).params(SOURCE_ID, vehicles.get(index).id, capacity, at(evidenceAt)).update();
        }
    }

    private static int capacity(final int index) {
        if (index == 8) {
            return 25;
        }
        return 45;
    }

    private static void batch(
        JdbcClient jdbc,
        Random random,
        List<Vehicle> vehicles,
        final long version,
        Instant receivedAt
    ) {
        List<Object[]> rows = observedRows(random, vehicles, receivedAt);
        final double kind = random.nextDouble();
        Instant confirmedAt = receivedAt.plusMillis(1000 + random.nextInt(7000))
            .plusNanos(random.nextInt(1000) * 1000L);
        BatchHeader header = header(random, rows, kind, receivedAt, confirmedAt);
        final long batch = insertBatch(jdbc, version, receivedAt, header, storedRows(header.outcome(), rows));
        if (rows.isEmpty()) {
            return;
        }
        insertObservations(jdbc, batch, version, rows);
    }

    private static List<Object[]> observedRows(Random random, List<Vehicle> vehicles, Instant receivedAt) {
        List<Object[]> rows = new ArrayList<>();
        for (Vehicle vehicle : vehicles) {
            Object[] row = vehicle.observe(receivedAt);
            if (row != null) {
                rows.add(row);
            }
        }
        if (!rows.isEmpty() && random.nextDouble() < 0.015) {
            Object[] duplicate = rows.get(random.nextInt(rows.size())).clone();
            duplicate[4] = 1 + random.nextInt(30);
            duplicate[5] = null;
            rows.add(duplicate);
        }
        if (random.nextDouble() < 0.03) {
            rows.add(new Object[] {null, 3, 4, 1, 12, null, 2});
        }
        return rows;
    }

    /** 배치 종류를 정한다. 빈 배치와 실패 배치는 {@code rows}를 비운다. 늦게 착수한 배치만 착수 시각 난수를 하나 더 뽑는다. */
    private static BatchHeader header(
        Random random,
        List<Object[]> rows,
        final double kind,
        Instant receivedAt,
        Instant confirmedAt
    ) {
        if (kind < 0.02) {
            rows.clear();
            return new BatchHeader("LIVE", "SUCCESS_EMPTY", null, confirmedAt);
        }
        if (kind < 0.04) {
            return new BatchHeader("LIVE", successOutcome(rows), null,
                receivedAt.plusSeconds(91 + random.nextInt(110)));
        }
        if (kind < 0.05) {
            rows.clear();
            return new BatchHeader("LIVE", "FAILED_UPSTREAM", "UPSTREAM_ERROR", confirmedAt);
        }
        if (kind < 0.06) {
            return new BatchHeader("UNVERIFIED", successOutcome(rows), null, confirmedAt);
        }
        return new BatchHeader("LIVE", successOutcome(rows), null, confirmedAt);
    }

    private static String successOutcome(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return "SUCCESS_EMPTY";
        }
        return "SUCCESS_ROWS";
    }

    private static Integer storedRows(String outcome, List<Object[]> rows) {
        if ("FAILED_UPSTREAM".equals(outcome)) {
            return null;
        }
        return rows.size();
    }

    private static long insertBatch(
        JdbcClient jdbc,
        final long version,
        Instant receivedAt,
        BatchHeader header,
        Integer stored
    ) {
        return jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,
                response_received_at,outcome,failure_code,stored_rows,normalization_version,collection_strategy_version,
                ingestion_origin,input_confirmed_at)
            VALUES(?,?,1,?,?,?,?,?,?,'normalization-v1.0.0','adaptive-kst-v1.0.1',?,?) RETURNING id
            """).params(version, at(receivedAt), UUID.randomUUID().toString(), at(receivedAt), at(receivedAt),
                header.outcome(), header.failure(), stored, header.origin(), at(header.confirmedAt()))
            .query(Long.class).single();
    }

    private static void insertObservations(JdbcClient jdbc, final long batch, final long version, List<Object[]> rows) {
        List<Object> values = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            Object[] row = rows.get(index);
            final int stop = (Integer) row[2];
            values.addAll(Arrays.asList(batch, version, index, row[0], row[1], stop, "s" + stop, row[3],
                row[4], row[5], row[6]));
        }
        jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                passed_stop_order,stop_order,stop_id,running_state,remaining_seats,seat_unknown_reason,crowd_level)
            VALUES
            """ + String.join(",", Collections.nCopies(rows.size(), ROW_PLACEHOLDERS)))
            .params(values).update();
    }

    private static Instant instant(OffsetDateTime time) {
        if (time == null) {
            return null;
        }
        return time.toInstant();
    }

    private static OffsetDateTime at(Instant time) {
        return time.atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime atOrNull(Instant time) {
        if (time == null) {
            return null;
        }
        return at(time);
    }

    private record BatchHeader(String origin, String outcome, String failure, Instant confirmedAt) {
    }

    /** 노선을 돌며 정류장을 지나고, 가끔 보이지 않거나 운행을 마치고 쉬는 차량이다. */
    private static final class Vehicle {
        private final String id;
        private final double speed;
        private final Random random;
        private boolean active;
        private Instant resumeAt;
        private Instant hiddenUntil;
        private int passed;
        private int seats;

        private Vehicle(
            String id,
            final double speed,
            Random random,
            Instant start
        ) {
            this.id = id;
            this.speed = speed;
            this.random = random;
            this.active = random.nextDouble() < 0.7;
            this.resumeAt = start.plusSeconds(random.nextInt(1800));
            this.hiddenUntil = start;
            this.passed = random.nextInt(STOP_COUNT);
            this.seats = 5 + random.nextInt(40);
        }

        /** 이번 배치의 줄 {차량, 지난 정류장, 정류장, 운행 상태, 잔여석, 모름 사유, 혼잡도} 또는 관측 없음이다. */
        private Object[] observe(Instant at) {
            if (!active) {
                if (at.isBefore(resumeAt)) {
                    return null;
                }
                active = true;
                passed = restartedPassed();
                seats = 10 + random.nextInt(36);
            }
            if (at.isBefore(hiddenUntil)) {
                return null;
            }
            final double gap = random.nextDouble();
            if (gap < 0.02) {
                hiddenUntil = at.plusSeconds(60 + random.nextInt(90));
                return null;
            }
            if (gap < 0.023) {
                hiddenUntil = at.plusSeconds(1900 + random.nextInt(900));
                return null;
            }
            final double move = random.nextDouble();
            if (move < speed) {
                passed += stopsMoved(move);
                seats = Math.clamp(seats + random.nextInt(9) - 5, 0, 45);
            }
            if (passed > STOP_COUNT) {
                active = false;
                resumeAt = at.plusSeconds(180 + random.nextInt(2400));
                return null;
            }
            if (random.nextDouble() < 0.05) {
                seats = 0;
            }
            final int stop = Math.min(Math.max(passed + 1, 1), STOP_COUNT);
            final int state = runningState(stop);
            final double quality = random.nextDouble();
            Integer remaining = reportedSeats(quality);
            String reason = unknownReason(quality);
            Integer crowd = crowd();
            return new Object[] {id, passed, stop, state, remaining, reason, crowd};
        }

        private int restartedPassed() {
            if (random.nextDouble() < 0.8) {
                return random.nextInt(2);
            }
            return random.nextInt(STOP_COUNT);
        }

        private int stopsMoved(final double move) {
            if (move < speed * 0.1) {
                return 2;
            }
            return 1;
        }

        /** 4%는 모름, 1%는 범위 밖 좌석(75)으로 보고한다. */
        private Integer reportedSeats(final double quality) {
            if (quality < 0.04) {
                return null;
            }
            if (quality < 0.05) {
                return 75;
            }
            return seats;
        }

        private String unknownReason(final double quality) {
            if (quality < 0.04) {
                return "REPORTED_UNKNOWN";
            }
            return null;
        }

        /** 혼잡도를 보고하는 경우에만 값 난수를 하나 더 뽑는다. */
        private Integer crowd() {
            if (random.nextDouble() < 0.2) {
                return null;
            }
            return 1 + random.nextInt(4);
        }

        /** 첫 정류장과 회차 정류장에서는 방향이 정류장 순번대로 정해지도록 운행 상태를 고정한다. */
        private int runningState(final int stop) {
            final double draw = random.nextDouble();
            if (stop == 1) {
                return 2;
            }
            if (stop == TURN) {
                return 1;
            }
            if (draw < 0.3) {
                return 2;
            }
            return 1;
        }
    }
}

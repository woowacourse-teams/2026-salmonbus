package com.gustler.backend.forecasting.infrastructure.pipeline;

import com.gustler.backend.forecasting.api.pipeline.ForecastOutcome;
import com.gustler.backend.forecasting.application.pipeline.ForecastBatch;
import com.gustler.backend.forecasting.application.pipeline.ForecastObservationRow;
import com.gustler.backend.forecasting.application.pipeline.ObservationBatchRead;
import com.gustler.backend.forecasting.application.pipeline.ObservationBatchReader;
import com.gustler.backend.forecasting.application.pipeline.ReplaySource;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 되감기 원본이자 배치 하나를 읽는 원본이다. 착수 시각을 어디서 읽는지에 따라 두 축({@link Axis})이 있다.
 * <ul>
 *   <li>{@link Axis#BATCH_INPUT}: {@code observation_batch.input_confirmed_at}. 옛 경로·그림자(v1)와 같은 시각 축이다.</li>
 *   <li>{@link Axis#RECORDED_RUN}: 본선(v2)의 축이다. 배치마다 예보 실행({@code forecast_run})의 착수 시각이 있으면 그것을, 없으면
 *       {@code input_confirmed_at}을 쓴다. v1에서 v2로 넘어간 직후 되감기 창에는 옛 경로가 처리한 배치({@code input_confirmed_at}만
 *       있음)와 본선이 처리한 배치({@code forecast_run}만 있음)가 섞이는데, 이 규칙으로 두 출처를 빈틈 없이 잇는다. 그림자가 켜져
 *       있던 구간은 그림자가 {@code input_confirmed_at}을 실행 줄의 착수 시각으로 썼으므로 두 값이 같다.</li>
 * </ul>
 * 어느 축이든 확정 조건은 옛 경로의 {@code CONFIRMED_INPUT}과 같다(정상 수집, 줄이 있는 성공, 수신 뒤 90초 안 착수). 그래서
 * "상태에 반영된 배치"의 판정이 두 경로에서 같다. 본선이 90초를 넘겨 {@code LATE}로 남긴 배치는 착수 시각이 90초 밖이라 자연히
 * 빠진다. 실행 결과({@code outcome})로 거르지 않는 것은 옛 경로의 이력이 확정 배치를 결과와 무관하게 모두 담기 때문이다.
 * 줄은 거르지 않고 모두 돌려준다(거르기와 줄 수 검사는 예보 단계가 한다).
 * 판본 호환 검사는 옛 경로의 {@code validateHistoricalVersion}과 같은 기준으로 창 안에 나온 판본만 본다. 행 잠금을 잡지 않는다.
 *
 * <p>배치 하나를 읽는 {@link #readBatch}도 같은 축·같은 열·같은 줄 순서·같은 변환을 쓴다. 이어 온 상태와 되감은 상태가 같은 입력을
 * 받게 하려는 것이다. 본선은 이 값이 비어 있는지로 "아직 어느 경로도 처리하지 않은 배치"를 가린다.
 *
 * <p>{@link Axis#RECORDED_RUN} 축의 되감기는 예보 실행이 입력 부족({@code INPUT_UNAVAILABLE})으로 끝난 배치를 따로 알려 준다
 * ({@link ReplaySource.Read#unforecastRuns}). 그런 배치는 착수 시각이 확정 조건 안이라 이력에 들어가지만, 그 배치가 닫은 통과는
 * 저장된 적이 없다. {@link Axis#BATCH_INPUT} 축은 예보 실행을 읽지 않으므로 알려 주지 않는다.
 */
public final class JdbcReplaySource implements ReplaySource, ObservationBatchReader {
    static final String ROUTE_HISTORY_MISSING = "ALIGHTING_AWARE_ROUTE_HISTORY_MISSING";
    static final String INCOMPATIBLE_VERSION = "ALIGHTING_AWARE_INCOMPATIBLE_HISTORICAL_ROUTE_VERSION:";
    static final String ROW_LIMIT_EXCEEDED = "ALIGHTING_AWARE_HISTORY_ROW_LIMIT_EXCEEDED";
    private static final String READ_VERSIONS = """
        SELECT version.id FROM route_version version JOIN route ON route.id=version.route_id
        WHERE route.display_name=:name ORDER BY version.id
        """;
    private static final String BATCH_COLUMNS = """
        b.id AS batch_id,b.route_version_id,b.response_received_at,b.input_confirmed_at,b.ingestion_origin,
          b.outcome,b.stored_rows,o.id AS observation_id,o.vehicle_id,o.passed_stop_order,o.stop_order,
          o.running_state,o.remaining_seats,o.seat_unknown_reason,o.crowd_level,o.source_row_number
        """;
    private static final String RUN_COLUMNS = ",b.run_outcome,b.run_source_date,b.run_route_id";
    /** 예보 실행의 나눔 날짜는 배치 예약 시각의 한국 날짜다({@code SourceDates}). 기본 키로 찾으려고 날짜를 같이 준다. */
    private static final String RECORDED_BATCHES = """
        (SELECT b.id,b.route_version_id,b.response_received_at,
            coalesce(r.confirmed_at,b.input_confirmed_at) AS input_confirmed_at,b.ingestion_origin,b.outcome,
            b.stored_rows,b.scheduled_at,r.outcome AS run_outcome,r.source_date AS run_source_date,
            r.route_id AS run_route_id
          FROM observation_batch b LEFT JOIN forecast_run r
            ON r.source_date=(b.scheduled_at AT TIME ZONE 'Asia/Seoul')::date AND r.observation_batch_id=b.id)""";
    private static final String READ_BATCHES = """
        SELECT %s
        FROM %s b LEFT JOIN vehicle_observation o ON o.observation_batch_id=b.id
        WHERE b.route_version_id IN (:versions)
          AND b.ingestion_origin='LIVE' AND b.outcome='SUCCESS_ROWS' AND b.stored_rows>0
          AND b.input_confirmed_at>=b.response_received_at
          AND b.input_confirmed_at<=b.response_received_at+interval '90 seconds'
          AND b.input_confirmed_at>=:from AND (b.input_confirmed_at,b.id)<=(:through,:batch)
          AND b.response_received_at<=:through AND b.response_received_at>=:from-interval '90 seconds'
        ORDER BY b.input_confirmed_at,b.id,o.vehicle_id,o.id
        LIMIT :limit
        """;
    private static final String READ_BATCH = """
        SELECT %s,b.scheduled_at,version.route_id,route.source_id
        FROM %s b JOIN route_version version ON version.id=b.route_version_id
          JOIN route ON route.id=version.route_id
          LEFT JOIN vehicle_observation o ON o.observation_batch_id=b.id
        WHERE b.id=:batch
        ORDER BY o.vehicle_id,o.id
        """;
    private static final String READ_STOPS = """
        SELECT stop_order,stop_id,boarding_allowed,direction FROM route_stop WHERE route_version_id=:version ORDER BY stop_order
        """;

    private final JdbcClient jdbc;
    private final int maximumRows;
    private final Axis axis;
    private final String readBatches;
    private final String readBatch;

    /**
     * 옛 경로의 축({@link Axis#BATCH_INPUT})으로 읽는다.
     *
     * @param maximumRows 한 번 되감기에서 읽는 관측 줄의 한도. 옛 경로의 {@code forecast.alighting.maximum-history-rows}와 같은 뜻이다
     */
    public JdbcReplaySource(JdbcClient jdbc, final int maximumRows) {
        this(jdbc, maximumRows, Axis.BATCH_INPUT);
    }

    /**
     * @param maximumRows 한 번 되감기에서 읽는 관측 줄의 한도
     * @param axis 착수 시각을 읽는 축
     */
    public JdbcReplaySource(JdbcClient jdbc, final int maximumRows, Axis axis) {
        if (maximumRows < 1) {
            throw new IllegalArgumentException("되감기 줄 한도는 양수여야 한다");
        }
        this.jdbc = jdbc;
        this.maximumRows = maximumRows;
        this.axis = Objects.requireNonNull(axis);
        String source = batchSource(axis);
        String columns = batchColumns(axis);
        this.readBatches = READ_BATCHES.formatted(columns, source);
        this.readBatch = READ_BATCH.formatted(columns, source);
    }

    private static String batchColumns(Axis axis) {
        if (axis == Axis.BATCH_INPUT) {
            return BATCH_COLUMNS;
        }
        return BATCH_COLUMNS + RUN_COLUMNS;
    }

    private static String batchSource(Axis axis) {
        if (axis == Axis.BATCH_INPUT) {
            return "observation_batch";
        }
        return RECORDED_BATCHES;
    }

    /** 이 원본이 착수 시각을 읽는 축. */
    public Axis axis() {
        return axis;
    }

    @Override
    public Read read(
        AlightingAwareRouteStatistics statistics,
        Instant from,
        Instant through,
        final long throughBatchId
    ) {
        List<Long> versions = jdbc.sql(READ_VERSIONS).param("name", statistics.route()).query(Long.class).list();
        if (versions.isEmpty()) {
            return Read.failed(ROUTE_HISTORY_MISSING);
        }
        List<Row> rows = readRows(versions, from, through, throughBatchId);
        if (rows.size() > maximumRows) {
            return Read.failed(ROW_LIMIT_EXCEEDED);
        }
        Set<Long> seenVersions = rows.stream()
            .map(Row::routeVersionId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        for (final long version : seenVersions) {
            if (!isCompatible(version, statistics)) {
                return Read.failed(INCOMPATIBLE_VERSION + version);
            }
        }
        return Read.of(batches(rows), unforecastRuns(rows));
    }

    private static Map<Long, UnforecastRun> unforecastRuns(List<Row> rows) {
        Map<Long, UnforecastRun> runs = new HashMap<>();
        for (Row row : rows) {
            if (row.unforecastRun() != null) {
                runs.put(row.batchId(), row.unforecastRun());
            }
        }
        return runs;
    }

    private List<Row> readRows(List<Long> versions, Instant from, Instant through, final long throughBatchId) {
        return jdbc.sql(readBatches)
            .param("versions", versions)
            .param("from", from.atOffset(ZoneOffset.UTC))
            .param("through", through.atOffset(ZoneOffset.UTC))
            .param("batch", throughBatchId)
            .param("limit", maximumRows + 1)
            .query((row, index) -> row(row, axis))
            .list();
    }

    @Override
    public Optional<ObservationBatchRead> readBatch(final long observationBatchId) {
        List<Placed> rows = jdbc.sql(readBatch)
            .param("batch", observationBatchId)
            .query((row, index) -> new Placed(row(row, axis),
                row.getObject("scheduled_at", OffsetDateTime.class).toInstant(), row.getLong("route_id"),
                row.getString("source_id")))
            .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Placed head = rows.getFirst();
        Map<Long, Integer> rowNumbers = new HashMap<>();
        rows.forEach(placed -> placed.row().observation().ifPresent(
            observation -> rowNumbers.put(observation.observationId(), placed.row().sourceRowNumber())));
        ForecastBatch batch = batches(rows.stream().map(Placed::row).toList()).getFirst();
        return Optional.of(
            new ObservationBatchRead(batch, head.routeId(), head.scheduledAt(), head.sourceId(), rowNumbers));
    }

    private static List<ForecastBatch> batches(List<Row> rows) {
        Map<Long, List<Row>> byBatch = new LinkedHashMap<>();
        for (Row row : rows) {
            byBatch.computeIfAbsent(row.batchId(), ignored -> new ArrayList<>()).add(row);
        }
        List<ForecastBatch> batches = new ArrayList<>();
        for (List<Row> group : byBatch.values()) {
            Row head = group.getFirst();
            List<ForecastObservationRow> observations = group.stream()
                .map(Row::observation)
                .flatMap(Optional::stream)
                .toList();
            batches.add(new ForecastBatch(head.batchId(), head.routeVersionId(), head.receivedAt(), head.confirmedAt(),
                head.ingestionOrigin(), head.outcome(), head.storedRows(), observations));
        }
        return batches;
    }

    private boolean isCompatible(final long version, AlightingAwareRouteStatistics statistics) {
        List<StopRow> stops = jdbc.sql(READ_STOPS).param("version", version)
            .query((row, index) -> new StopRow(row.getInt(1), row.getString(2), row.getBoolean(3), row.getString(4)))
            .list();
        if (stops.size() != statistics.stops().size()) {
            return false;
        }
        for (StopRow stop : stops) {
            Optional<AlightingAwareRouteStatistics.Stop> reference = statistics.stops().stream()
                .filter(candidate -> candidate.order() == stop.order()).findFirst();
            if (reference.isEmpty() || !stop.matches(reference.get())) {
                return false;
            }
        }
        return true;
    }

    private static Row row(ResultSet row, Axis axis) throws SQLException {
        Long observationId = (Long) row.getObject("observation_id");
        ForecastObservationRow observation = null;
        if (observationId != null) {
            observation = new ForecastObservationRow(observationId, row.getString("vehicle_id"),
                row.getInt("passed_stop_order"), row.getInt("stop_order"), row.getInt("running_state"),
                (Integer) row.getObject("remaining_seats"), row.getString("seat_unknown_reason"),
                row.getInt("crowd_level"));
        }
        return new Row(row.getLong("batch_id"), row.getLong("route_version_id"), instant(row, "response_received_at"),
            instant(row, "input_confirmed_at"), row.getString("ingestion_origin"), row.getString("outcome"),
            (Integer) row.getObject("stored_rows"), observation, row.getInt("source_row_number"),
            unforecastRun(row, axis));
    }

    private static UnforecastRun unforecastRun(ResultSet row, Axis axis) throws SQLException {
        if (axis == Axis.BATCH_INPUT) {
            return null;
        }
        if (!ForecastOutcome.INPUT_UNAVAILABLE.name().equals(row.getString("run_outcome"))) {
            return null;
        }
        return new UnforecastRun(row.getObject("run_source_date", LocalDate.class), row.getLong("run_route_id"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        if (value == null) {
            return null;
        }
        return value.toInstant();
    }

    private record Row(
        long batchId,
        long routeVersionId,
        Instant receivedAt,
        Instant confirmedAt,
        String ingestionOrigin,
        String outcome,
        Integer storedRows,
        ForecastObservationRow row,
        int sourceRowNumber,
        UnforecastRun unforecastRun
    ) {
        private Optional<ForecastObservationRow> observation() {
            return Optional.ofNullable(row);
        }
    }

    private record Placed(Row row, Instant scheduledAt, long routeId, String sourceId) {
    }

    private record StopRow(int order, String stopId, boolean boardingAllowed, String direction) {
        private boolean matches(AlightingAwareRouteStatistics.Stop reference) {
            if (!stopId.equals(reference.id()) || boardingAllowed != reference.boardingAllowed()) {
                return false;
            }
            return direction.equals(directionName(reference.down()));
        }

        private static String directionName(final boolean down) {
            if (down) {
                return "DOWN";
            }
            return "UP";
        }
    }

    /** 착수 시각을 읽는 축. */
    public enum Axis {
        /** {@code observation_batch.input_confirmed_at}. 옛 경로와 그림자의 축이다. */
        BATCH_INPUT,
        /** 예보 실행의 착수 시각, 없으면 {@code input_confirmed_at}. 본선의 축이다. */
        RECORDED_RUN
    }
}

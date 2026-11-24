package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.api.forecast.ForecastOutcome;
import com.gustler.backend.forecasting.application.forecast.ForecastBatch;
import com.gustler.backend.forecasting.application.forecast.ForecastObservationRow;
import com.gustler.backend.forecasting.application.forecast.ObservationBatchRead;
import com.gustler.backend.forecasting.application.forecast.ObservationBatchReader;
import com.gustler.backend.forecasting.application.forecast.ReplaySource;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 복구 원본이자 배치 하나를 읽는 원본이다.
 *
 * <p>예보 실행 기록의 처리 시작 시각으로 확정 관측을 복구한다. 실행 기록이 없는 배치는 아직 처리하지 않은 배치다.
 *
 * <p>확정 조건은 정상 수집, 관측이 있는 성공, 수신 뒤 90초 안 착수다. 90초를 넘겨 {@code LATE}로 남은 배치는 처리 시작 시각이 90초
 * 밖이라 자연히 빠진다. 실행 결과({@code outcome})로 거르지 않는다. 이력은 확정 배치를 결과와 무관하게 모두 담는다.
 * 줄은 제외하지 않고 모두 반환한다(필터링와 행 수 검사는 예보 단계가 한다). 버전 호환 검사는 조회 범위 안에 나온 버전만 본다. 행 잠금을
 * 잡지 않는다.
 *
 * <p>배치 하나를 읽는 {@link #readBatch}도 같은 열, 같은 줄 순서, 같은 변환을 쓴다. 이어 온 상태와 복구한 상태가 같은 입력을 받게
 * 하려는 것이다. 읽은 처리 시작 시각이 비어 있으면 아직 처리하지 않은 배치다.
 *
 * <p>입력 부족으로 끝난 예보 실행도 확정 관측 이력에 들어간다. 그 배치가 확정한 통과는 복구해 호출자에게 반환한다.
 */
public final class JdbcReplaySource implements ReplaySource, ObservationBatchReader {

    /** worker가 한 번 복구에서 읽는 관측 행의 한도. 넘으면 그 노선의 복구가 실패한다. */
    public static final int DEFAULT_MAXIMUM_ROWS = 250_000;
    static final String ROUTE_HISTORY_MISSING = "ALIGHTING_AWARE_ROUTE_HISTORY_MISSING";
    static final String INCOMPATIBLE_VERSION = "ALIGHTING_AWARE_INCOMPATIBLE_HISTORICAL_ROUTE_VERSION:";
    static final String ROW_LIMIT_EXCEEDED = "ALIGHTING_AWARE_HISTORY_ROW_LIMIT_EXCEEDED";
    private static final String READ_VERSIONS = """
        SELECT /* salmonbus:replay.find_versions */ version.id
        FROM route_version version JOIN route ON route.id=version.route_id
        WHERE route.display_name=:name ORDER BY version.id
        """;
    private static final String BATCH_COLUMNS = """
        b.id AS batch_id,b.route_version_id,b.response_received_at,b.confirmed_at,b.ingestion_origin,
          b.outcome,b.stored_rows,o.id AS observation_id,o.vehicle_id,o.passed_stop_order,o.stop_order,
          o.running_state,o.remaining_seats,o.seat_unknown_reason,o.crowd_level,o.source_row_number
        """;
    private static final String RUN_COLUMNS = ",b.run_outcome,b.batch_source_date,b.run_route_id";
    /** 예보 실행의 파티션 기준 날짜는 배치 예약 시각의 한국 날짜다({@code SourceDates}). 기본 키로 찾으려고 날짜를 같이 준다. */
    private static final String RECORDED_BATCHES = """
        (SELECT b.id,b.route_version_id,b.response_received_at,
            r.confirmed_at AS confirmed_at,b.ingestion_origin,b.outcome,
            b.stored_rows,b.scheduled_at,r.outcome AS run_outcome,
            (b.scheduled_at AT TIME ZONE 'Asia/Seoul')::date AS batch_source_date,r.route_id AS run_route_id
          FROM observation_batch b LEFT JOIN forecast_run r
            ON r.source_date=(b.scheduled_at AT TIME ZONE 'Asia/Seoul')::date AND r.observation_batch_id=b.id)""";
    private static final String COLUMNS = BATCH_COLUMNS + RUN_COLUMNS;
    private static final String READ_BATCHES = """
        SELECT /* salmonbus:replay.read_batches */ %s
        FROM %s b LEFT JOIN vehicle_observation o ON o.observation_batch_id=b.id
        WHERE b.route_version_id IN (:versions)
          AND b.ingestion_origin='LIVE' AND b.outcome='SUCCESS_ROWS' AND b.stored_rows>0
          AND b.confirmed_at>=b.response_received_at
          AND b.confirmed_at<=b.response_received_at+interval '90 seconds'
          AND b.confirmed_at>=:from AND (b.confirmed_at,b.id)<=(:through,:batch)
          AND b.response_received_at<=:through AND b.response_received_at>=:from-interval '90 seconds'
        ORDER BY b.confirmed_at,b.id,o.vehicle_id,o.id
        LIMIT :limit
        """.formatted(COLUMNS, RECORDED_BATCHES);
    private static final String READ_BATCH = """
        SELECT /* salmonbus:forecast.read_batch */ %s,b.scheduled_at,version.route_id,route.source_id
        FROM %s b JOIN route_version version ON version.id=b.route_version_id
          JOIN route ON route.id=version.route_id
          LEFT JOIN vehicle_observation o ON o.observation_batch_id=b.id
        WHERE b.id=:batch
        ORDER BY o.vehicle_id,o.id
        """.formatted(COLUMNS, RECORDED_BATCHES);
    private static final String READ_STOPS = """
        SELECT /* salmonbus:replay.read_stops */ stop_order,stop_id,boarding_allowed,direction
        FROM route_stop WHERE route_version_id=:version ORDER BY stop_order
        """;

    private final JdbcClient jdbc;
    private final int maximumRows;

    /**
     * @param maximumRows 한 번 복구에서 읽는 관측 행의 한도
     */
    public JdbcReplaySource(JdbcClient jdbc, final int maximumRows) {
        if (maximumRows < 1) {
            throw new IllegalArgumentException("복구할 관측의 최대 행 수는 양수여야 한다");
        }
        this.jdbc = jdbc;
        this.maximumRows = maximumRows;
    }

    @Override
    public ReplaySource.Read read(
        AlightingAwareRouteStatistics statistics,
        Instant from,
        Instant through,
        final long throughBatchId
    ) {
        List<Long> versions = jdbc.sql(READ_VERSIONS)
            .param("name", statistics.route())
            .query(Long.class)
            .list();
        if (versions.isEmpty()) {
            return ReplaySource.Read.failed(ROUTE_HISTORY_MISSING);
        }
        List<Row> rows = readRows(
                versions,
                from,
                through,
                throughBatchId
        );
        if (rows.size() > maximumRows) {
            return ReplaySource.Read.failed(ROW_LIMIT_EXCEEDED);
        }
        Set<Long> seenVersions = rows.stream()
            .map(Row::routeVersionId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        for (final long version : seenVersions) {
            if (!isCompatible(version, statistics)) {
                return ReplaySource.Read.failed(INCOMPATIBLE_VERSION + version);
            }
        }
        return ReplaySource.Read.of(batches(rows), unforecastRuns(rows));
    }

    private static Map<Long, ReplaySource.UnforecastRun> unforecastRuns(List<Row> rows) {
        return rows.stream()
            .filter(row -> row.unforecastRun() != null)
            .collect(Collectors.toMap(
                Row::batchId,
                Row::unforecastRun,
                (earlier, later) -> later
            ));
    }

    private List<Row> readRows(
        List<Long> versions,
        Instant from,
        Instant through,
        final long throughBatchId
    ) {
        return jdbc.sql(READ_BATCHES)
            .param("versions", versions)
            .param("from", from.atOffset(ZoneOffset.UTC))
            .param("through", through.atOffset(ZoneOffset.UTC))
            .param("batch", throughBatchId)
            .param("limit", maximumRows + 1)
            .query((row, index) -> row(row))
            .list();
    }

    @Override
    public Optional<ObservationBatchRead> readBatch(final long observationBatchId) {
        List<Placed> rows = jdbc.sql(READ_BATCH)
            .param("batch", observationBatchId)
            .query((row, index) -> new Placed(
                    row(row),
                    row.getObject("scheduled_at", OffsetDateTime.class).toInstant(),
                    row.getLong("route_id"),
                    row.getString("source_id")
            ))
            .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Placed batchDetails = rows.getFirst();
        List<Row> batchRows = rows.stream().map(Placed::row).toList();
        Map<Long, Integer> sourceRowNumbers = batchRows.stream()
            .filter(row -> row.observation() != null)
            .collect(Collectors.toMap(
                row -> row.observation().observationId(),
                Row::sourceRowNumber,
                (earlier, later) -> later
            ));

        return Optional.of(new ObservationBatchRead(
                forecastBatch(batchRows),
                batchDetails.routeId(),
                batchDetails.scheduledAt(),
                batchDetails.sourceId(),
                sourceRowNumbers
        ));
    }

    private static List<ForecastBatch> batches(List<Row> rows) {
        Map<Long, List<Row>> rowsByBatch = rows.stream()
            .collect(Collectors.groupingBy(Row::batchId, LinkedHashMap::new, Collectors.toList()));
        return rowsByBatch.values().stream().map(JdbcReplaySource::forecastBatch).toList();
    }

    private static ForecastBatch forecastBatch(List<Row> batchRows) {
        Row first = batchRows.getFirst();
        List<ForecastObservationRow> observations = batchRows.stream()
            .map(Row::observation)
            .filter(Objects::nonNull)
            .toList();
        return new ForecastBatch(
                first.batchId(),
                first.routeVersionId(),
                first.receivedAt(),
                first.confirmedAt(),
                first.ingestionOrigin(),
                first.outcome(),
                first.storedRows(),
                observations
        );
    }

    private boolean isCompatible(final long version, AlightingAwareRouteStatistics statistics) {
        List<StopRow> stops = jdbc.sql(READ_STOPS).param("version", version)
            .query((row, index) -> new StopRow(
                    row.getInt(1),
                    row.getString(2),
                    row.getBoolean(3),
                    row.getString(4)
            ))
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

    private static Row row(ResultSet row) throws SQLException {
        Long observationId = (Long) row.getObject("observation_id");
        ForecastObservationRow observation = null;
        if (observationId != null) {
            observation = new ForecastObservationRow(
                    observationId,
                    row.getString("vehicle_id"),
                    row.getInt("passed_stop_order"),
                    row.getInt("stop_order"),
                    row.getInt("running_state"),
                    (Integer) row.getObject("remaining_seats"),
                    row.getString("seat_unknown_reason"),
                    row.getInt("crowd_level")
            );
        }
        return new Row(
                row.getLong("batch_id"),
                row.getLong("route_version_id"),
                instant(row, "response_received_at"),
                instant(row, "confirmed_at"),
                row.getString("ingestion_origin"),
                row.getString("outcome"),
                (Integer) row.getObject("stored_rows"),
                observation,
                row.getInt("source_row_number"),
                unforecastRun(row)
        );
    }

    private static ReplaySource.UnforecastRun unforecastRun(ResultSet row) throws SQLException {
        if (!ForecastOutcome.INPUT_UNAVAILABLE.name().equals(row.getString("run_outcome"))) {
            return null;
        }
        return new ReplaySource.UnforecastRun(
            row.getObject("batch_source_date", LocalDate.class), row.getLong("run_route_id"));
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
        ForecastObservationRow observation,
        int sourceRowNumber,
        ReplaySource.UnforecastRun unforecastRun
    ) {

    }

    private record Placed(
        Row row,
        Instant scheduledAt,
        long routeId,
        String sourceId
    ) {

    }

    private record StopRow(
        int order,
        String stopId,
        boolean boardingAllowed,
        String direction
    ) {

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
}

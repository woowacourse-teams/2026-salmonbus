package com.gustler.backend.forecasting.infrastructure.pipeline;

import com.gustler.backend.forecasting.application.pipeline.ForecastBatch;
import com.gustler.backend.forecasting.application.pipeline.ForecastObservationRow;
import com.gustler.backend.forecasting.application.pipeline.ReplaySource;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 착수 시각을 {@code observation_batch.input_confirmed_at}에서 읽는 되감기 원본이다. 옛 경로·그림자(v1)와 같은 시각 축이다.
 * 확정 조건은 옛 경로의 {@code CONFIRMED_INPUT}과 같고, 줄은 거르지 않고 모두 돌려준다(거르기와 줄 수 검사는 예보 단계가 한다).
 * 판본 호환 검사는 옛 경로의 {@code validateHistoricalVersion}과 같은 기준으로 창 안에 나온 판본만 본다. 행 잠금을 잡지 않는다.
 */
public final class JdbcReplaySource implements ReplaySource {
    static final String ROUTE_HISTORY_MISSING = "ALIGHTING_AWARE_ROUTE_HISTORY_MISSING";
    static final String INCOMPATIBLE_VERSION = "ALIGHTING_AWARE_INCOMPATIBLE_HISTORICAL_ROUTE_VERSION:";
    static final String ROW_LIMIT_EXCEEDED = "ALIGHTING_AWARE_HISTORY_ROW_LIMIT_EXCEEDED";
    private static final String READ_VERSIONS = """
        SELECT version.id FROM route_version version JOIN route ON route.id=version.route_id
        WHERE route.display_name=:name ORDER BY version.id
        """;
    private static final String READ_BATCHES = """
        SELECT b.id AS batch_id,b.route_version_id,b.response_received_at,b.input_confirmed_at,b.ingestion_origin,
          b.outcome,b.stored_rows,o.id AS observation_id,o.vehicle_id,o.passed_stop_order,o.stop_order,
          o.running_state,o.remaining_seats,o.seat_unknown_reason,o.crowd_level
        FROM observation_batch b LEFT JOIN vehicle_observation o ON o.observation_batch_id=b.id
        WHERE b.route_version_id IN (:versions)
          AND b.ingestion_origin='LIVE' AND b.outcome='SUCCESS_ROWS' AND b.stored_rows>0
          AND b.input_confirmed_at>=b.response_received_at
          AND b.input_confirmed_at<=b.response_received_at+interval '90 seconds'
          AND b.input_confirmed_at>=:from AND (b.input_confirmed_at,b.id)<=(:through,:batch)
          AND b.response_received_at<=:through AND b.response_received_at>=:from-interval '90 seconds'
        ORDER BY b.input_confirmed_at,b.id,o.vehicle_id,o.id
        LIMIT :limit
        """;
    private static final String READ_STOPS = """
        SELECT stop_order,stop_id,boarding_allowed,direction FROM route_stop WHERE route_version_id=:version ORDER BY stop_order
        """;

    private final JdbcClient jdbc;
    private final int maximumRows;

    /** {@code maximumRows}는 한 번 되감기에서 읽는 관측 줄의 한도다. 옛 경로의 {@code forecast.alighting.maximum-history-rows}와 같은 뜻이다. */
    public JdbcReplaySource(JdbcClient jdbc, final int maximumRows) {
        if (maximumRows < 1) {
            throw new IllegalArgumentException("되감기 줄 한도는 양수여야 한다");
        }
        this.jdbc = jdbc;
        this.maximumRows = maximumRows;
    }

    @Override
    public Read read(AlightingAwareRouteStatistics statistics, Instant from, Instant through, final long throughBatchId) {
        List<Long> versions = jdbc.sql(READ_VERSIONS).param("name", statistics.route()).query(Long.class).list();
        if (versions.isEmpty()) {
            return Read.failed(ROUTE_HISTORY_MISSING);
        }
        List<Row> rows = jdbc.sql(READ_BATCHES)
            .param("versions", versions)
            .param("from", from.atOffset(ZoneOffset.UTC))
            .param("through", through.atOffset(ZoneOffset.UTC))
            .param("batch", throughBatchId)
            .param("limit", maximumRows + 1)
            .query((row, index) -> row(row))
            .list();
        if (rows.size() > maximumRows) {
            return Read.failed(ROW_LIMIT_EXCEEDED);
        }
        Set<Long> seenVersions = new LinkedHashSet<>();
        rows.forEach(row -> seenVersions.add(row.routeVersionId()));
        for (final long version : seenVersions) {
            if (!isCompatible(version, statistics)) {
                return Read.failed(INCOMPATIBLE_VERSION + version);
            }
        }
        return Read.of(batches(rows));
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

    private static Row row(ResultSet row) throws SQLException {
        Long observationId = (Long) row.getObject("observation_id");
        ForecastObservationRow observation = null;
        if (observationId != null) {
            observation = new ForecastObservationRow(observationId, row.getString("vehicle_id"),
                row.getInt("passed_stop_order"), row.getInt("stop_order"), row.getInt("running_state"),
                (Integer) row.getObject("remaining_seats"), row.getString("seat_unknown_reason"), row.getInt("crowd_level"));
        }
        return new Row(row.getLong("batch_id"), row.getLong("route_version_id"),
            row.getObject("response_received_at", OffsetDateTime.class).toInstant(),
            row.getObject("input_confirmed_at", OffsetDateTime.class).toInstant(), row.getString("ingestion_origin"),
            row.getString("outcome"), (Integer) row.getObject("stored_rows"), observation);
    }

    private record Row(
        long batchId,
        long routeVersionId,
        Instant receivedAt,
        Instant confirmedAt,
        String ingestionOrigin,
        String outcome,
        Integer storedRows,
        ForecastObservationRow row
    ) {
        private Optional<ForecastObservationRow> observation() {
            return Optional.ofNullable(row);
        }
    }

    private record StopRow(int order, String stopId, boolean boardingAllowed, String direction) {
        private boolean matches(AlightingAwareRouteStatistics.Stop reference) {
            if (!stopId.equals(reference.id()) || boardingAllowed != reference.boardingAllowed()) {
                return false;
            }
            return direction.equals(reference.down() ? "DOWN" : "UP");
        }
    }
}

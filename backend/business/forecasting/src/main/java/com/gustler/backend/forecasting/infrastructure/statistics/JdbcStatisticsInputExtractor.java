package com.gustler.backend.forecasting.infrastructure.statistics;

import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 소량 비교 자료 추출용. 한 실행 범위를 같은 스냅샷에서 읽으며 운영 이력 삭제에는 사용하지 않는다. */
final class JdbcStatisticsInputExtractor {
    private static final int MAX_ROWS = 10_000;
    private static final long MAX_ID_SPAN = 10_000;
    private static final String SQL = """
        WITH candidates AS MATERIALIZED (
            SELECT e.* FROM forecast_evaluation_result e
            WHERE e.route_version_id=:route AND e.vehicle_observation_id>:after
              AND e.vehicle_observation_id<=:through AND e.scored_at<=:until
            ORDER BY e.vehicle_observation_id,e.target_stop_order LIMIT :limit
        )
        SELECT e.vehicle_observation_id,e.target_stop_order,e.scoring_state,
            e.arrival_observation_id,e.seats_on_arrival,e.arrived_at,e.scored_at,
            f.stops_to_target,s.boarding_allowed,
            source.id AS source_id,source.route_version_id AS source_route,
            source.vehicle_id AS source_vehicle,source.quality_direction AS source_direction,
            source.remaining_seats AS source_seats,source.forecast_eligible AS source_usable,
            arrival.id AS arrival_id,arrival.route_version_id AS arrival_route,
            arrival.vehicle_id AS arrival_vehicle,arrival.quality_direction AS arrival_direction,
            arrival.remaining_seats AS arrival_seats,arrival.forecast_eligible AS arrival_usable
        FROM candidates e
        LEFT JOIN seat_forecast f ON f.vehicle_observation_id=e.vehicle_observation_id
            AND f.target_stop_order=e.target_stop_order
        LEFT JOIN route_stop s ON s.route_version_id=e.route_version_id AND s.stop_order=e.target_stop_order
        LEFT JOIN forecast_observation_quality source ON source.id=e.vehicle_observation_id
        LEFT JOIN forecast_observation_quality arrival ON arrival.id=e.arrival_observation_id
        ORDER BY e.vehicle_observation_id,e.target_stop_order
        """;

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    JdbcStatisticsInputExtractor(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(5);
    }

    Extracted extract(long route, long after, long through, Instant until, ZoneId zone, int maxRows) {
        if (route <= 0 || after < 0 || through <= after || through - after > MAX_ID_SPAN
            || until == null || zone == null || maxRows < 1 || maxRows > MAX_ROWS) {
            throw new IllegalArgumentException("추출 노선·관측 범위·시각·건수 한도가 올바르지 않다");
        }
        return transaction.execute(status -> {
            jdbc.sql("SET LOCAL statement_timeout = '2s'").update();
            jdbc.sql("SET LOCAL lock_timeout = '500ms'").update();
            long revision = jdbc.sql("""
                SELECT q.quality_revision FROM route_data_quality q
                JOIN route_version v ON v.route_id=q.route_id WHERE v.id=:route
                """).param("route", route).query(Long.class).single();
            boolean rebuilding = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM trip_quality_rebuild
                    WHERE route_version_id=:route AND NOT completed)
                """).param("route", route).query(Boolean.class).single();
            if (rebuilding) {
                throw new IllegalStateException("품질 조사 중인 노선은 추출하지 않는다");
            }
            List<StatisticsInputRow> rows = jdbc.sql(SQL).param("route", route).param("after", after)
                .param("through", through).param("until", OffsetDateTime.ofInstant(until, ZoneOffset.UTC))
                .param("limit", maxRows + 1).query((rs, index) -> row(rs, revision)).list();
            if (rows.size() > maxRows) {
                throw new IllegalArgumentException("추출 건수 한도를 넘었다. 관측 ID 범위를 줄여야 한다");
            }
            var scope = new StatisticsInputScope(StatisticsInputScope.SCHEMA_VERSION,
                DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, route, revision, until, zone, rows.size());
            StatisticsInputRows.verify(scope, rows.iterator());
            return new Extracted(scope, after, through, rows);
        });
    }

    private StatisticsInputRow row(ResultSet rs, long revision) throws SQLException {
        Integer distance = rs.getObject("stops_to_target", Integer.class);
        Boolean boarding = rs.getObject("boarding_allowed", Boolean.class);
        if (distance == null || boarding == null) {
            throw new IllegalArgumentException("정산에 연결된 예보 또는 정류장이 없다");
        }
        return new StatisticsInputRow(rs.getLong("vehicle_observation_id"), rs.getInt("target_stop_order"),
            distance, boarding, ScoringState.valueOf(rs.getString("scoring_state")),
            rs.getObject("arrival_observation_id", Long.class), rs.getObject("seats_on_arrival", Integer.class),
            instant(rs, "arrived_at"), instant(rs, "scored_at"), revision,
            observation(rs, "source"), observation(rs, "arrival"));
    }

    private StatisticsInputRow.Observation observation(ResultSet rs, String prefix) throws SQLException {
        Long id = rs.getObject(prefix + "_id", Long.class);
        return id == null ? null : new StatisticsInputRow.Observation(id, rs.getLong(prefix + "_route"),
            rs.getString(prefix + "_vehicle"), rs.getLong(prefix + "_direction"),
            rs.getObject(prefix + "_seats", Integer.class), rs.getBoolean(prefix + "_usable"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    record Extracted(StatisticsInputScope scope, long afterObservationId, long throughObservationId,
        List<StatisticsInputRow> rows) {
        Extracted { rows = List.copyOf(rows); }
    }
}

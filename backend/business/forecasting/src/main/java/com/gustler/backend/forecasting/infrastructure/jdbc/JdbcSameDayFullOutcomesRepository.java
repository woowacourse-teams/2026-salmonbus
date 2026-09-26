package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.SameDayFullOutcomeCount;
import com.gustler.backend.forecasting.domain.evaluation.SameDayFullOutcomesRepository;
import com.gustler.backend.forecasting.domain.evaluation.SeoulDay;
import com.gustler.backend.forecasting.domain.evaluation.SettledForecast;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSameDayFullOutcomesRepository implements SameDayFullOutcomesRepository {

    private static final String SELECT_COUNTS = """
        SELECT stops_to_target, row_count, actual_full_count, raw_full_chance_sum, settled_through
        FROM same_day_full_outcomes
        WHERE route_id = :routeId
          AND outcome_date = :outcomeDate
          AND quality_revision = (SELECT quality_revision FROM route_data_quality WHERE route_id = :routeId)
        ORDER BY stops_to_target
        """;

    private static final String UPSERT_COUNT = """
        INSERT INTO same_day_full_outcomes (
            route_id, outcome_date, stops_to_target,
            row_count, actual_full_count, raw_full_chance_sum, settled_through, quality_revision
        ) VALUES (
            :routeId, :outcomeDate, :stopsToTarget,
            :rowCount, :actualFullCount, :rawFullChanceSum, :settledThrough,
            (SELECT quality_revision FROM route_data_quality WHERE route_id = :routeId)
        )
        ON CONFLICT (route_id, outcome_date, stops_to_target) DO UPDATE SET
            row_count = EXCLUDED.row_count,
            actual_full_count = EXCLUDED.actual_full_count,
            raw_full_chance_sum = EXCLUDED.raw_full_chance_sum,
            settled_through = EXCLUDED.settled_through,
            quality_revision = EXCLUDED.quality_revision
        """;

    private static final String ADD_TO_COUNT = """
        INSERT INTO same_day_full_outcomes (
            route_id, outcome_date, stops_to_target,
            row_count, actual_full_count, raw_full_chance_sum, settled_through, quality_revision
        ) VALUES (
            :routeId, :outcomeDate, :stopsToTarget, 1, :fullCount, :rawFullChance, :arrivedAt,
            (SELECT quality_revision FROM route_data_quality WHERE route_id = :routeId)
        )
        ON CONFLICT (route_id, outcome_date, stops_to_target) DO UPDATE SET
            row_count = CASE WHEN same_day_full_outcomes.quality_revision = EXCLUDED.quality_revision
                THEN same_day_full_outcomes.row_count + 1 ELSE 1 END,
            actual_full_count = CASE WHEN same_day_full_outcomes.quality_revision = EXCLUDED.quality_revision
                THEN same_day_full_outcomes.actual_full_count + EXCLUDED.actual_full_count
                ELSE EXCLUDED.actual_full_count END,
            raw_full_chance_sum = CASE WHEN same_day_full_outcomes.quality_revision = EXCLUDED.quality_revision
                THEN same_day_full_outcomes.raw_full_chance_sum + EXCLUDED.raw_full_chance_sum
                ELSE EXCLUDED.raw_full_chance_sum END,
            settled_through = CASE WHEN same_day_full_outcomes.quality_revision = EXCLUDED.quality_revision
                THEN GREATEST(same_day_full_outcomes.settled_through, EXCLUDED.settled_through)
                ELSE EXCLUDED.settled_through END,
            quality_revision = EXCLUDED.quality_revision
        """;

    /**
     * 평가에 보관한 실제 도착 시각으로 당일 결과를 집계한다.
     *
     * <p>현재 품질 조건을 통과하고 발행 당시 품질 버전도 일치하는 결과만 사용한다.
     * 같은 노선의 이전 노선 버전도 포함하며, 지정한 기준 시각과 같은 순간의 도착은 포함한다.
     */
    private static final String COUNT_FROM_SOURCE = """
        SELECT forecast.stops_to_target,
               count(*) AS row_count,
               count(*) FILTER (WHERE evaluation.seats_on_arrival = 0) AS actual_full_count,
               sum(forecast.seat_full_chance_raw) AS raw_full_chance_sum,
               max(evaluation.arrived_at) AS settled_through
        FROM quality_calibration_seat_forecast forecast
        JOIN forecast_evaluation evaluation
          ON evaluation.vehicle_observation_id = forecast.vehicle_observation_id
         AND evaluation.target_stop_order = forecast.target_stop_order
        JOIN route_version version ON version.id = evaluation.route_version_id
        WHERE version.route_id = :routeId
          AND evaluation.arrived_at >= :dayStart
          AND evaluation.arrived_at < :dayEnd
          AND evaluation.arrived_at <= :until
          AND evaluation.scoring_state = 'SETTLED'
          AND evaluation.seats_on_arrival IS NOT NULL
        GROUP BY forecast.stops_to_target
        ORDER BY forecast.stops_to_target
        """;

    private final JdbcClient jdbcClient;
    private final RouteDataQualityAccess qualityAccess;

    public JdbcSameDayFullOutcomesRepository(JdbcClient jdbcClient, RouteDataQualityAccess qualityAccess) {
        this.jdbcClient = jdbcClient;
        this.qualityAccess = qualityAccess;
    }

    @Override
    public void lockRoute(final long routeId) {
        qualityAccess.lockByRoute(routeId);
    }

    @Override
    public List<SameDayFullOutcomeCount> findCounts(
        final long routeId,
        SeoulDay day
    ) {
        return jdbcClient.sql(SELECT_COUNTS)
            .param("routeId", routeId)
            .param("outcomeDate", day.date())
            .query(JdbcSameDayFullOutcomesRepository::countOf)
            .list();
    }

    @Override
    public void upsertCounts(
        final long routeId,
        SeoulDay day,
        List<SameDayFullOutcomeCount> counts
    ) {
        for (SameDayFullOutcomeCount count : counts) {
            jdbcClient.sql(UPSERT_COUNT)
                .param("routeId", routeId)
                .param("outcomeDate", day.date())
                .param("stopsToTarget", count.stopsToTarget())
                .param("rowCount", count.rowCount())
                .param("actualFullCount", count.actualFullCount())
                .param("rawFullChanceSum", count.rawFullChanceSum())
                .param("settledThrough", offsetOf(count.settledThrough()))
                .update();
        }
    }

    @Override
    public void add(
        SettledForecast settled
    ) {
        jdbcClient.sql(ADD_TO_COUNT)
            .param("routeId", settled.routeId())
            .param("outcomeDate", SeoulDay.containing(settled.arrivedAt()).date())
            .param("stopsToTarget", settled.stopsToTarget())
            .param("fullCount", settled.wasFull() ? 1 : 0)
            .param("rawFullChance", settled.rawFullChance())
            .param("arrivedAt", offsetOf(settled.arrivedAt()))
            .update();
    }

    @Override
    public List<SameDayFullOutcomeCount> countFromSource(
        final long routeId,
        SeoulDay day,
        Instant until
    ) {
        return jdbcClient.sql(COUNT_FROM_SOURCE)
            .param("routeId", routeId)
            .param("dayStart", offsetOf(day.start()))
            .param("dayEnd", offsetOf(day.end()))
            .param("until", offsetOf(until))
            .query(JdbcSameDayFullOutcomesRepository::countOf)
            .list();
    }

    private static SameDayFullOutcomeCount countOf(
        ResultSet resultSet,
        final int rowNumber
    ) throws SQLException {
        return new SameDayFullOutcomeCount(
            resultSet.getInt("stops_to_target"),
            resultSet.getInt("row_count"),
            resultSet.getInt("actual_full_count"),
            resultSet.getDouble("raw_full_chance_sum"),
            instantOf(resultSet.getObject("settled_through", OffsetDateTime.class)));
    }

    private static Instant instantOf(
        OffsetDateTime timestamp
    ) {
        return timestamp.toInstant();
    }

    private static OffsetDateTime offsetOf(
        Instant timestamp
    ) {
        return timestamp.atOffset(ZoneOffset.UTC);
    }
}

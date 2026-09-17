package com.gustler.backend.processor.persistence.jdbc;

import com.gustler.backend.processor.SameDayFullOutcomeCount;
import com.gustler.backend.processor.SameDayFullOutcomesRepository;
import com.gustler.backend.processor.SeoulDay;
import com.gustler.backend.processor.SettledForecast;
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
        ORDER BY stops_to_target
        """;

    private static final String REPLACE_COUNT = """
        INSERT INTO same_day_full_outcomes (
            route_id, outcome_date, stops_to_target,
            row_count, actual_full_count, raw_full_chance_sum, settled_through
        ) VALUES (
            :routeId, :outcomeDate, :stopsToTarget,
            :rowCount, :actualFullCount, :rawFullChanceSum, :settledThrough
        )
        ON CONFLICT (route_id, outcome_date, stops_to_target) DO UPDATE SET
            row_count = EXCLUDED.row_count,
            actual_full_count = EXCLUDED.actual_full_count,
            raw_full_chance_sum = EXCLUDED.raw_full_chance_sum,
            settled_through = EXCLUDED.settled_through
        """;

    private static final String SELECT_ARRIVAL_OF_SETTLED = """
        SELECT forecast_version.route_id, arrival_batch.response_received_at
        FROM vehicle_observation arrival
        JOIN observation_batch arrival_batch
          ON arrival_batch.id = arrival.observation_batch_id
        CROSS JOIN route_version forecast_version
        WHERE arrival.id = :arrivalObservationId
          AND forecast_version.id = :routeVersionId
        """;

    private static final String ADD_TO_COUNT = """
        INSERT INTO same_day_full_outcomes (
            route_id, outcome_date, stops_to_target,
            row_count, actual_full_count, raw_full_chance_sum, settled_through
        ) VALUES (
            :routeId, :outcomeDate, :stopsToTarget, 1, :fullCount, :rawFullChance, :arrivedAt
        )
        ON CONFLICT (route_id, outcome_date, stops_to_target) DO UPDATE SET
            row_count = same_day_full_outcomes.row_count + 1,
            actual_full_count = same_day_full_outcomes.actual_full_count + EXCLUDED.actual_full_count,
            raw_full_chance_sum = same_day_full_outcomes.raw_full_chance_sum + EXCLUDED.raw_full_chance_sum,
            settled_through = GREATEST(same_day_full_outcomes.settled_through, EXCLUDED.settled_through)
        """;

    /**
     * 오늘 도착이 확인된 예보들의 성적을 원본에서 센다. 예보 거리마다 한 줄이다.
     *
     * <p>예보 시각과 같은 순간에 도착한 것까지 센다. 그 순간에 이미 확정된 과거 사건이라
     * 미래를 보고 답하는 것이 아니다.
     *
     * <p><b>노선 판본이 아니라 노선으로 묶는다.</b> 노선이 개편되면 판본이 갈리는데, 판본으로 묶으면
     * 개편된 날 성적이 0건에서 다시 시작한다. 만석이 얼마나 나는지는 개편과 상관없이 이어진다.
     * 판본을 도착 batch 쪽에서 고르는 것은 V1 의 ix_batch_recent_history 첫 열이 판본이라서다.
     * 회수가 도착 후보를 예보와 같은 판본에서만 찾으니 예보 쪽 판본으로 골라도 같은 행이 나온다.
     *
     * <p>날짜를 {@code ::date} 로 비교하지 않고 자정 경계 두 개로 자른다. 열에 함수를 씌우면
     * 그 인덱스를 못 타고 판 표를 통째로 읽는다.
     */
    private static final String COUNT_FROM_SOURCE = """
        SELECT forecast.stops_to_target,
               count(*)                                              AS row_count,
               count(*) FILTER (WHERE forecast.seats_on_arrival = 0)  AS actual_full_count,
               sum(forecast.seat_full_chance_raw)                     AS raw_full_chance_sum,
               max(arrival_batch.response_received_at)                AS settled_through
        FROM observation_batch arrival_batch
        JOIN vehicle_observation arrival
          ON arrival.observation_batch_id = arrival_batch.id
        JOIN seat_forecast forecast
          ON forecast.arrival_observation_id = arrival.id
        WHERE arrival_batch.route_version_id IN (
                SELECT id FROM route_version WHERE route_id = :routeId)
          AND arrival_batch.response_received_at >= :dayStart
          AND arrival_batch.response_received_at < :dayEnd
          AND arrival_batch.response_received_at <= :until
          AND forecast.scoring_state = 'SETTLED'
          AND forecast.seats_on_arrival IS NOT NULL
        GROUP BY forecast.stops_to_target
        ORDER BY forecast.stops_to_target
        """;

    private final JdbcClient jdbcClient;

    public JdbcSameDayFullOutcomesRepository(
        JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
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
    public void replaceCounts(
        final long routeId,
        SeoulDay day,
        List<SameDayFullOutcomeCount> counts
    ) {
        for (SameDayFullOutcomeCount count : counts) {
            jdbcClient.sql(REPLACE_COUNT)
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
        Arrival arrival = jdbcClient.sql(SELECT_ARRIVAL_OF_SETTLED)
            .param("arrivalObservationId", settled.arrivalObservationId())
            .param("routeVersionId", settled.routeVersionId())
            .query((resultSet, rowNumber) -> new Arrival(
                resultSet.getLong("route_id"),
                instantOf(resultSet.getObject("response_received_at", OffsetDateTime.class))))
            .single();
        jdbcClient.sql(ADD_TO_COUNT)
            .param("routeId", arrival.routeId())
            .param("outcomeDate", SeoulDay.containing(arrival.arrivedAt()).date())
            .param("stopsToTarget", settled.stopsToTarget())
            .param("fullCount", settled.wasFull() ? 1 : 0)
            .param("rawFullChance", settled.rawFullChance())
            .param("arrivedAt", offsetOf(arrival.arrivedAt()))
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

    private record Arrival(
        long routeId,
        Instant arrivedAt
    ) {
    }
}

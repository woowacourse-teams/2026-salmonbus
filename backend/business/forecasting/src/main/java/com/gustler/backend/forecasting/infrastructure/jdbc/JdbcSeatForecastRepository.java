package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel;
import com.gustler.backend.forecasting.domain.evaluation.ForecastSettlement;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import com.gustler.backend.forecasting.domain.publication.SeatForecastRepository;
import com.gustler.backend.forecasting.domain.evaluation.SettledForecast;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예보 표를 SQL 로 직접 읽고 쓴다. JPA 엔티티를 두지 않는다.
 *
 * <p>같은 표를 매핑한 엔티티가 다른 패키지에 이미 있어서 엔티티를 하나 더 두면
 * Hibernate 가 기동할 때 매핑이 겹쳤다고 막는다.
 *
 * <p>예보 완료 표시는 collector 가 소유한 observation_batch 에 있지만 여기서 직접 쓴다.
 * collector 코드를 부르면 패키지 경계 검사가 막는다.
 */
@Repository
public class JdbcSeatForecastRepository implements SeatForecastRepository {

    /**
     * 갓 낸 예보의 회수 상태. 라벨은 나중에 회수 배치가 같은 행에 채운다.
     *
     * <p>V8 의 ck_forecast_scored_at_presence 가 이 상태의 행에만 회수 시각이 비어 있기를 요구한다.
     */
    private static final String NOT_SETTLED_YET = ScoringState.PENDING.name();

    /**
     * 예보 한 줄. 같은 (관측, 대상 순번) 이 이미 있으면 예보 값만 덮어쓴다.
     *
     * <p>재시도가 같은 판을 두 번 열어도 행이 하나만 남는다.
     *
     * <p>덮어쓸 때 회수 결과 세 열은 건드리지 않는다. 같은 관측이 같은 정류장에 닿았을 때
     * 본 것은 예보를 다시 계산해도 안 바뀌고, 회수 상태만 되돌리면 V8 의 짝 검사에 걸린다.
     */
    private static final String UPSERT_FORECAST = """
        INSERT INTO seat_forecast (
            vehicle_observation_id, target_stop_order, route_version_id, stops_to_target,
            model_deployment_id, demand_statistics_revision,
            seat_full_chance_raw, seat_full_chance, expected_seats, generated_at, scoring_state, quality_revision
        ) VALUES (
            :vehicleObservationId, :targetStopOrder, :routeVersionId, :stopsToTarget,
            :modelDeploymentId, :demandStatisticsRevision,
            :seatFullChanceRaw, :seatFullChance, :expectedSeats, :generatedAt, :scoringState,
            (SELECT q.quality_revision FROM route_version v
             JOIN route q ON q.id = v.route_id WHERE v.id = :routeVersionId)
        )
        ON CONFLICT (vehicle_observation_id, target_stop_order) DO UPDATE SET
            route_version_id = EXCLUDED.route_version_id,
            stops_to_target = EXCLUDED.stops_to_target,
            model_deployment_id = EXCLUDED.model_deployment_id,
            demand_statistics_revision = EXCLUDED.demand_statistics_revision,
            seat_full_chance_raw = EXCLUDED.seat_full_chance_raw,
            seat_full_chance = EXCLUDED.seat_full_chance,
            expected_seats = EXCLUDED.expected_seats,
            generated_at = EXCLUDED.generated_at,
            quality_revision = EXCLUDED.quality_revision
        """;

    /** 그 판의 예보를 다 썼다는 표시. 차가 0대라 예보 행이 하나도 없어도 찍는다. */
    private static final String MARK_FORECAST_COMPLETED = """
        UPDATE observation_batch
        SET forecast_completed_at = :completedAt
        WHERE id = :observationBatchId
        """;

    /**
     * 아직 라벨을 못 채운 예보를 오래된 것부터.
     *
     * <p>WHERE 의 scoring_state 조건은 V8 의 ix_forecast_awaiting_label 부분 조건과 글자가 같다.
     * 글자가 어긋나면 계획기가 그 인덱스를 안 고르고 예보 표를 통째로 훑는다.
     *
     * <p>관측 시각은 vehicle_observation 에서 안 읽는다. observation_batch.response_received_at 이
     * 관측 시각의 권위라서 판을 조인해 채운다. 예보는 응답을 받은 판에만 붙어서 그 시각이 비지 않는다.
     */
    private static final String SELECT_FORECASTS_AWAITING_LABEL = """
        SELECT forecast.vehicle_observation_id,
               forecast.target_stop_order,
               forecast.route_version_id,
               observation.vehicle_id,
               forecast.stops_to_target,
               batch.response_received_at,
               forecast.generated_at,
               observation.quality_direction
        FROM quality_eligible_seat_forecast forecast
        JOIN forecast_eligible_observation observation
          ON observation.id = forecast.vehicle_observation_id
        JOIN observation_batch batch
          ON batch.id = observation.observation_batch_id
        WHERE forecast.scoring_state = 'PENDING'
          AND forecast.route_version_id = :routeVersionId
        ORDER BY forecast.generated_at
        LIMIT :limit
        """;

    /**
     * 안 닫힌 예보가 남은 노선 판본. 부분 인덱스의 조건과 글자가 같아야 그것을 고른다.
     *
     * <p>노선당 차량이 백 대 안쪽이라 DISTINCT 결과가 몇 줄에서 끝난다.
     */
    private static final String SELECT_ROUTE_VERSIONS_AWAITING_LABEL = """
        SELECT DISTINCT route_version_id
        FROM quality_eligible_seat_forecast
        WHERE scoring_state = 'PENDING'
        """;

    /** 회수한 라벨을 예보 행에 채운다. 라벨 세 열과 회수 상태를 한 번에 써야 V8 의 짝 검사를 통과한다. */
    private static final String SETTLE_FORECAST = """
        UPDATE seat_forecast
        SET scoring_state = :scoringState,
            arrival_observation_id = :arrivalObservationId,
            seats_on_arrival = :seatsOnArrival,
            scored_at = :scoredAt
        WHERE vehicle_observation_id = :vehicleObservationId
          AND target_stop_order = :targetStopOrder
          AND scoring_state = 'PENDING'
          AND EXISTS (SELECT 1 FROM forecast_eligible_observation source
                      WHERE source.id = :vehicleObservationId
                        AND (CAST(:arrivalObservationId AS bigint) IS NULL OR EXISTS (
                            SELECT 1 FROM forecast_eligible_observation arrival
                            WHERE arrival.id = :arrivalObservationId
                              AND arrival.quality_direction = source.quality_direction
                              AND arrival.vehicle_id IS NOT DISTINCT FROM source.vehicle_id
                              AND arrival.route_version_id = source.route_version_id)))
        """;

    private static final String SETTLE_PENDING_FORECAST = """
        UPDATE seat_forecast forecast
        SET scoring_state = :scoringState,
            arrival_observation_id = :arrivalObservationId,
            seats_on_arrival = :seatsOnArrival,
            scored_at = :scoredAt
        FROM route_version forecast_version,
             vehicle_observation arrival
             JOIN observation_batch arrival_batch
               ON arrival_batch.id = arrival.observation_batch_id
        WHERE forecast.vehicle_observation_id = :vehicleObservationId
          AND forecast.target_stop_order = :targetStopOrder
          AND forecast.scoring_state = 'PENDING'
          AND forecast_version.id = forecast.route_version_id
          AND arrival.id = :arrivalObservationId
          AND EXISTS (SELECT 1 FROM forecast_eligible_observation source
                      JOIN forecast_eligible_observation eligible_arrival
                        ON eligible_arrival.quality_direction = source.quality_direction
                       AND eligible_arrival.vehicle_id IS NOT DISTINCT FROM source.vehicle_id
                       AND eligible_arrival.route_version_id = source.route_version_id
                      WHERE source.id = :vehicleObservationId AND eligible_arrival.id = :arrivalObservationId)
        RETURNING forecast_version.route_id, forecast.stops_to_target,
                  forecast.seat_full_chance_raw, arrival_batch.response_received_at,
                  forecast.quality_revision = (SELECT quality_revision FROM route
                      WHERE id = forecast_version.route_id) AS usable_for_calibration
        """;

    private final JdbcClient jdbcClient;

    public JdbcSeatForecastRepository(
        JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 한 판의 예보를 한 줄씩 쓴다.
     *
     * <p>JdbcClient 에는 묶음 쓰기가 없어서 줄마다 한 번씩 부른다. 한 판이 낼 수 있는 줄은
     * 차량 수에 12정류장을 곱한 만큼이라 실측 수십 줄이다.
     */
    @Override
    public void save(
        List<SeatForecast> forecasts
    ) {
        for (SeatForecast forecast : forecasts) {
            jdbcClient.sql(UPSERT_FORECAST)
                .param("vehicleObservationId", forecast.vehicleObservationId())
                .param("targetStopOrder", forecast.targetStopOrder())
                .param("routeVersionId", forecast.routeVersionId())
                .param("stopsToTarget", forecast.stopsToTarget())
                .param("modelDeploymentId", forecast.modelDeploymentId())
                .param("demandStatisticsRevision", forecast.demandStatisticsRevision())
                .param("seatFullChanceRaw", forecast.seatFullChanceRaw())
                .param("seatFullChance", forecast.seatFullChance())
                .param("expectedSeats", forecast.expectedSeats())
                .param("generatedAt", offsetOf(forecast.generatedAt()))
                .param("scoringState", NOT_SETTLED_YET)
                .update();
        }
    }

    @Override
    public void markForecastCompleted(
        final long observationBatchId,
        Instant completedAt
    ) {
        jdbcClient.sql(MARK_FORECAST_COMPLETED)
            .param("completedAt", offsetOf(completedAt))
            .param("observationBatchId", observationBatchId)
            .update();
    }

    @Override
    public List<PendingForecast> findPending(
        final long routeVersionId,
        final int limit
    ) {
        return jdbcClient.sql(SELECT_FORECASTS_AWAITING_LABEL)
            .param("routeVersionId", routeVersionId)
            .param("limit", limit)
            .query((resultSet, rowNumber) -> new PendingForecast(
                resultSet.getLong("vehicle_observation_id"),
                resultSet.getInt("target_stop_order"),
                resultSet.getLong("route_version_id"),
                resultSet.getString("vehicle_id"),
                resultSet.getInt("stops_to_target"),
                instantOf(resultSet.getObject("response_received_at", OffsetDateTime.class)),
                instantOf(resultSet.getObject("generated_at", OffsetDateTime.class)),
                resultSet.getObject("quality_direction", Long.class)))
            .list();
    }

    @Override
    public List<Long> findRouteVersionIdsWithPendingForecasts() {
        return jdbcClient.sql(SELECT_ROUTE_VERSIONS_AWAITING_LABEL)
            .query(Long.class)
            .list();
    }

    /** 호출한 회수 작업의 transaction은 당일 집계 갱신까지 이어져야 한다. */
    @Override
    @Transactional
    public List<SettledForecast> settle(
        List<ForecastSettlement> settlements
    ) {
        if (settlements.isEmpty()) {
            return List.of();
        }
        // 품질 변경/예보 생성과 같은 노선 잠금을 공유한다. 여러 노선은 id 순서로 잠근다.
        jdbcClient.sql("""
            SELECT id FROM route WHERE id IN (
                SELECT v.route_id FROM vehicle_observation o
                JOIN route_version v ON v.id = o.route_version_id WHERE o.id IN (:sourceIds))
            ORDER BY id FOR UPDATE
            """).param("sourceIds", settlements.stream().map(ForecastSettlement::vehicleObservationId).distinct().toList())
            .query(Long.class).list();
        List<SettledForecast> settled = new ArrayList<>();
        for (ForecastSettlement settlement : settlements) {
            if (settlement.label() instanceof ArrivalLabel.Settled label) {
                settlePending(settlement, label).ifPresent(settled::add);
                continue;
            }
            ArrivalColumns arrival = arrivalColumnsOf(settlement.label());
            jdbcClient.sql(SETTLE_FORECAST)
                .param("scoringState", settlement.label().scoringState().name())
                .param("arrivalObservationId", arrival.arrivalObservationId())
                .param("seatsOnArrival", arrival.seatsOnArrival())
                .param("scoredAt", offsetOf(settlement.scoredAt()))
                .param("vehicleObservationId", settlement.vehicleObservationId())
                .param("targetStopOrder", settlement.targetStopOrder())
                .update();
        }
        return List.copyOf(settled);
    }

    /** 이전 품질 버전의 실제 도착 라벨도 보존하되 후보정에 더할 결과에서는 뺀다. */
    private Optional<SettledForecast> settlePending(
        ForecastSettlement settlement,
        ArrivalLabel.Settled label
    ) {
        return jdbcClient.sql(SETTLE_PENDING_FORECAST)
            .param("scoringState", ScoringState.SETTLED.name())
            .param("arrivalObservationId", label.arrivalObservationId())
            .param("seatsOnArrival", label.seatsOnArrival())
            .param("scoredAt", offsetOf(settlement.scoredAt()))
            .param("vehicleObservationId", settlement.vehicleObservationId())
            .param("targetStopOrder", settlement.targetStopOrder())
            .query((resultSet, rowNumber) -> resultSet.getBoolean("usable_for_calibration") ? new SettledForecast(
                resultSet.getLong("route_id"),
                resultSet.getInt("stops_to_target"),
                resultSet.getDouble("seat_full_chance_raw"),
                resultSet.getObject("response_received_at", OffsetDateTime.class).toInstant(),
                label.seatsOnArrival()) : null)
            .list().stream().filter(Objects::nonNull).findFirst();
    }

    /**
     * 라벨 갈래마다 도착 관측 id 와 도착 잔여석에 무엇을 쓸지 정한다.
     *
     * <p>봉인 인터페이스를 switch 로 받아서 갈래가 늘면 컴파일러가 여기를 짚는다.
     * 아직 안 닿은 갈래는 ForecastSettlement 생성자가 막아서 여기까지 못 온다.
     */
    private static ArrivalColumns arrivalColumnsOf(
        ArrivalLabel label
    ) {
        return switch (label) {
            case ArrivalLabel.Settled settled ->
                new ArrivalColumns(settled.arrivalObservationId(), settled.seatsOnArrival());
            case ArrivalLabel.SeatMissing seatMissing ->
                new ArrivalColumns(seatMissing.arrivalObservationId(), null);
            case ArrivalLabel.Skipped skipped -> new ArrivalColumns(null, null);
            case ArrivalLabel.Lost lost -> new ArrivalColumns(null, null);
            case ArrivalLabel.NotArrivedYet notArrivedYet ->
                throw new IllegalArgumentException("아직 대상 정류장에 안 닿은 예보는 열어 둔다");
        };
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

    /** 회수 결과가 채우는 두 열. 둘 다 비는 갈래가 있어서 값이 없는 상태를 담는다. */
    private record ArrivalColumns(
        Long arrivalObservationId,
        Integer seatsOnArrival
    ) {
    }

}

package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationDiagnostics;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluationRepository;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.evaluation.SettledEvaluation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.StringJoiner;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** 평가 상태와 당시 도착 관측의 근거 값을 예측과 별도로 저장한다. */
@Repository
public class JdbcForecastEvaluationRepository implements ForecastEvaluationRepository {

    private static final String INSERT_PENDING = """
        INSERT /* salmonbus:forecast_evaluation.insert_pending */ INTO forecast_evaluation_pending (vehicle_observation_id, target_stop_order, route_version_id)
        SELECT :observation, :stop, :version
        WHERE NOT EXISTS (
            SELECT 1 FROM forecast_evaluation_result
            WHERE vehicle_observation_id = :observation AND target_stop_order = :stop)
          AND NOT EXISTS (
            SELECT 1 FROM evaluation_archive_member
            WHERE vehicle_observation_id = :observation AND target_stop_order = :stop)
        ON CONFLICT (vehicle_observation_id, target_stop_order) DO NOTHING
        """;

    // 대상 키는 한 번만 읽고, 같은 관측을 쓰는 여러 정류장 예보의 품질 조회를 공유한다.
    // 품질을 통과하지 못한 키도 반환해야 다음 페이지 이동과 확정 제외 처리를 유지할 수 있다.
    private static final String SELECT_PENDING_PAGE = """
        WITH /* salmonbus:forecast_evaluation.select_pending_page */ pending AS MATERIALIZED (
            SELECT vehicle_observation_id, target_stop_order
            FROM forecast_evaluation_pending
            WHERE route_version_id = :routeVersionId
              AND (vehicle_observation_id, target_stop_order) > (:afterObservationId, :afterStopOrder)
            ORDER BY vehicle_observation_id, target_stop_order
            LIMIT :limit
        ), source_ids AS MATERIALIZED (
            SELECT DISTINCT vehicle_observation_id AS id FROM pending
        ), sources AS MATERIALIZED (
            SELECT source.id, source.vehicle_id, source.quality_direction, batch.response_received_at
            FROM source_ids keys
            JOIN forecast_eligible_observation source ON source.id = keys.id
            JOIN observation_batch batch ON batch.id = source.observation_batch_id
        ), eligible_forecasts AS MATERIALIZED (
            SELECT evaluation.vehicle_observation_id, evaluation.target_stop_order,
                   forecast.route_version_id, source.vehicle_id, forecast.stops_to_target,
                   source.response_received_at, forecast.generated_at, source.quality_direction
            FROM pending evaluation
            JOIN sources source ON source.id = evaluation.vehicle_observation_id
            JOIN seat_forecast forecast ON forecast.vehicle_observation_id = evaluation.vehicle_observation_id
             AND forecast.target_stop_order = evaluation.target_stop_order
        )
        SELECT evaluation.vehicle_observation_id, evaluation.target_stop_order,
               detail.route_version_id, detail.vehicle_id, detail.stops_to_target,
               detail.response_received_at, detail.generated_at, detail.quality_direction,
               detail.vehicle_observation_id IS NOT NULL AS eligible
        FROM pending evaluation
        LEFT JOIN eligible_forecasts detail ON detail.vehicle_observation_id = evaluation.vehicle_observation_id
         AND detail.target_stop_order = evaluation.target_stop_order
        ORDER BY evaluation.vehicle_observation_id, evaluation.target_stop_order
        """;

    private static final int SETTLEMENT_BATCH_SIZE = 100;

    /** 품질을 재확인한 대기 행만 제거하고, 같은 SQL에서 완료 근거를 보관한다. */
    private static final String COMPLETE_BATCH = """
        WITH /* salmonbus:forecast_evaluation.complete_batch */ input(vehicleObservationId, targetStopOrder, arrivalObservationId, scoringState, seatsOnArrival, scoredAt)
            AS MATERIALIZED (VALUES %s),
        eligible AS MATERIALIZED (
            SELECT observation.* FROM forecast_eligible_observation observation
            JOIN (
                SELECT vehicleObservationId AS id FROM input
                UNION
                SELECT arrivalObservationId FROM input WHERE arrivalObservationId IS NOT NULL
            ) keys ON keys.id = observation.id
        ), candidates AS MATERIALIZED (
            SELECT pending.vehicle_observation_id, pending.target_stop_order, pending.route_version_id,
                   input.scoringState AS scoring_state, input.arrivalObservationId AS arrival_observation_id,
                   input.seatsOnArrival AS seats_on_arrival, input.scoredAt AS scored_at,
                   arrival_batch.response_received_at AS arrived_at,
                   arrival.route_version_id AS arrival_route_version_id,
                   arrival.vehicle_id AS arrival_vehicle_id, arrival.stop_order AS arrival_stop_order,
                   arrival.running_state AS arrival_running_state, arrival.remaining_seats AS arrival_remaining_seats,
                   arrival.seat_unknown_reason AS arrival_seat_unknown_reason,
                   arrival.vehicle_trip_key AS arrival_vehicle_trip_key,
                   arrival.quality_direction AS arrival_quality_direction,
                   version.route_id, forecast.model_deployment_id, forecast.stops_to_target,
                   forecast.seat_full_chance_raw,
                   forecast.quality_revision = quality.quality_revision AS usable_for_calibration,
                   source.vehicle_id AS prediction_vehicle_id, source.remaining_seats AS prediction_remaining_seats,
                   COALESCE(target_stop.boarding_allowed, false) AS target_boarding_allowed,
                   route_info.display_name AS route_name, target_stop.name AS stop_name,
                   target_stop.stop_id, target_stop.direction, forecast.expected_seats, forecast.seat_full_chance
            FROM input
            JOIN forecast_evaluation_pending pending ON pending.vehicle_observation_id = input.vehicleObservationId
             AND pending.target_stop_order = input.targetStopOrder
            JOIN seat_forecast forecast ON forecast.vehicle_observation_id = input.vehicleObservationId
             AND forecast.target_stop_order = input.targetStopOrder
            JOIN route_version version ON version.id = forecast.route_version_id
            JOIN route route_info ON route_info.id = version.route_id
            LEFT JOIN route_stop target_stop ON target_stop.route_version_id = forecast.route_version_id
             AND target_stop.stop_order = forecast.target_stop_order
            JOIN route_data_quality quality ON quality.route_id = version.route_id
            JOIN eligible source ON source.id = forecast.vehicle_observation_id
            LEFT JOIN eligible arrival ON arrival.id = input.arrivalObservationId
            LEFT JOIN observation_batch arrival_batch ON arrival_batch.id = arrival.observation_batch_id
            WHERE CAST(input.arrivalObservationId AS bigint) IS NULL OR (arrival.id IS NOT NULL AND %s)
        ), removed AS (
            DELETE FROM forecast_evaluation_pending pending USING candidates candidate
            WHERE pending.vehicle_observation_id = candidate.vehicle_observation_id
              AND pending.target_stop_order = candidate.target_stop_order
            RETURNING pending.vehicle_observation_id, pending.target_stop_order
        ), saved AS (
            INSERT INTO forecast_evaluation_result (
                vehicle_observation_id, target_stop_order, route_version_id, scoring_state,
                arrival_observation_id, seats_on_arrival, scored_at, arrived_at, arrival_route_version_id,
                arrival_vehicle_id, arrival_stop_order, arrival_running_state, arrival_remaining_seats,
                arrival_seat_unknown_reason, arrival_vehicle_trip_key, arrival_quality_direction)
            SELECT candidate.vehicle_observation_id, candidate.target_stop_order, candidate.route_version_id,
                   candidate.scoring_state, candidate.arrival_observation_id, candidate.seats_on_arrival,
                   candidate.scored_at, candidate.arrived_at, candidate.arrival_route_version_id,
                   candidate.arrival_vehicle_id, candidate.arrival_stop_order, candidate.arrival_running_state,
                   candidate.arrival_remaining_seats, candidate.arrival_seat_unknown_reason,
                   candidate.arrival_vehicle_trip_key, candidate.arrival_quality_direction
            FROM candidates candidate JOIN removed USING (vehicle_observation_id, target_stop_order)
            RETURNING vehicle_observation_id, target_stop_order
        )
        SELECT candidate.* FROM candidates candidate JOIN saved USING (vehicle_observation_id, target_stop_order)
        """;

    private static final int EXCLUSION_LIMIT = 500;

    // 조사 중의 임시 편도 배정은 종료 근거로 쓰지 않는다. 완료된 차량 조사와 확정 제외가 모두 필요하다.
    private static final String CONFIRMED_EXCLUSION = """
        trip.status = 'EXCLUDED'
        AND EXISTS (SELECT 1 FROM trip_quality_rebuild completed
            WHERE completed.route_version_id = source.route_version_id
              AND completed.vehicle_id = source.vehicle_id AND completed.completed AND completed.phase = 'DONE')
        AND NOT EXISTS (SELECT 1 FROM trip_quality_rebuild pending
            WHERE pending.route_version_id = source.route_version_id AND NOT pending.completed
              AND (pending.vehicle_id = '' OR pending.vehicle_id = source.vehicle_id))
        """;

    private static final String EXCLUSION_SOURCE = """
        JOIN vehicle_observation source ON source.id = keys.observation_id
        LEFT JOIN observation_trip_assignment assignment ON assignment.observation_id = source.id
        JOIN vehicle_one_way_trip trip ON trip.id = COALESCE(assignment.trip_id, source.vehicle_trip_key)
        """;

    private final JdbcClient jdbcClient;

    public JdbcForecastEvaluationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<Long> findRouteVersionIdsWithPendingForecasts() {
        return jdbcClient.sql("""
            SELECT version.id
            FROM route_version version
            WHERE EXISTS (
                SELECT 1 FROM forecast_evaluation_pending evaluation
                WHERE evaluation.route_version_id = version.id
            )
            ORDER BY version.route_id, version.id
            """).query(Long.class).list();
    }

    @Override
    public List<Long> findRouteIdsForObservations(List<Long> observationIds) {
        if (observationIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
            SELECT DISTINCT version.route_id FROM vehicle_observation source
            JOIN route_version version ON version.id = source.route_version_id
            WHERE source.id IN (:sourceIds) ORDER BY version.route_id
            """).param("sourceIds", observationIds).query(Long.class).list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PendingForecast> findPending(final long routeVersionId, final int limit) {
        List<PendingForecast> pending = new ArrayList<>();
        PendingKey after = PendingKey.BEFORE_FIRST;
        int exclusionBudget = EXCLUSION_LIMIT;
        while (pending.size() < limit) {
            PendingPage page = findPendingPage(routeVersionId, after, limit);
            List<PendingKey> keys = page.keys();
            if (keys.isEmpty()) {
                break;
            }
            PendingKey last = keys.getLast();
            List<PendingForecast> eligible = page.eligible();
            if (exclusionBudget > 0 && eligible.size() < keys.size()) {
                exclusionBudget -= closeConfirmedExclusions(routeVersionId, keys, eligible, exclusionBudget);
            }
            pending.addAll(eligible.subList(0, Math.min(eligible.size(), limit - pending.size())));
            after = last;
        }
        return pending;
    }

    private int closeConfirmedExclusions(long version, List<PendingKey> keys,
        List<PendingForecast> eligible, int budget) {
        Set<Long> eligibleIds = eligible.stream().map(PendingForecast::vehicleObservationId).collect(Collectors.toSet());
        List<Long> absentIds = keys.stream().map(PendingKey::vehicleObservationId)
            .filter(id -> !eligibleIds.contains(id)).distinct().toList();
        if (absentIds.isEmpty()) { return 0; }
        List<Long> confirmed = jdbcClient.sql("""
            SELECT source.id FROM vehicle_observation source
            LEFT JOIN observation_trip_assignment assignment ON assignment.observation_id = source.id
            JOIN vehicle_one_way_trip trip ON trip.id = COALESCE(assignment.trip_id, source.vehicle_trip_key)
            WHERE source.id IN (:ids) AND source.route_version_id = :version AND
            """ + CONFIRMED_EXCLUSION)
            .param("ids", absentIds).param("version", version).query(Long.class).list();
        if (confirmed.isEmpty()) { return 0; }

        // 뒤의 Writer도 노선 ID 오름차순으로 잠근다. 앞선 정상 노선은 아직 잠그지 않았을 수 있어
        // 이 노선까지의 낮은 ID를 먼저 잠가 후속 Writer와 잠금 순서가 뒤집히지 않게 한다.
        jdbcClient.sql("""
            SELECT id FROM route
            WHERE id <= (SELECT route_id FROM route_version WHERE id = ?)
            ORDER BY id FOR UPDATE
            """).param(version).query(Long.class).list();
        Set<Long> excludedIds = Set.copyOf(confirmed);
        List<PendingKey> closing = keys.stream().filter(key -> excludedIds.contains(key.vehicleObservationId()))
            .limit(budget).toList();
        StringJoiner values = new StringJoiner(", ");
        for (int index = 0; index < closing.size(); index++) {
            values.add("(CAST(:observation%d AS bigint), CAST(:stop%d AS integer))".formatted(index, index));
        }
        // 잠금을 얻은 뒤 최신 조사/편도 상태를 다시 확인한다. PENDING 조건으로 재시도도 중복 갱신하지 않는다.
        JdbcClient.StatementSpec query = jdbcClient.sql("""
            WITH keys(observation_id, target_stop_order) AS MATERIALIZED (VALUES %s)
            , removed AS (
                DELETE FROM forecast_evaluation_pending evaluation
                USING keys
                %s
                WHERE evaluation.vehicle_observation_id = keys.observation_id
                  AND evaluation.target_stop_order = keys.target_stop_order
                  AND evaluation.route_version_id = :version AND source.route_version_id = :version AND %s
                RETURNING evaluation.vehicle_observation_id, evaluation.target_stop_order, evaluation.route_version_id
            )
            INSERT INTO forecast_evaluation_result (
                vehicle_observation_id, target_stop_order, route_version_id, scoring_state, scored_at)
            SELECT vehicle_observation_id, target_stop_order, route_version_id, 'QUALITY_EXCLUDED', CURRENT_TIMESTAMP
            FROM removed
            """.formatted(values, EXCLUSION_SOURCE, CONFIRMED_EXCLUSION)).param("version", version);
        for (int index = 0; index < closing.size(); index++) {
            query = query.param("observation" + index, closing.get(index).vehicleObservationId())
                .param("stop" + index, closing.get(index).targetStopOrder());
        }
        query.update();
        return closing.size();
    }

    private PendingPage findPendingPage(final long routeVersionId, PendingKey after, final int limit) {
        return jdbcClient.sql(SELECT_PENDING_PAGE)
            .param("routeVersionId", routeVersionId)
            .param("afterObservationId", after.vehicleObservationId())
            .param("afterStopOrder", after.targetStopOrder())
            .param("limit", limit)
            .query(rows -> {
                List<PendingKey> keys = new ArrayList<>();
                List<PendingForecast> eligible = new ArrayList<>();
                while (rows.next()) {
                    long observationId = rows.getLong("vehicle_observation_id");
                    int stopOrder = rows.getInt("target_stop_order");
                    keys.add(new PendingKey(observationId, stopOrder));
                    if (rows.getBoolean("eligible")) {
                        eligible.add(new PendingForecast(
                            observationId, stopOrder, rows.getLong("route_version_id"),
                            rows.getString("vehicle_id"), rows.getInt("stops_to_target"),
                            rows.getObject("response_received_at", OffsetDateTime.class).toInstant(),
                            rows.getObject("generated_at", OffsetDateTime.class).toInstant(),
                            rows.getObject("quality_direction", Long.class)));
                    }
                }
                return new PendingPage(keys, eligible);
            });
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void addPending(final long routeVersionId, List<ForecastEvaluation> evaluations) {
        for (ForecastEvaluation evaluation : evaluations) {
            jdbcClient.sql(INSERT_PENDING)
                .param("observation", evaluation.vehicleObservationId())
                .param("stop", evaluation.targetStopOrder()).param("version", routeVersionId)
                .update();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SettledEvaluation> settle(List<ForecastEvaluation> evaluations) {
        if (evaluations.stream().anyMatch(evaluation -> evaluation.state() == ScoringState.PENDING)) {
            throw new IllegalArgumentException("완료된 평가만 저장할 수 있다");
        }
        Map<PendingKey, Integer> positions = new HashMap<>();
        for (int index = 0; index < evaluations.size(); index++) {
            ForecastEvaluation evaluation = evaluations.get(index);
            PendingKey key = new PendingKey(evaluation.vehicleObservationId(), evaluation.targetStopOrder());
            if (positions.put(key, index) != null) {
                // 첫 입력이 품질 조건에 탈락하면 다음 입력이 저장될 수 있어 순차 의미를 보존한다.
                return settleIndividually(evaluations);
            }
        }
        List<SettledEvaluation> settled = new ArrayList<>();
        for (int start = 0; start < evaluations.size(); start += SETTLEMENT_BATCH_SIZE) {
            List<ForecastEvaluation> batch = evaluations.subList(start,
                Math.min(start + SETTLEMENT_BATCH_SIZE, evaluations.size()));
            settled.addAll(settleBatch(batch));
        }
        settled.sort(Comparator.comparingInt(result -> positions.get(
            new PendingKey(result.vehicleObservationId(), result.targetStopOrder()))));
        return List.copyOf(settled);
    }

    private List<SettledEvaluation> settleBatch(List<ForecastEvaluation> batch) {
        StringJoiner values = new StringJoiner(", ");
        for (int index = 0; index < batch.size(); index++) {
            values.add(("(CAST(:source%d AS bigint), CAST(:target%d AS integer), CAST(:arrival%d AS bigint), "
                + "CAST(:state%d AS varchar), CAST(:seats%d AS integer), CAST(:at%d AS timestamptz))")
                .formatted(index, index, index, index, index, index));
        }
        JdbcClient.StatementSpec query = jdbcClient.sql(COMPLETE_BATCH.formatted(
            values, EligibleObservationSql.ARRIVAL_MATCHES_SOURCE));
        for (int index = 0; index < batch.size(); index++) {
            ForecastEvaluation evaluation = batch.get(index);
            query = query.param("source" + index, evaluation.vehicleObservationId())
                .param("target" + index, evaluation.targetStopOrder())
                .param("arrival" + index, evaluation.result().arrivalObservationId())
                .param("state" + index, evaluation.state().name())
                .param("seats" + index, evaluation.result().seatsOnArrival())
                .param("at" + index, offsetOf(evaluation.scoredAt()));
        }
        return query.query(JdbcForecastEvaluationRepository::settledEvaluationOf).list();
    }

    private List<SettledEvaluation> settleIndividually(List<ForecastEvaluation> evaluations) {
        if (evaluations.isEmpty()) {
            return List.of();
        }
        List<SettledEvaluation> newlySettled = new ArrayList<>();
        for (ForecastEvaluation evaluation : evaluations) {
            if (evaluation.state() == ScoringState.PENDING) {
                throw new IllegalArgumentException("완료된 평가만 저장할 수 있다");
            }
            newlySettled.addAll(settleBatch(List.of(evaluation)));
        }
        return List.copyOf(newlySettled);
    }

    private static SettledEvaluation settledEvaluationOf(ResultSet row, int index) throws SQLException {
        return new SettledEvaluation(row.getLong("route_id"), row.getLong("model_deployment_id"), row.getLong("route_version_id"),
                    row.getLong("vehicle_observation_id"), row.getInt("target_stop_order"),
                    row.getInt("stops_to_target"), row.getDouble("seat_full_chance_raw"),
                    ScoringState.valueOf(row.getString("scoring_state")),
                    row.getObject("arrival_observation_id", Long.class), row.getObject("seats_on_arrival", Integer.class),
                    instantOrNull(row.getObject("arrived_at", OffsetDateTime.class)),
                    row.getObject("scored_at", OffsetDateTime.class).toInstant(),
                    row.getBoolean("usable_for_calibration"), row.getString("prediction_vehicle_id"),
                    row.getObject("prediction_remaining_seats", Integer.class),
                    row.getBoolean("target_boarding_allowed"),
                    new EvaluationDiagnostics(row.getString("route_name"), row.getString("stop_name"),
                        row.getString("stop_id"), row.getString("direction"), row.getLong("model_deployment_id"),
                        row.getObject("expected_seats", Double.class), row.getDouble("seat_full_chance")));
    }

    private static Instant instantOrNull(OffsetDateTime timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static OffsetDateTime offsetOf(Instant timestamp) {
        return timestamp.atOffset(ZoneOffset.UTC);
    }

    private record PendingPage(List<PendingKey> keys, List<PendingForecast> eligible) {
    }

    private record PendingKey(long vehicleObservationId, int targetStopOrder) {

        private static final PendingKey BEFORE_FIRST = new PendingKey(0L, 0);
    }

}

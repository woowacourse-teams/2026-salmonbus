package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.model.ObservedSeats;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.model.SeatGrid;
import com.gustler.backend.forecasting.domain.model.SeatUnknownReason;
import com.gustler.backend.forecasting.domain.model.VehicleTrajectory;
import com.gustler.backend.forecasting.domain.publication.ObservationHistory;
import com.gustler.backend.forecasting.domain.publication.ObservedBatch;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.TrajectoryObservation;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectoryAssembler;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectoryQuery;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 관측 표를 SQL로 직접 읽는다. observations 의 JPA 엔티티를 쓰지 않는다.
 *
 * <p>관측 시각은 vehicle_observation 에 없다. observation_batch.response_received_at 을
 * 관측 시각의 기준으로 사용하므로 수집 배치를 조인해 채운다.
 *
 * <p>결손 판정은 도메인이 담당한다. 수집 배치를 빠짐없이 전달하며 창을 SQL에서 걸러내지 않는다.
 * 창을 미리 걸러내면 수집 배치 결손과 구분할 수 없기 때문이다.
 */
@Repository
public class JdbcVehicleTrajectoryQuery implements VehicleTrajectoryQuery {
    /**
     * 궤적을 잇는 데 거슬러 볼 시간.
     *
     * <p>만석이 최대 12정류장 이어지는 것이 실측이고 정류소 하나에 2분쯤 걸려서 그만큼을 담게 잡았다.
     * 이 창보다 긴 연속 만석은 정확한 수가 아니라 최소값으로 나온다.
     */
    private static final Duration TRAJECTORY_HISTORY_WINDOW = Duration.ofMinutes(30);

    /** 성공한 수집 배치 중 발행이 없고 신선도 기준을 만족하는 현재 시도를 읽는다. */
    private static final String SELECT_BATCHES_AWAITING_FORECAST = """
        SELECT /* salmonbus:vehicle_trajectory_query.select_batches_awaiting_forecast */ batch.id, batch.route_version_id, version.route_id, batch.response_received_at
        FROM observation_batch batch
        JOIN route_version version
          ON version.id = batch.route_version_id
        WHERE batch.route_version_id = :routeVersionId
          AND NOT EXISTS (SELECT 1 FROM forecast_publication p WHERE p.source_batch_id = batch.id)
          AND batch.response_received_at IS NOT NULL
          AND batch.response_received_at >= :notBefore
          AND batch.outcome IN ('SUCCESS_ROWS', 'SUCCESS_EMPTY')
        ORDER BY batch.response_received_at
        LIMIT :limit
        """;

    /** 최근 처리 범위에서 신선도 기준을 지난 미발행 배치의 가장 오래된 관측 시각. */
    private static final String SELECT_OLDEST_BATCH_LEFT_BEHIND = """
        SELECT /* salmonbus:vehicle_trajectory_query.select_oldest_batch_left_behind */ batch.response_received_at
        FROM observation_batch batch
        WHERE batch.route_version_id = :routeVersionId
          AND NOT EXISTS (SELECT 1 FROM forecast_publication p WHERE p.source_batch_id = batch.id)
          AND response_received_at IS NOT NULL
          AND response_received_at >= :from
          AND response_received_at < :until
          AND outcome IN ('SUCCESS_ROWS', 'SUCCESS_EMPTY')
        ORDER BY response_received_at
        LIMIT 1
        """;

    private static final String SELECT_TARGET_BATCH = """
        SELECT /* salmonbus:vehicle_trajectory_query.select_target_batch */ route_version_id, response_received_at
        FROM observation_batch
        WHERE id = :observationBatchId
          AND response_received_at IS NOT NULL
        """;

    /**
     * 창 안의 수집 배치를 오래된 순서대로 빠짐없이 읽는다. 차량이 없던 수집 배치도 포함한다.
     *
     * <p>끝은 시각만이 아니라 (시각, id) 쌍으로 구분한다. 시각이 같은 수집 배치가 둘이면 시각만으로는
     * 대상 수집 배치가 맨 뒤에 온다는 보장이 없어 다른 수집 배치의 궤적을 반환할 수 있다.
     */
    private static final String SELECT_BATCHES_IN_WINDOW = """
        SELECT /* salmonbus:vehicle_trajectory_query.select_batches_in_window */ id, response_received_at
        FROM observation_batch
        WHERE route_version_id = :routeVersionId
          AND response_received_at IS NOT NULL
          AND response_received_at > :from
          AND (response_received_at, id) <= (:until, :observationBatchId)
        ORDER BY response_received_at, id
        """;

    /**
     * 차량 아이디가 없는 행은 뺀다. 어느 차의 관측인지 모르면 앞뒤로 이을 수가 없다.
     * 스키마가 그 열을 NULL 허용으로 두고 있어서 실제로 생길 수 있다.
     */
    private static final String SELECT_OBSERVATIONS_IN_BATCHES = """
        SELECT /* salmonbus:vehicle_trajectory_query.select_observations_in_batches */ o.id, o.observation_batch_id, o.route_version_id, o.vehicle_id,
               o.vehicle_trip_key, o.passed_stop_order,
               CASE WHEN o.forecast_eligible THEN o.remaining_seats END AS remaining_seats,
               CASE WHEN NOT o.forecast_eligible THEN 'QUALITY_WITHHELD' ELSE o.seat_unknown_reason END AS seat_unknown_reason,
               o.crowd_level
        FROM forecast_observation_quality o
        WHERE o.observation_batch_id IN (:observationBatchIds)
          AND o.vehicle_id IS NOT NULL
        ORDER BY o.observation_batch_id, o.source_row_number
        """;

    /**
     * 차량마다 현재 사용 가능한 편도에서 관측된 최대 잔여석을 조회한다.
     *
     * <p>궤적을 연결하는 30분 창으로 제한하지 않는다. 셀 통계 집계는 그 차량의 전체 관측에서
     * 같은 값을 구하므로 조회에만 창을 적용하면 집계와 서빙의 분모가 달라진다. 그러면 설계행렬의
     * 잔여석 비율과 셀 통계의 z값이 서로 다른 수를 기준으로 계산된다. 양쪽 모두 제외/보류 편도는
     * 사용하지 않는다.
     *
     * <p>기준 시각으로 제한하는 것도 집계와 같다. 제한하지 않으면 나중에 더 큰 잔여석이 관측될 때
     * 과거 예보를 같은 시각으로 다시 계산해도 다른 값이 나온다.
     *
     * <p>시각만이 아니라 (시각, id) 쌍으로 제한한다. 같은 시각의 batch가 둘이면 시각만으로는
     * 기준 batch 뒤의 것을 거를 수 없다. 앞의 이력 조회도 같은 이유로 같은 커서를 사용한다.
     *
     * <p>최대 잔여석이 0석인 차량은 정원을 알 수 없어 결과에서 제외한다. 정원을 1석으로
     * 임의 설정하면 의미 없는 계산값으로 예보가 발행된다. 잔여석을 한 번도 제공하지 않은 차량과
     * 마찬가지로 궤적을 만들지 않으며 예보도 발행하지 않는다.
     *
     * <p>차량마다 기존 잔여석 인덱스를 역순으로 읽어 조건에 맞는 첫 양수 값을 선택한다.
     * 기준 시각과 품질 검사는 LIMIT 전에 수행해야 미래/제외 관측을 제외한 최대값을 얻는다.
     * 품질은 후보 ID로 확인하여 편도 view 조인이 잔여석 순서의 인덱스 탐색을 깨지 않게 한다.
     */
    private static final String SELECT_MAXIMUM_SEATS_EVER_OBSERVED = """
        WITH /* salmonbus:vehicle_trajectory_query.select_maximum_seats_ever_observed */ vehicles AS (
            SELECT unnest(ARRAY[:vehicleIds]::text[]) AS vehicle_id
        )
        SELECT capacity.vehicle_id, capacity.maximum_seats
        FROM vehicles vehicle
        CROSS JOIN LATERAL (
            SELECT observation.vehicle_id,
                   observation.remaining_seats AS maximum_seats
            FROM vehicle_observation observation
            JOIN observation_batch batch
              ON batch.id = observation.observation_batch_id
            WHERE observation.route_version_id = :routeVersionId
              AND observation.vehicle_id = vehicle.vehicle_id
              AND observation.remaining_seats > 0 AND observation.remaining_seats <= :maximumSeats
              AND (SELECT quality.forecast_eligible FROM forecast_observation_quality quality
                   WHERE quality.id = observation.id)
              AND (batch.response_received_at, batch.id) <= (:until, :observationBatchId)
            ORDER BY observation.remaining_seats DESC
            LIMIT 1
        ) capacity
        """;

    private final JdbcClient jdbcClient;

    public JdbcVehicleTrajectoryQuery(
        JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<PendingForecastBatch> findBatchesAwaitingForecast(
        final long routeVersionId,
        Instant notBefore,
        final int limit
    ) {
        return jdbcClient.sql(SELECT_BATCHES_AWAITING_FORECAST)
            .param("routeVersionId", routeVersionId)
            .param("notBefore", offsetOf(notBefore))
            .param("limit", limit)
            .query((resultSet, rowNumber) -> new PendingForecastBatch(
                resultSet.getLong("id"),
                resultSet.getLong("route_version_id"),
                resultSet.getLong("route_id"),
                instantOf(resultSet.getObject("response_received_at", OffsetDateTime.class))))
            .list();
    }

    @Override
    public Optional<Instant> findOldestLeftBehindAt(
        final long routeVersionId,
        Instant from,
        Instant until
    ) {
        return jdbcClient.sql(SELECT_OLDEST_BATCH_LEFT_BEHIND)
            .param("routeVersionId", routeVersionId)
            .param("from", offsetOf(from))
            .param("until", offsetOf(until))
            .query((resultSet, rowNumber) ->
                instantOf(resultSet.getObject("response_received_at", OffsetDateTime.class)))
            .optional();
    }

    @Override
    public List<VehicleTrajectory> readTrajectories(
        final long observationBatchId
    ) {
        Optional<TargetBatch> target = findTargetBatch(observationBatchId);
        if (target.isEmpty()) {
            return List.of();
        }
        ObservationHistory history = historyEndingAt(target.get());
        Set<Long> eligibleIds = history.targetBatch()
            .observations()
            .stream()
            .filter(TrajectoryObservation::isForecastEligible)
            .map(TrajectoryObservation::vehicleObservationId)
            .collect(Collectors.toSet());
        return VehicleTrajectoryAssembler.assemble(history, maximumSeatsEverObservedOf(target.get(), history))
            .stream()
            .filter(trajectory -> eligibleIds.contains(trajectory.vehicleObservationId()))
            .toList();
    }

    /**
     * 대상 batch 에 있는 차량마다 그 노선 판본에서 지금까지 보여 준 가장 많은 잔여석.
     *
     * <p>대상 batch 의 차량만 묻는다. 궤적 창 안의 다른 batch 에만 있는 차량은 예보 대상이 아니라
     * 그 값이 필요 없다.
     */
    private Map<String, Integer> maximumSeatsEverObservedOf(
        TargetBatch target,
        ObservationHistory history
    ) {
        List<String> vehicleIds = history.targetBatch()
            .observations()
            .stream()
            .filter(TrajectoryObservation::isForecastEligible)
            .map(TrajectoryObservation::vehicleId)
            .distinct()
            .toList();
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        List<VehicleMaximumSeats> rows = jdbcClient.sql(SELECT_MAXIMUM_SEATS_EVER_OBSERVED)
            .param("maximumSeats", SeatGrid.LARGEST_SEATS)
            .param("routeVersionId", target.routeVersionId())
            .param("vehicleIds", vehicleIds)
            .param("until", offsetOf(target.responseReceivedAt()))
            .param("observationBatchId", target.observationBatchId())
            .query((resultSet, rowNumber) -> new VehicleMaximumSeats(
                resultSet.getString("vehicle_id"), resultSet.getInt("maximum_seats")))
            .list();

        Map<String, Integer> maximumSeatsByVehicle = rows.stream()
            .collect(LinkedHashMap::new,
                (byVehicle, row) -> byVehicle.put(row.vehicleId(), row.maximumSeats()),
                Map::putAll);
        return maximumSeatsByVehicle;
    }

    /**
     * 관측 시각이 없는 수집 배치는 관측 순서를 정할 수 없어 궤적을 만들 수 없다.
     * 수집 배치 자체가 없는 경우에도 같은 결과를 반환한다.
     */
    private Optional<TargetBatch> findTargetBatch(
        final long observationBatchId
    ) {
        return jdbcClient.sql(SELECT_TARGET_BATCH)
            .param("observationBatchId", observationBatchId)
            .query((resultSet, rowNumber) -> new TargetBatch(
                observationBatchId,
                resultSet.getLong("route_version_id"),
                instantOf(resultSet.getObject("response_received_at", OffsetDateTime.class))))
            .optional();
    }

    private ObservationHistory historyEndingAt(
        TargetBatch target
    ) {
        List<WindowBatch> window = readWindow(target);
        Map<Long, List<TrajectoryObservation>> observationsByBatch = readObservations(window);

        List<ObservedBatch> batches = window.stream()
            .map(batch -> new ObservedBatch(
                batch.observationBatchId(),
                batch.responseReceivedAt(),
                observationsByBatch.getOrDefault(batch.observationBatchId(), List.of())))
            .collect(Collectors.toCollection(ArrayList::new));
        return new ObservationHistory(batches);
    }

    private List<WindowBatch> readWindow(
        TargetBatch target
    ) {
        Instant until = target.responseReceivedAt();
        return jdbcClient.sql(SELECT_BATCHES_IN_WINDOW)
            .param("routeVersionId", target.routeVersionId())
            .param("from", offsetOf(until.minus(TRAJECTORY_HISTORY_WINDOW)))
            .param("until", offsetOf(until))
            .param("observationBatchId", target.observationBatchId())
            .query((resultSet, rowNumber) -> new WindowBatch(
                resultSet.getLong("id"),
                instantOf(resultSet.getObject("response_received_at", OffsetDateTime.class))))
            .list();
    }

    private Map<Long, List<TrajectoryObservation>> readObservations(
        List<WindowBatch> window
    ) {
        Map<Long, Instant> observedAtByBatch = window.stream()
            .collect(LinkedHashMap::new,
                (byBatch, batch) -> byBatch.put(batch.observationBatchId(), batch.responseReceivedAt()),
                Map::putAll);

        List<ObservationRow> rows = jdbcClient.sql(SELECT_OBSERVATIONS_IN_BATCHES)
            .param("observationBatchIds", observedAtByBatch.keySet())
            .query((resultSet, rowNumber) -> new ObservationRow(
                resultSet.getLong("id"),
                resultSet.getLong("observation_batch_id"),
                resultSet.getLong("route_version_id"),
                resultSet.getString("vehicle_id"),
                resultSet.getString("vehicle_trip_key"),
                resultSet.getInt("passed_stop_order"),
                resultSet.getObject("remaining_seats", Integer.class),
                resultSet.getString("seat_unknown_reason"),
                resultSet.getObject("crowd_level", Integer.class)))
            .list();

        Map<Long, List<TrajectoryObservation>> observationsByBatch = rows.stream()
            .collect(LinkedHashMap::new,
                (byBatch, row) -> byBatch
                    .computeIfAbsent(row.observationBatchId(), batchId -> new ArrayList<>())
                    .add(row.toObservation(observedAtByBatch.get(row.observationBatchId()))),
                (left, right) -> right.forEach((key, values) -> left
                    .computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(values)));
        return observationsByBatch;
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

    private record TargetBatch(
        long observationBatchId,
        long routeVersionId,
        Instant responseReceivedAt
    ) {
    }

    private record WindowBatch(
        long observationBatchId,
        Instant responseReceivedAt
    ) {
    }

    /** 차량 하나에서 지금까지 관측한 최대 잔여석. */
    private record VehicleMaximumSeats(
        String vehicleId,
        int maximumSeats
    ) {
    }

    /** 관측 한 행. 관측 시각은 해당 수집 배치에서 가져온다. */
    private record ObservationRow(
        long id,
        long observationBatchId,
        long routeVersionId,
        String vehicleId,
        String vehicleTripKey,
        int passedStopOrder,
        Integer remainingSeats,
        String seatUnknownReason,
        Integer crowdLevel
    ) {
        TrajectoryObservation toObservation(
            Instant observedAt
        ) {
            return new TrajectoryObservation(
                id,
                new ObservedVehicle(
                    vehicleId, routeVersionId, passedStopOrder, observedAt, remainingSeats, crowdLevel),
                vehicleTripKey,
                ObservedSeats.of(remainingSeats, seatUnknownReasonOf()));
        }

        /** 값 목록은 V4 의 CHECK 에서 두 값으로 제한한다. 다른 값이 오면 스키마 불일치로 보고 중단한다. */
        private SeatUnknownReason seatUnknownReasonOf() {
            if (seatUnknownReason == null) {
                return null;
            }
            return SeatUnknownReason.valueOf(seatUnknownReason);
        }
    }
}

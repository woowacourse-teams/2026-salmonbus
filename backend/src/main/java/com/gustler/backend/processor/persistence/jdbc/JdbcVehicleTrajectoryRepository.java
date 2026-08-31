package com.gustler.backend.processor.persistence.jdbc;

import com.gustler.backend.processor.ObservationHistory;
import com.gustler.backend.processor.ObservedBatch;
import com.gustler.backend.processor.ObservedSeats;
import com.gustler.backend.processor.ObservedVehicle;
import com.gustler.backend.processor.PendingForecastBatch;
import com.gustler.backend.processor.SeatUnknownReason;
import com.gustler.backend.processor.TrajectoryObservation;
import com.gustler.backend.processor.VehicleTrajectory;
import com.gustler.backend.processor.VehicleTrajectoryAssembler;
import com.gustler.backend.processor.VehicleTrajectoryRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 관측 표를 SQL 로 직접 읽는다. collector 의 JPA 엔티티를 쓰지 않는다.
 *
 * <p>관측 시각은 vehicle_observation 에 없다. observation_batch.response_received_at 이
 * 관측 시각의 권위라서 판을 조인해 채운다.
 *
 * <p>결손 판정은 여기서 안 한다. 판을 빠짐없이 늘어놓아 넘기고 판정은 도메인이 한다.
 * 창을 SQL 에서 걸러내면 판 결손과 구분이 안 된다.
 */
@Repository
public class JdbcVehicleTrajectoryRepository implements VehicleTrajectoryRepository {

    /**
     * 궤적을 잇는 데 거슬러 볼 시간.
     *
     * <p>만석이 최대 12정류장 이어지는 것이 실측이고 정류소 하나에 2분쯤 걸려서 그만큼을 담게 잡았다.
     * 이 창보다 긴 연속 만석은 정확한 수가 아니라 최소값으로 나온다.
     */
    private static final Duration TRAJECTORY_HISTORY_WINDOW = Duration.ofMinutes(30);

    /**
     * 예보를 붙일 수 있는 판의 결말. 상류를 부르는 데 성공한 판만 예보 대상이다.
     *
     * <p>실패 갈래는 SAL-85 가 붙인다. 그때 이 목록에 안 들어가야 실패한 판이 영영
     * 예보 대기로 남지 않는다. V1 의 ix_batch_forecast_ready 도 같은 두 값을 적고 있다.
     */
    private static final List<String> FORECASTABLE_OUTCOMES = List.of("SUCCESS_ROWS", "SUCCESS_EMPTY");

    private static final String SELECT_BATCHES_AWAITING_FORECAST = """
        SELECT id, route_version_id, response_received_at
        FROM observation_batch
        WHERE route_version_id = :routeVersionId
          AND forecast_completed_at IS NULL
          AND response_received_at IS NOT NULL
          AND outcome IN (:outcomes)
        ORDER BY response_received_at
        LIMIT :limit
        """;

    private static final String SELECT_TARGET_BATCH = """
        SELECT route_version_id, response_received_at
        FROM observation_batch
        WHERE id = :observationBatchId
          AND response_received_at IS NOT NULL
        """;

    /**
     * 창 안의 판을 하나도 빠뜨리지 않고 오래된 것부터. 차가 없던 판도 같이 온다.
     *
     * <p>끝을 시각만으로 끊지 않고 (시각, id) 쌍으로 끊는다. 같은 시각의 판이 둘이면
     * 시각만으로는 물어본 판이 맨 뒤에 온다는 보장이 없고, 그러면 엉뚱한 판의 궤적이 나간다.
     */
    private static final String SELECT_BATCHES_IN_WINDOW = """
        SELECT id, response_received_at
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
        SELECT id, observation_batch_id, route_version_id, vehicle_id,
               vehicle_trip_key, passed_stop_order, remaining_seats, seat_unknown_reason, crowd_level
        FROM vehicle_observation
        WHERE observation_batch_id IN (:observationBatchIds)
          AND vehicle_id IS NOT NULL
        ORDER BY observation_batch_id, source_row_number
        """;

    /**
     * 차량마다 지금까지 보여 준 가장 많은 잔여석.
     *
     * <p><b>궤적을 잇는 30분 창을 안 쓴다.</b> 셀 통계 집계가 같은 값을 그 차량의 관측 전부에서
     * 세고 있어서, 창으로 자르면 집계 쪽 분모와 서빙 쪽 분모가 갈린다. 그러면 설계행렬의
     * 잔여석 비율과 셀 통계의 z값이 서로 다른 수로 나눈 값이 된다.
     *
     * <p>기준 시각으로 자르는 것도 집계와 같다. 자르지 않으면 나중에 더 큰 잔여석이 들어올 때
     * 예전 예보를 같은 시각으로 다시 계산해도 다른 값이 나온다.
     *
     * <p><b>시각 하나가 아니라 (시각, id) 쌍으로 자른다.</b> 같은 시각의 batch 가 둘이면 시각만으로는
     * 물어본 batch 뒤의 것이 안 걸러진다. 바로 위 이력 조회가 같은 이유로 같은 커서를 쓴다.
     *
     * <p>줄곧 만석이던 차량은 최대 잔여석이 0석인데 그것으로 나눌 수 없어 1석을 바닥으로 둔다.
     * 집계 쪽 GREATEST 와 같은 자리다. 잔여석을 한 번도 안 보여 준 차량은 결과에 아예 없고,
     * 그 자리를 1석으로 세는 것은 도메인이 한다.
     */
    private static final String SELECT_MAXIMUM_SEATS_EVER_OBSERVED = """
        SELECT observation.vehicle_id,
               GREATEST(MAX(observation.remaining_seats), 1) AS maximum_seats
        FROM vehicle_observation observation
        JOIN observation_batch batch
          ON batch.id = observation.observation_batch_id
        WHERE observation.route_version_id = :routeVersionId
          AND observation.vehicle_id IN (:vehicleIds)
          AND observation.remaining_seats IS NOT NULL
          AND (batch.response_received_at, batch.id) <= (:until, :observationBatchId)
        GROUP BY observation.vehicle_id
        """;

    private final JdbcClient jdbcClient;

    public JdbcVehicleTrajectoryRepository(
        JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<PendingForecastBatch> findBatchesAwaitingForecast(
        final long routeVersionId,
        final int limit
    ) {
        return jdbcClient.sql(SELECT_BATCHES_AWAITING_FORECAST)
            .param("routeVersionId", routeVersionId)
            .param("outcomes", FORECASTABLE_OUTCOMES)
            .param("limit", limit)
            .query((resultSet, rowNumber) -> new PendingForecastBatch(
                resultSet.getLong("id"),
                resultSet.getLong("route_version_id"),
                instantOf(resultSet.getObject("response_received_at", OffsetDateTime.class))))
            .list();
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
        return VehicleTrajectoryAssembler.assemble(
            history, maximumSeatsEverObservedOf(target.get(), history));
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
        List<String> vehicleIds = history.targetBatch().observations().stream()
            .map(TrajectoryObservation::vehicleId)
            .distinct()
            .toList();
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        List<VehicleMaximumSeats> rows = jdbcClient.sql(SELECT_MAXIMUM_SEATS_EVER_OBSERVED)
            .param("routeVersionId", target.routeVersionId())
            .param("vehicleIds", vehicleIds)
            .param("until", offsetOf(target.responseReceivedAt()))
            .param("observationBatchId", target.observationBatchId())
            .query((resultSet, rowNumber) -> new VehicleMaximumSeats(
                resultSet.getString("vehicle_id"), resultSet.getInt("maximum_seats")))
            .list();

        Map<String, Integer> maximumSeatsByVehicle = new LinkedHashMap<>();
        for (VehicleMaximumSeats row : rows) {
            maximumSeatsByVehicle.put(row.vehicleId(), row.maximumSeats());
        }
        return maximumSeatsByVehicle;
    }

    /**
     * 관측 시각이 없는 판은 궤적을 못 낸다. 언제 본 것인지 모르면 앞뒤로 늘어놓을 수가 없다.
     * 없는 판을 물어봐도 같은 답이다.
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

        List<ObservedBatch> batches = new ArrayList<>();
        for (WindowBatch batch : window) {
            batches.add(new ObservedBatch(
                batch.observationBatchId(),
                batch.responseReceivedAt(),
                observationsByBatch.getOrDefault(batch.observationBatchId(), List.of())));
        }
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
        Map<Long, Instant> observedAtByBatch = new LinkedHashMap<>();
        for (WindowBatch batch : window) {
            observedAtByBatch.put(batch.observationBatchId(), batch.responseReceivedAt());
        }

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

        Map<Long, List<TrajectoryObservation>> observationsByBatch = new LinkedHashMap<>();
        for (ObservationRow row : rows) {
            observationsByBatch
                .computeIfAbsent(row.observationBatchId(), batchId -> new ArrayList<>())
                .add(row.toObservation(observedAtByBatch.get(row.observationBatchId())));
        }
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

    /** 차량 하나가 지금까지 보여 준 가장 많은 잔여석. */
    private record VehicleMaximumSeats(
        String vehicleId,
        int maximumSeats
    ) {
    }

    /** 관측 한 행. 관측 시각만 자기 판에서 받아 채운다. */
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

        /** 값 목록은 V4 의 CHECK 이 둘로 묶어 둔다. 그 밖의 값이 오면 스키마가 어긋난 것이라 멈춘다. */
        private SeatUnknownReason seatUnknownReasonOf() {
            if (seatUnknownReason == null) {
                return null;
            }
            return SeatUnknownReason.valueOf(seatUnknownReason);
        }
    }
}

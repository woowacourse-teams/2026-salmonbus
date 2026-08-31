package com.gustler.backend.processor.persistence.jdbc;

import com.gustler.backend.processor.ArrivalCandidate;
import com.gustler.backend.processor.ArrivalObservationRepository;
import com.gustler.backend.processor.ObservedVehicle;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 예보를 낸 뒤 그 차량이 남긴 관측을 SQL 로 직접 읽는다. collector 의 JPA 엔티티를 쓰지 않는다.
 *
 * <p>관측 시각은 vehicle_observation 에 없다. observation_batch.response_received_at 이
 * 관측 시각의 권위라서 판을 조인해 채운다.
 *
 * <p>통과 순번은 적재가 계산해 열에 남긴 값을 그대로 읽는다. 운행 상태에서 다시 유도하면
 * 같은 규칙이 적재 쪽과 조회 쪽에 두 벌 생기고 어긋나는 순간을 아무도 못 잡는다.
 *
 * <p>어느 관측이 라벨이 되는지는 여기서 안 고른다. 판을 빠짐없이 늘어놓아 넘기고 판정은 도메인이 한다.
 * SQL 이 대상 순번의 행만 집어 오면 순번 되돌림과 관측 공백이 구분되지 않는다.
 */
@Repository
public class JdbcArrivalObservationRepository implements ArrivalObservationRepository {

    private static final String SELECT_OBSERVATIONS_AFTER = """
        SELECT o.id,
               o.vehicle_id,
               o.route_version_id,
               o.passed_stop_order,
               b.response_received_at,
               o.remaining_seats
        FROM vehicle_observation o
        JOIN observation_batch b
          ON b.id = o.observation_batch_id
         AND b.route_version_id = o.route_version_id
        WHERE o.route_version_id = :routeVersionId
          AND o.vehicle_id = :vehicleId
          AND b.response_received_at > :observedAfter
        ORDER BY b.response_received_at, o.id
        LIMIT :limit
        """;

    private final JdbcClient jdbcClient;

    public JdbcArrivalObservationRepository(
        JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<ArrivalCandidate> findAfter(
        final long routeVersionId,
        String vehicleId,
        Instant observedAfter,
        final int limit
    ) {
        return jdbcClient.sql(SELECT_OBSERVATIONS_AFTER)
            .param("routeVersionId", routeVersionId)
            .param("vehicleId", vehicleId)
            .param("observedAfter", offsetOf(observedAfter))
            .param("limit", limit)
            .query((resultSet, rowNumber) -> new ArrivalCandidate(
                resultSet.getLong("id"),
                new ObservedVehicle(
                    resultSet.getString("vehicle_id"),
                    resultSet.getLong("route_version_id"),
                    resultSet.getInt("passed_stop_order"),
                    instantOf(resultSet.getObject("response_received_at", OffsetDateTime.class)),
                    (Integer) resultSet.getObject("remaining_seats"))))
            .list();
    }

    private static OffsetDateTime offsetOf(
        Instant instant
    ) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instantOf(
        OffsetDateTime offsetDateTime
    ) {
        return offsetDateTime == null ? null : offsetDateTime.toInstant();
    }
}

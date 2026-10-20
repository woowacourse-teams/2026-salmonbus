package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalObservationQuery;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 예보 이후 같은 차량의 관측을 읽는다. ID 조회와 상세 조회의 차량·노선 범위는 같다.
 *
 * <p>관측 시각은 observation_batch.response_received_at에서 가져오고,
 * 통과 순번은 수집 시 계산한 값을 그대로 사용한다.
 * 대상 정류장의 관측만 고르면 관측 공백과 순번 되돌림을 놓치므로 여기서는 거르지 않는다.
 * 정산 판정은 시각과 ID 순서로 정렬한 전체 후보를 사용한다.
 */
@Repository
public class JdbcArrivalObservationQuery implements ArrivalObservationQuery {
    private static final String COLUMNS = """
        SELECT o.id, o.vehicle_id, o.route_version_id, o.passed_stop_order,
               b.response_received_at, o.remaining_seats, o.crowd_level,
               CASE WHEN o.forecast_eligible THEN o.quality_direction END AS quality_direction,
               o.id AS assessed_id
        """;

    private static final String SOURCE = """
        FROM forecast_observation_quality o
        JOIN observation_batch b
          ON b.id = o.observation_batch_id
         AND b.route_version_id = o.route_version_id
        WHERE o.route_version_id = :routeVersionId
          AND o.vehicle_id = :vehicleId
        """;

    private static final String AFTER = """
          AND b.response_received_at > :observedAfter
        ORDER BY b.response_received_at, o.id
        LIMIT :limit
        """;

    private final JdbcClient jdbcClient;

    public JdbcArrivalObservationQuery(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<Long> findIdsAfter(
        final long routeVersionId,
        String vehicleId,
        Instant observedAfter,
        final int limit
    ) {
        return jdbcClient.sql("SELECT o.id\n" + SOURCE + AFTER)
            .param("routeVersionId", routeVersionId)
            .param("vehicleId", vehicleId)
            .param("observedAfter", offsetOf(observedAfter))
            .param("limit", limit)
            .query(Long.class).list();
    }

    @Override
    public List<ArrivalCandidate> findByIds(final long routeVersionId, String vehicleId, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql(COLUMNS + SOURCE + " AND o.id IN (:ids)")
            .param("routeVersionId", routeVersionId)
            .param("vehicleId", vehicleId)
            .param("ids", ids)
            .query((rs, rowNumber) -> candidate(rs)).list();
    }

    @Override
    public List<ArrivalCandidate> findAfter(
        final long routeVersionId,
        String vehicleId,
        Instant observedAfter,
        final int limit
    ) {
        return jdbcClient.sql(COLUMNS + SOURCE + AFTER)
            .param("routeVersionId", routeVersionId)
            .param("vehicleId", vehicleId)
            .param("observedAfter", offsetOf(observedAfter))
            .param("limit", limit)
            .query((rs, rowNumber) -> candidate(rs)).list();
    }

    static ArrivalCandidate candidate(ResultSet rs) throws SQLException {
        return new ArrivalCandidate(rs.getLong("id"),
            new ObservedVehicle(rs.getString("vehicle_id"), rs.getLong("route_version_id"),
                rs.getInt("passed_stop_order"),
                instantOf(rs.getObject("response_received_at", OffsetDateTime.class)),
                (Integer) rs.getObject("remaining_seats"), (Integer) rs.getObject("crowd_level")),
            rs.getObject("quality_direction", Long.class), rs.getObject("assessed_id", Long.class) != null);
    }

    private static OffsetDateTime offsetOf(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instantOf(OffsetDateTime at) {
        if (at == null) {
            return null;
        }
        return at.toInstant();
    }
}

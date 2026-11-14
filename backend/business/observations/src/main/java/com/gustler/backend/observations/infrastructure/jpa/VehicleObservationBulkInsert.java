package com.gustler.backend.observations.infrastructure.jpa;

import com.gustler.backend.observations.domain.SeatUnknownReason;
import com.gustler.backend.observations.domain.StoredObservations.Row;
import com.gustler.backend.observations.domain.UpstreamObservationRow;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

/**
 * 한 배치의 관측을 INSERT 한 문장으로 저장한다.
 *
 * <p>열 값은 JPA 저장과 같은 {@link VehicleObservationJpaEntity} 생성자로 만들고, JPA 매핑이 넣는 열만 같은 순서로 넣는다.
 * 매핑하지 않은 열은 JPA 저장처럼 DB 기본값을 받는다.
 * 새 관측 번호는 RETURNING 의 줄 순서가 아니라 (배치, 상류 행 번호)로 각 줄에 대응시킨다.
 * 같은 배치 안의 상류 행 번호는 ux_observation_source_row 로 유일하다.
 * 호출자의 트랜잭션에 참여한다. 제약 위반은 JPA 저장과 같은 {@link DataIntegrityViolationException}으로 던진다.
 * JdbcTemplate 은 중복 키를 그 하위 종류인 {@link DuplicateKeyException}으로 바꾸므로 원인을 유지한 채 다시 던진다.
 */
@Component
public class VehicleObservationBulkInsert {
    private static final String INSERT = """
        INSERT INTO vehicle_observation (
            observation_batch_id, route_version_id, source_row_number, vehicle_id, plate_number,
            stop_order, stop_id, passed_stop_order, running_state, remaining_seats, seat_unknown_reason,
            crowd_level, vehicle_type, route_type, tagless
        )
        SELECT ?, ?, input.source_row_number, input.vehicle_id, input.plate_number,
               input.stop_order, input.stop_id, input.passed_stop_order, input.running_state,
               input.remaining_seats, input.seat_unknown_reason,
               input.crowd_level, input.vehicle_type, input.route_type, input.tagless
        FROM unnest(
            CAST(? AS integer[]), CAST(? AS varchar[]), CAST(? AS varchar[]),
            CAST(? AS integer[]), CAST(? AS varchar[]), CAST(? AS integer[]), CAST(? AS integer[]),
            CAST(? AS integer[]), CAST(? AS varchar[]),
            CAST(? AS integer[]), CAST(? AS integer[]), CAST(? AS integer[]), CAST(? AS integer[])
        ) WITH ORDINALITY AS input(
            source_row_number, vehicle_id, plate_number,
            stop_order, stop_id, passed_stop_order, running_state,
            remaining_seats, seat_unknown_reason,
            crowd_level, vehicle_type, route_type, tagless, input_order
        )
        ORDER BY input.input_order
        RETURNING id, source_row_number
        """;

    private final JdbcOperations jdbc;

    public VehicleObservationBulkInsert(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 관측을 한 문장으로 넣고 입력 순서대로 저장 식별자가 붙은 행을 반환한다. 빈 목록이면 문장을 내지 않는다.
     */
    public List<Row> insert(final long batchId, final long routeVersionId, List<UpstreamObservationRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<VehicleObservationJpaEntity> entities = rows.stream()
            .map(row -> new VehicleObservationJpaEntity(batchId, routeVersionId, row))
            .toList();
        Map<Integer, Long> idsBySourceRow = insertReturningIds(batchId, routeVersionId, entities);
        return entities.stream()
            .map(entity -> {
                entity.insertedAs(idOf(idsBySourceRow, entity.getSourceRowNumber()));
                return entity.storedRow();
            })
            .toList();
    }

    private Map<Integer, Long> insertReturningIds(
        final long batchId,
        final long routeVersionId,
        List<VehicleObservationJpaEntity> entities
    ) {
        try {
            return jdbc.query(
                connection -> statement(connection, batchId, routeVersionId, entities),
                resultSet -> {
                    Map<Integer, Long> ids = new HashMap<>();
                    while (resultSet.next()) {
                        ids.put(resultSet.getInt("source_row_number"), resultSet.getLong("id"));
                    }
                    return ids;
                });
        } catch (DuplicateKeyException duplicate) {
            throw new DataIntegrityViolationException(duplicate.getMessage(), duplicate.getCause());
        }
    }

    private static long idOf(Map<Integer, Long> idsBySourceRow, Integer sourceRowNumber) {
        Long id = idsBySourceRow.get(sourceRowNumber);
        if (id == null) {
            throw new IllegalStateException("저장한 관측의 번호를 받지 못했다: 상류행=" + sourceRowNumber);
        }
        return id;
    }

    private static PreparedStatement statement(
        Connection connection,
        final long batchId,
        final long routeVersionId,
        List<VehicleObservationJpaEntity> entities
    ) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(INSERT);
        statement.setLong(1, batchId);
        statement.setLong(2, routeVersionId);
        statement.setArray(3, integers(connection, entities, VehicleObservationJpaEntity::getSourceRowNumber));
        statement.setArray(4, texts(connection, entities, VehicleObservationJpaEntity::getVehicleId));
        statement.setArray(5, texts(connection, entities, VehicleObservationJpaEntity::getPlateNumber));
        statement.setArray(6, integers(connection, entities, VehicleObservationJpaEntity::getStopOrder));
        statement.setArray(7, texts(connection, entities, VehicleObservationJpaEntity::getStopId));
        statement.setArray(8, integers(connection, entities, VehicleObservationJpaEntity::getPassedStopOrder));
        statement.setArray(9, integers(connection, entities, VehicleObservationJpaEntity::getRunningState));
        statement.setArray(10, integers(connection, entities, VehicleObservationJpaEntity::getRemainingSeats));
        statement.setArray(11, texts(connection, entities, entity -> reasonName(entity.getSeatUnknownReason())));
        statement.setArray(12, integers(connection, entities, VehicleObservationJpaEntity::getCrowdLevel));
        statement.setArray(13, integers(connection, entities, VehicleObservationJpaEntity::getVehicleType));
        statement.setArray(14, integers(connection, entities, VehicleObservationJpaEntity::getRouteType));
        statement.setArray(15, integers(connection, entities, VehicleObservationJpaEntity::getTagless));
        return statement;
    }

    private static String reasonName(SeatUnknownReason reason) {
        if (reason == null) {
            return null;
        }
        return reason.name();
    }

    private static Array integers(
        Connection connection,
        List<VehicleObservationJpaEntity> entities,
        Function<VehicleObservationJpaEntity, Integer> column
    ) throws SQLException {
        return connection.createArrayOf("integer", entities.stream().map(column).toArray(Integer[]::new));
    }

    private static Array texts(
        Connection connection,
        List<VehicleObservationJpaEntity> entities,
        Function<VehicleObservationJpaEntity, String> column
    ) throws SQLException {
        return connection.createArrayOf("varchar", entities.stream().map(column).toArray(String[]::new));
    }
}

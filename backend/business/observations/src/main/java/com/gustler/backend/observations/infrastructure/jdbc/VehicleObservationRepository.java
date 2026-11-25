package com.gustler.backend.observations.infrastructure.jdbc;

import com.gustler.backend.observations.domain.RemainingSeats;
import com.gustler.backend.observations.domain.VehicleObservationRow;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관측을 원본 순서대로 한 문장에 저장하고, 같은 계획을 재개할 때 이전 관측을 지운다.
 * 호출자의 트랜잭션에 참여하며 매핑하지 않은 열은 DB 기본값을 유지한다.
 */
@Repository
@Transactional(readOnly = false, propagation = Propagation.MANDATORY)
public class VehicleObservationRepository {

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
        """;

    private final JdbcOperations jdbc;

    public VehicleObservationRepository(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 빈 목록은 SQL을 실행하지 않는다. 저장 ID는 DB에 남기고 호출자에게 복사하지 않는다.
     * JdbcTemplate의 중복 키 예외를 기존 무결성 예외로 맞추되 SQL 원인은 보존한다.
     */
    public void insertAll(final long batchId, final long routeVersionId, List<VehicleObservationRow> rows) {
        if (rows.isEmpty()) {
            return;
        }

        try {
            final int inserted = jdbc.update(connection -> statement(
                    connection,
                    batchId,
                    routeVersionId,
                    rows
            ));

            if (inserted != rows.size()) {
                throw new IllegalStateException("저장한 관측 수가 입력과 다르다");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new DataIntegrityViolationException(duplicate.getMessage(), duplicate.getCause());
        }
    }

    /** 기존 관측 삭제도 호출자의 준비 트랜잭션에 참여한다. */
    public void deleteByObservationBatchId(final long batchId) {
        jdbc.update("DELETE FROM vehicle_observation WHERE observation_batch_id = ?", batchId);
    }

    private static PreparedStatement statement(
        Connection connection,
        final long batchId,
        final long routeVersionId,
        List<VehicleObservationRow> rows
    ) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(INSERT);
        statement.setLong(1, batchId);
        statement.setLong(2, routeVersionId);
        statement.setArray(3, integers(connection, rows, VehicleObservationRow::sourceRowNumber));
        statement.setArray(4, texts(connection, rows, row -> row.observation().vehicleId()));
        statement.setArray(5, texts(connection, rows, row -> row.observation().plateNumber()));
        statement.setArray(6, integers(connection, rows, row -> row.observation().stopSequence()));
        statement.setArray(7, texts(connection, rows, row -> row.observation().stopId()));
        statement.setArray(8, integers(connection, rows, row -> row.observation().passedStopOrder()));
        statement.setArray(9, integers(connection, rows, row -> row.observation().runningState()));
        statement.setArray(10, integers(connection, rows, VehicleObservationRepository::seatsOf));
        statement.setArray(11, texts(connection, rows, VehicleObservationRepository::seatUnknownReasonOf));
        statement.setArray(12, integers(connection, rows, row -> row.observation().crowdLevel()));
        statement.setArray(13, integers(connection, rows, row -> row.observation().vehicleType()));
        statement.setArray(14, integers(connection, rows, row -> row.observation().routeType()));
        statement.setArray(15, integers(connection, rows, row -> row.observation().tagless()));

        return statement;
    }

    private static Integer seatsOf(VehicleObservationRow row) {
        return switch (row.observation().remainingSeats()) {
            case RemainingSeats.Known known -> known.seats();
            case RemainingSeats.Unknown ignored -> null;
        };
    }

    private static String seatUnknownReasonOf(VehicleObservationRow row) {
        return switch (row.observation().remainingSeats()) {
            case RemainingSeats.Known ignored -> null;
            case RemainingSeats.Unknown unknown -> unknown.reason().name();
        };
    }

    private static Array integers(
        Connection connection,
        List<VehicleObservationRow> rows,
        Function<VehicleObservationRow, Integer> column
    ) throws SQLException {
        return connection.createArrayOf("integer", rows.stream().map(column).toArray(Integer[]::new));
    }

    private static Array texts(
        Connection connection,
        List<VehicleObservationRow> rows,
        Function<VehicleObservationRow, String> column
    ) throws SQLException {
        return connection.createArrayOf("varchar", rows.stream().map(column).toArray(String[]::new));
    }
}

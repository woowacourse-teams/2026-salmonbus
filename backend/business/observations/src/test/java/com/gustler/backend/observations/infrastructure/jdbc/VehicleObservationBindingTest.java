package com.gustler.backend.observations.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.observations.domain.RemainingSeats;
import com.gustler.backend.observations.domain.VehicleObservation;
import com.gustler.backend.observations.domain.VehicleObservationRow;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;

/** JDBC로 전달하는 값만 확인한다. PostgreSQL 제약, 트랜잭션 검증은 Repository 통합 테스트가 담당한다. */
class VehicleObservationBindingTest {
    @Test
    void 원본_순서와_도착_순번과_좌석_미관측을_JDBC_인자로_보존한다() throws SQLException {
        // given
        JdbcOperations jdbc = mock(JdbcOperations.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        Map<Integer, Object[]> columns = captureColumns(connection, statement);
        when(jdbc.update(any(PreparedStatementCreator.class))).thenAnswer(call -> {
            PreparedStatementCreator creator = call.getArgument(0);
            creator.createPreparedStatement(connection);
            return 3;
        });

        // when
        new VehicleObservationRepository(jdbc).insertAll(10, 7,
            List.of(row(4, 0), row(0, -1), row(2, null)));

        // then
        assertSoftly(softly -> {
            softly.assertThat(columns.get(3)).containsExactly(4, 0, 2);
            softly.assertThat(columns.get(4)).containsExactly("v-4", "v-0", "v-2");
            softly.assertThat(columns.get(8)).containsExactly(0, 0, 0);
            softly.assertThat(columns.get(10)).containsExactly(0, null, null);
            softly.assertThat(columns.get(11)).containsExactly(null, "REPORTED_UNKNOWN", "NOT_REPORTED");
        });
        verify(statement).setLong(1, 10);
        verify(statement).setLong(2, 7);
    }

    @Test
    void 빈_관측은_JDBC를_호출하지_않는다() {
        // given
        JdbcOperations jdbc = mock(JdbcOperations.class);

        // when
        new VehicleObservationRepository(jdbc).insertAll(10, 7, List.of());

        // then
        verifyNoInteractions(jdbc);
    }

    @Test
    void 중복_저장의_SQL_원인을_무결성_예외에_보존한다() {
        // given
        JdbcOperations jdbc = mock(JdbcOperations.class);
        SQLException cause = new SQLException("duplicate source row", "23505");
        when(jdbc.update(any(PreparedStatementCreator.class)))
            .thenThrow(new DuplicateKeyException("duplicate", cause));

        // when
        Throwable failure = catchThrowable(() -> new VehicleObservationRepository(jdbc)
            .insertAll(10, 7, List.of(row(0, 10))));

        // then
        assertThat(failure).isExactlyInstanceOf(DataIntegrityViolationException.class).hasCause(cause);
    }

    private Map<Integer, Object[]> captureColumns(Connection connection, PreparedStatement statement)
        throws SQLException {
        Map<Integer, Object[]> columns = new HashMap<>();
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(connection.createArrayOf(anyString(), any(Object[].class))).thenAnswer(call -> {
            Array array = mock(Array.class);
            when(array.getArray()).thenReturn(call.getArgument(1));
            return array;
        });
        doAnswer(call -> {
            Array array = call.getArgument(1);
            columns.put(call.getArgument(0), (Object[]) array.getArray());
            return null;
        }).when(statement).setArray(anyInt(), any(Array.class));
        return columns;
    }

    private VehicleObservationRow row(final int sourceRow, Integer seats) {
        return new VehicleObservationRow(sourceRow, new VehicleObservation(
                "v-" + sourceRow,
                null,
                1,
                "205000217",
                1,
                RemainingSeats.from(seats),
                3,
                0,
                11,
                1
        ));
    }
}

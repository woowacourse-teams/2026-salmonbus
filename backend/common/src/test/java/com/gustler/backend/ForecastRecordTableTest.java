package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class ForecastRecordTableTest {
    private static final List<String> PARTITIONED =
        List.of("forecast_record", "forecast_run", "forecast_unresolved", "pass_label", "stop_pass");

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void 새_예보_경로_테이블은_배치_날짜로_나뉘고_기본_파티션만_가진_채로_시작한다() {
        // when
        List<String> partitioned = jdbcClient.sql("""
                SELECT parent.relname || ':' || pg_get_partkeydef(parent.oid) || ':' || child.relname
                FROM pg_partitioned_table partition
                JOIN pg_class parent ON parent.oid = partition.partrelid
                JOIN pg_inherits ON pg_inherits.inhparent = parent.oid
                JOIN pg_class child ON child.oid = pg_inherits.inhrelid
                WHERE parent.relname = ANY (?)
                ORDER BY parent.relname
                """)
            .param(PARTITIONED.toArray(String[]::new))
            .query(String.class)
            .list();

        // then
        assertThat(partitioned).containsExactlyElementsOf(PARTITIONED.stream()
            .map(table -> table + ":RANGE (source_date):" + table + "_default")
            .toList());
    }

    @Test
    void 목표_정류장과_예측_배열의_길이가_다르면_저장되지_않는다() {
        // when
        Throwable actual = catchThrowable(() -> jdbcClient.sql("""
            INSERT INTO forecast_record (source_date, vehicle_observation_id, observation_batch_id, source_row_number,
                route_id, route_version_id, vehicle_id, passed_stop_order, trip_start_observation_id,
                chain_start_observation_id, down, capacity, extended, target_stop_orders, expected_seats,
                full_probabilities)
            VALUES (DATE '2030-01-01', 1, 1, 1, 1, 1, 'bus', 1, 1, 1, false, 45, false,
                ARRAY[2, 3], ARRAY[1.0, 2.0]::float8[], ARRAY[0.5]::float8[])
            """).update());

        // then
        assertThat(actual).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 채점된_정답에_사유가_있으면_저장되지_않는다() {
        // when
        Throwable actual = catchThrowable(() -> insertLabel("SETTLED", "'NO_PREVIOUS_PASS'", "1.5"));

        // then
        assertThat(actual).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 지원_안_함인_정답에_값이_있으면_저장되지_않는다() {
        // when
        Throwable actual = catchThrowable(() -> insertLabel("UNSUPPORTED", "'NO_PREVIOUS_PASS'", "1.5"));

        // then
        assertThat(actual).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertLabel(String state, String reason, String value) {
        jdbcClient.sql("""
            INSERT INTO pass_label (source_date, route_id, trip_start_observation_id, stop_order, capacity,
                label_version, model_deployment_id, state, reason, label_value, computed_at)
            VALUES (DATE '2030-01-01', 1, 1, 1, 45, 'v1', 1, '%s', %s, %s, now())
            """.formatted(state, reason, value)).update();
    }
}

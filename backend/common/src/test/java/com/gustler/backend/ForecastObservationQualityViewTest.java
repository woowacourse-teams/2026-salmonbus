package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.IntegrationTest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class ForecastObservationQualityViewTest {
    private static final String ROUTE_204000057 = "204000057";
    private static final String STOP_205000227 = "205000227";
    private static final int FIRST_STOP = 1;
    private static final int TURN_SEQUENCE = 43;
    private static final OffsetDateTime OBSERVED_AT = OffsetDateTime.parse("2026-10-22T14:00:00+09:00");

    @Autowired
    private JdbcClient jdbcClient;

    private long routeVersionId;
    private long batchId;

    @BeforeEach
    void 회차_노선의_기점과_수집_묶음을_먼저_저장한다() {
        final long routeId = jdbcClient.sql("""
                INSERT INTO route (
                    public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name
                ) VALUES (?, 'GBIS', ?, '3330', '도촌동9단지앞', '안양역')
                RETURNING id
                """)
            .params(ROUTE_204000057, ROUTE_204000057)
            .query(Long.class)
            .single();
        routeVersionId = jdbcClient.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from, turn_sequence)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """)
            .params(routeId, "0".repeat(64), OBSERVED_AT, TURN_SEQUENCE)
            .query(Long.class)
            .single();
        jdbcClient.sql("""
                INSERT INTO route_stop (
                    route_version_id, stop_order, stop_id, name, direction, boarding_allowed
                ) VALUES (?, ?, ?, '도촌동9단지앞', 'UP', true)
                """)
            .params(routeVersionId, FIRST_STOP, STOP_205000227)
            .update();
        batchId = jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key,
                    requested_at, response_received_at, outcome, normalization_version,
                    collection_strategy_version
                ) VALUES (?, ?, 1, '204000057-2026-10-22T14:00', ?, ?, 'SUCCESS_ROWS',
                    'normalization-v1.0.0', 'adaptive-kst-v1.0.1')
                RETURNING id
                """)
            .params(routeVersionId, OBSERVED_AT, OBSERVED_AT, OBSERVED_AT)
            .query(Long.class)
            .single();
    }

    @ParameterizedTest
    @CsvSource({"0, 1, 0", "1, 0, 1", "2, 1, 0"})
    void 기점에서는_정류소_도착만_이전_편도_방향으로_판정한다(final int runningState, final int passedStopOrder, final long direction) {
        // given
        jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id, passed_stop_order,
                    running_state, remaining_seats, crowd_level
                ) VALUES (?, ?, 0, '204000206', ?, ?, ?, ?, 40, 1)
                """)
            .params(batchId, routeVersionId, FIRST_STOP, STOP_205000227, passedStopOrder, runningState)
            .update();

        // when
        final long actual = jdbcClient.sql(
            "SELECT quality_direction FROM forecast_observation_quality WHERE observation_batch_id = ?")
            .param(batchId)
            .query(Long.class)
            .single();

        // then
        assertThat(actual).isEqualTo(direction);
    }
}

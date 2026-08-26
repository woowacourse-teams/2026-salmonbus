package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.support.IntegrationTest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class VehicleObservationTableTest {

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String STOP_205000217 = "205000217";
    private static final int STOP_ORDER = 6;
    private static final String VEHICLE_204000206 = "204000206";
    private static final String VEHICLE_204003542 = "204003542";
    private static final long MISSING_BATCH_ID = 9_999_999L;
    private static final String CONTENT_DIGEST = "0".repeat(64);
    private static final OffsetDateTime OBSERVED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final LocalDateTime SEOUL_WALL_CLOCK = LocalDateTime.of(2026, 8, 19, 11, 14, 4, 911_000_000);

    @Autowired
    private JdbcClient jdbcClient;

    private long routeVersionId;
    private long batchId;

    @BeforeEach
    void 노선_판본과_정류소와_수집_묶음을_먼저_저장한다() {
        final long routeId = insertRoute();
        routeVersionId = insertRouteVersion(routeId);
        insertRouteStop(routeVersionId);
        batchId = insertObservationBatch(routeVersionId);
    }

    @Test
    void 차량_관측은_수집_묶음이_먼저_있어야_저장된다() {
        // when & then
        assertThatThrownBy(() -> insertObservation(MISSING_BATCH_ID, VEHICLE_204000206, 0, OBSERVED_AT))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 수집이_재시도되어도_같은_묶음의_같은_버스는_한_번만_저장된다() {
        // given
        insertObservation(batchId, VEHICLE_204000206, 0, OBSERVED_AT);

        // when & then
        assertThatThrownBy(() -> insertObservation(batchId, VEHICLE_204000206, 1, OBSERVED_AT))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void KST로_넣든_UTC로_넣든_한국_시간으로_조회할_수_있다() {
        // given
        final OffsetDateTime inKst = OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
        final OffsetDateTime inUtc = OffsetDateTime.parse("2026-08-19T02:14:04.911Z");

        // when
        insertObservation(batchId, VEHICLE_204000206, 0, inKst);
        insertObservation(batchId, VEHICLE_204003542, 1, inUtc);
        final List<LocalDateTime> actual = jdbcClient
            .sql("""
                SELECT observed_at AT TIME ZONE 'Asia/Seoul'
                FROM vehicle_observation
                WHERE observation_batch_id = ?
                ORDER BY source_row_number
                """)
            .param(batchId)
            .query(LocalDateTime.class)
            .list();

        // then
        assertThat(actual).containsExactly(SEOUL_WALL_CLOCK, SEOUL_WALL_CLOCK);
    }

    private long insertRoute() {
        return jdbcClient.sql("""
                INSERT INTO route (
                    public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(ROUTE_204000057, SOURCE_ID, ROUTE_204000057, "3330", "범계역", "강남역")
            .query(Long.class)
            .single();
    }

    private long insertRouteVersion(
        final long routeId
    ) {
        return jdbcClient.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from)
                VALUES (?, ?, ?)
                RETURNING id
                """)
            .params(routeId, CONTENT_DIGEST, OBSERVED_AT)
            .query(Long.class)
            .single();
    }

    private void insertRouteStop(
        final long versionId
    ) {
        jdbcClient.sql("""
                INSERT INTO route_stop (
                    route_version_id, stop_order, stop_id, name, direction, boarding_allowed
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)
            .params(versionId, STOP_ORDER, STOP_205000217, "범계역", "UP", true)
            .update();
    }

    private long insertObservationBatch(
        final long versionId
    ) {
        return jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key, requested_at, outcome
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(versionId, OBSERVED_AT, 1, "204000057-2026-08-19T11:14", OBSERVED_AT, "SUCCESS_ROWS")
            .query(Long.class)
            .single();
    }

    private void insertObservation(
        final long targetBatchId,
        final String vehicleId,
        final int sourceRowNumber,
        final OffsetDateTime observedAt
    ) {
        jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    observed_at, vehicle_id, stop_order, stop_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
            .params(
                targetBatchId, routeVersionId, sourceRowNumber,
                observedAt, vehicleId, STOP_ORDER, STOP_205000217
            )
            .update();
    }
}

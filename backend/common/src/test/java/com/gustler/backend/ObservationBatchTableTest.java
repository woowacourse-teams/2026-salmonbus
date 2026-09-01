package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.support.IntegrationTest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class ObservationBatchTableTest {

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String CONTENT_DIGEST = "0".repeat(64);
    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0";
    private static final String STRATEGY_VERSION = "adaptive-kst-v1.0.1";
    private static final OffsetDateTime SCHEDULED_AT = OffsetDateTime.parse("2026-08-19T11:14:00+09:00");
    private static final String ATTEMPT_KEY = "204000057-2026-08-19T11:14";

    @Autowired
    private JdbcClient jdbcClient;

    private long routeVersionId;

    @BeforeEach
    void 노선과_판본을_먼저_저장한다() {
        routeVersionId = insertRouteVersion(insertRoute());
    }

    @Test
    void 자리만_잡고_아직_안_보낸_판은_보낸_시각_없이_저장된다() {
        // when
        insertBatch("RESERVED", null, null);

        // then
        assertThat(storedBatchCount()).isEqualTo(1);
    }

    @Test
    void 같은_시도_키의_판은_두_번_저장되지_않는다() {
        // given
        insertBatch("RESERVED", null, null);

        // when & then
        assertThatThrownBy(() -> insertBatch("RESERVED", null, null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 뜻을_모르는_결말의_판은_저장되지_않는다() {
        // when & then
        assertThatThrownBy(() -> insertBatch("TIMED_OUT", SCHEDULED_AT, null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 뜻을_모르는_실패_사유의_판은_저장되지_않는다() {
        // when & then
        assertThatThrownBy(() -> insertBatch("FAILED_UPSTREAM", SCHEDULED_AT, "CONNECTION_RESET"))
            .isInstanceOf(DataIntegrityViolationException.class);
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
            .params(routeId, CONTENT_DIGEST, SCHEDULED_AT)
            .query(Long.class)
            .single();
    }

    private void insertBatch(
        String outcome,
        OffsetDateTime requestedAt,
        String failureCode
    ) {
        jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key,
                    requested_at, outcome, failure_code, normalization_version,
                    collection_strategy_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(
                routeVersionId, SCHEDULED_AT, 1, ATTEMPT_KEY,
                requestedAt, outcome, failureCode, NORMALIZATION_VERSION,
                STRATEGY_VERSION
            )
            .update();
    }

    private int storedBatchCount() {
        return jdbcClient.sql("SELECT count(*) FROM observation_batch WHERE route_version_id = ?")
            .param(routeVersionId)
            .query(Integer.class)
            .single();
    }
}

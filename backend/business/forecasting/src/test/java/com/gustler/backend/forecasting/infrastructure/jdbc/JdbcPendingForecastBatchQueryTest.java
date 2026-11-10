package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import com.gustler.backend.support.ConfirmedTripFixture;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@ForecastingIntegrationTest
@Transactional
class JdbcPendingForecastBatchQueryTest {
    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String ROUTE_204000121 = "204000121";
    private static final String CONTENT_DIGEST = "0".repeat(64);
    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0";
    private static final String STRATEGY_VERSION = "adaptive-kst-v1.0.1";

    private static final String REPORTED_UNKNOWN = "REPORTED_UNKNOWN";

    private static final String SUCCESS_ROWS = "SUCCESS_ROWS";
    private static final String SUCCESS_EMPTY = "SUCCESS_EMPTY";

    private static final String VEHICLE_204000206 = "204000206";
    private static final String VEHICLE_204003542 = "204003542";
    private static final String VEHICLE_204001188 = "204001188";

    private static final OffsetDateTime EARLIER_POLL = OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final OffsetDateTime LATER_POLL = OffsetDateTime.parse("2026-08-19T11:14:19.911+09:00");

    private static final int RUNNING_STATE_DEPARTED = 2;
    private static final int CROWD_LEVEL_3 = 3;
    private static final int NO_SEAT_LEFT = 0;
    private static final int STOP_5 = 5;
    private static final int STOP_6 = 6;
    private static final int HIGHEST_STOP_ORDER = 10;
    private static final int ENOUGH_BATCHES = 10;

    /** 신선도 창을 안 보는 테스트가 쓰는 한계. 픽스처의 판이 전부 이보다 뒤다. */
    private static final Instant ANY_AGE = Instant.parse("2000-01-01T00:00:00Z");
    private static final long MISSING_BATCH_ID = 9_999_999L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JdbcPendingForecastBatchQuery repository;

    private long routeVersionId;

    /** 시도 키는 판마다 달라야 한다. ux_batch_attempt 가 판본 안에서 그것을 막는다. */
    private int insertedBatchCount;

    @BeforeEach
    void 노선_판본과_경유_정류소를_먼저_저장한다() {
        insertedBatchCount = 0;
        routeVersionId = insertRouteVersion(ROUTE_204000057);
    }

    @Test
    void 한계보다_오래된_판은_안_준다() {
        // given
        insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);

        // when
        List<PendingForecastBatch> actual = repository.findBatchesAwaitingForecast(routeVersionId,
            LATER_POLL.toInstant(), ENOUGH_BATCHES);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 한계와_같은_시각의_판은_준다() {
        // given
        final long onTheEdge = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);

        // when
        List<PendingForecastBatch> actual = repository.findBatchesAwaitingForecast(routeVersionId,
            LATER_POLL.toInstant(), ENOUGH_BATCHES);

        // then
        assertThat(actual).extracting(PendingForecastBatch::observationBatchId).containsExactly(onTheEdge);
    }

    @Test
    void 창_밖으로_막_밀려난_판을_준다() {
        // given
        final long left = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);

        // when
        Optional<Instant> actual = repository.findOldestLeftBehindAt(routeVersionId,
            EARLIER_POLL.minusHours(1).toInstant(), LATER_POLL.toInstant());

        // then
        assertThat(actual).contains(readResponseReceivedAt(left));
    }

    /** 이관한 관측이다. 예보 대상이 아니므로 완료 표시가 남지 않는다. */
    @Test
    void 거슬러_보는_폭보다_오래된_판은_안_준다() {
        // given
        insertBatch(routeVersionId, EARLIER_POLL.minusDays(1), SUCCESS_ROWS, null);

        // when
        Optional<Instant> actual = repository.findOldestLeftBehindAt(routeVersionId,
            EARLIER_POLL.minusHours(1).toInstant(), LATER_POLL.toInstant());

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 아직_창_안에_있는_판은_안_준다() {
        // given
        insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);

        // when
        Optional<Instant> actual = repository.findOldestLeftBehindAt(routeVersionId,
            EARLIER_POLL.minusHours(1).toInstant(), LATER_POLL.toInstant());

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 예보가_끝난_판은_밀려난_것으로_안_센다() {
        // given
        insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, LATER_POLL);

        // when
        Optional<Instant> actual = repository.findOldestLeftBehindAt(routeVersionId,
            EARLIER_POLL.minusHours(1).toInstant(), LATER_POLL.toInstant());

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 예보가_안_붙은_판을_오래된_것부터_준다() {
        // given
        final long later = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        final long earlier = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);

        // when
        List<PendingForecastBatch> actual = repository.findBatchesAwaitingForecast(routeVersionId, ANY_AGE,
            ENOUGH_BATCHES);

        // then
        assertThat(actual).extracting(PendingForecastBatch::observationBatchId).containsExactly(earlier, later);
    }

    @Test
    void 예보가_끝난_판은_대상에서_빠진다() {
        // given
        final long awaiting = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, LATER_POLL);

        // when
        List<PendingForecastBatch> actual = repository.findBatchesAwaitingForecast(routeVersionId, ANY_AGE,
            ENOUGH_BATCHES);

        // then
        assertThat(actual).extracting(PendingForecastBatch::observationBatchId).containsExactly(awaiting);
    }

    @Test
    void 차가_한_대도_없던_판도_예보_대상으로_준다() {
        // given
        final long empty = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_EMPTY, null);

        // when
        List<PendingForecastBatch> actual = repository.findBatchesAwaitingForecast(routeVersionId, ANY_AGE,
            ENOUGH_BATCHES);

        // then
        assertThat(actual).extracting(PendingForecastBatch::observationBatchId).containsExactly(empty);
    }

    @Test
    void 응답을_못_받은_판은_예보_대상이_아니다() {
        // given
        insertBatch(routeVersionId, null, SUCCESS_ROWS, null);

        // when
        List<PendingForecastBatch> actual = repository.findBatchesAwaitingForecast(routeVersionId, ANY_AGE,
            ENOUGH_BATCHES);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 다른_노선_판본의_판은_예보_대상에_섞이지_않는다() {
        // given
        insertBatch(insertRouteVersion(ROUTE_204000121), EARLIER_POLL, SUCCESS_ROWS, null);

        // when
        List<PendingForecastBatch> actual = repository.findBatchesAwaitingForecast(routeVersionId, ANY_AGE,
            ENOUGH_BATCHES);

        // then
        assertThat(actual).isEmpty();
    }

    private long insertRouteVersion(String sourceRouteId) {
        final long routeId = jdbcClient.sql("""
            INSERT INTO route (
                public_route_id, source_id, source_route_id,
                display_name, start_stop_name, end_stop_name
            ) VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id
            """).params(sourceRouteId, SOURCE_ID, sourceRouteId, "3330", "범계역", "강남역").query(Long.class).single();

        final long versionId = jdbcClient.sql("""
            INSERT INTO route_version (route_id, content_digest, valid_from)
            VALUES (?, ?, ?)
            RETURNING id
            """).params(routeId, CONTENT_DIGEST, EARLIER_POLL).query(Long.class).single();

        for (int stopOrder = 1; stopOrder <= HIGHEST_STOP_ORDER; stopOrder++) {
            jdbcClient.sql("""
                INSERT INTO route_stop (
                    route_version_id, stop_order, stop_id, name, direction, boarding_allowed
                ) VALUES (?, ?, ?, ?, ?, ?)
                """).params(versionId, stopOrder, stopIdOf(stopOrder), "범계역", "UP", true).update();
        }
        return versionId;
    }

    private long insertBatch(
        final long versionId,
        OffsetDateTime responseReceivedAt,
        String outcome,
        OffsetDateTime publishedAt
    ) {
        final long batchId = jdbcClient.sql("""
            INSERT INTO observation_batch (
                route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                response_received_at, input_confirmed_at, outcome, normalization_version,
                collection_strategy_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """)
            .params(versionId, EARLIER_POLL, 1, "%s-%d".formatted(ROUTE_204000057, insertedBatchCount++), EARLIER_POLL,
                responseReceivedAt, publishedAt, outcome, NORMALIZATION_VERSION, STRATEGY_VERSION)
            .query(Long.class).single();
        if (publishedAt != null) {
            jdbcClient.sql("""
                INSERT INTO forecast_publication (
                    source_batch_id, route_version_id, observed_at,
                    published_at, prediction_count, provenance
                ) VALUES (?, ?, ?, ?, 0, 'LEGACY_UNKNOWN')
                """).params(batchId, versionId, responseReceivedAt, publishedAt).update();
        }
        return batchId;
    }

    private long insertObservation(
        final long batchId,
        String vehicleId,
        final int stopOrder,
        Integer remainingSeats
    ) {
        return insertObservation(routeVersionId, batchId, vehicleId, stopOrder, remainingSeats, null);
    }

    private void insertObservationWithoutSeats(
        final long batchId,
        String vehicleId,
        final int stopOrder,
        String seatUnknownReason
    ) {
        insertObservation(routeVersionId, batchId, vehicleId, stopOrder, null, seatUnknownReason);
    }

    private long insertObservation(
        final long versionId,
        final long batchId,
        String vehicleId,
        final int stopOrder,
        Integer remainingSeats
    ) {
        return insertObservation(versionId, batchId, vehicleId, stopOrder, remainingSeats, null);
    }

    /** 통과 순번은 지나감(2)이라 상류 순번과 같다. 관측 시각 열은 SAL-84 에서 제거했다. */
    private long insertObservation(
        final long versionId,
        final long batchId,
        String vehicleId,
        final int stopOrder,
        Integer remainingSeats,
        String seatUnknownReason
    ) {
        final long inserted = ConfirmedTripFixture.include(jdbcClient,
            jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id, passed_stop_order,
                    running_state, remaining_seats, seat_unknown_reason, crowd_level
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
                .params(batchId, versionId, nextRowNumber(batchId), vehicleId, stopOrder, stopIdOf(stopOrder),
                    stopOrder, RUNNING_STATE_DEPARTED, remainingSeats, seatUnknownReason, CROWD_LEVEL_3)
                .query(Long.class).single());
        return inserted;
    }

    private int nextRowNumber(final long batchId) {
        return jdbcClient.sql("SELECT count(*) FROM vehicle_observation WHERE observation_batch_id = ?").param(batchId)
            .query(Integer.class).single();
    }

    private Instant readResponseReceivedAt(final long observationBatchId) {
        return jdbcClient.sql("SELECT response_received_at FROM observation_batch WHERE id = ?")
            .param(observationBatchId).query(OffsetDateTime.class).single().toInstant();
    }

    private static String stopIdOf(final int stopOrder) {
        return "20500021%d".formatted(stopOrder);
    }
}

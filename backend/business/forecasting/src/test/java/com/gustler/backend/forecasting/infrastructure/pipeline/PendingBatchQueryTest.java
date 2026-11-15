package com.gustler.backend.forecasting.infrastructure.pipeline;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.application.pipeline.PendingPipelineBatches;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** 예보가 처리 순서를 맡기는 처리하지 않은 배치 조회를 실제 DB로 본다. */
@ForecastingIntegrationTest
@Transactional
class PendingBatchQueryTest {
    private static final Instant NOW = Instant.parse("2030-03-01T03:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final int ROUTES = 20;
    private static final int BATCHES_PER_ROUTE = 2_000;

    @Autowired
    JdbcClient jdbc;

    @Test
    void 처리하지_않은_성공_배치만_넣은_순서와_무관하게_수신_순서로_한도까지_읽는다() {
        // given
        final long version = routeVersion();
        final long other = routeVersion();
        final long third = batch(version, NOW.minusSeconds(30), "SUCCESS_ROWS");
        final long first = batch(version, NOW.minusSeconds(90), "SUCCESS_EMPTY");
        final long second = batch(version, NOW.minusSeconds(60), "SUCCESS_ROWS");
        final long processed = batch(version, NOW.minusSeconds(120), "SUCCESS_ROWS");
        run(processed, version, NOW.minusSeconds(120));
        final long confirmedInput = batch(version, NOW.minusSeconds(150), "SUCCESS_ROWS");
        jdbc.sql("UPDATE observation_batch SET input_confirmed_at=response_received_at WHERE id=?")
            .param(confirmedInput).update();
        batch(version, NOW.minusSeconds(45), "FAILED_UPSTREAM");
        batch(version, NOW.minus(WINDOW).minusSeconds(1), "SUCCESS_ROWS");
        batch(other, NOW.minusSeconds(75), "SUCCESS_ROWS");
        PendingPipelineBatches pending = new JdbcPendingPipelineBatches(jdbc);

        // when
        List<PendingPipelineBatches.Pending> all = pending.find(version, NOW.minus(WINDOW), 20);
        List<PendingPipelineBatches.Pending> limited = pending.find(version, NOW.minus(WINDOW), 2);

        // then
        assertSoftly(softly -> {
            softly.assertThat(all).extracting(PendingPipelineBatches.Pending::observationBatchId)
                .containsExactly(first, second, third);
            softly.assertThat(all).extracting(PendingPipelineBatches.Pending::receivedAt)
                .containsExactly(NOW.minusSeconds(90), NOW.minusSeconds(60), NOW.minusSeconds(30));
            softly.assertThat(limited).extracting(PendingPipelineBatches.Pending::observationBatchId)
                .containsExactly(first, second);
        });
    }

    @Test
    void 수신_시각이_같은_배치는_배치_번호_순으로_읽는다() {
        // given
        final long version = routeVersion();
        final long lower = batch(version, NOW.minusSeconds(30), "SUCCESS_ROWS");
        final long higher = batch(version, NOW.minusSeconds(30), "SUCCESS_ROWS");

        // when
        List<PendingPipelineBatches.Pending> found = new JdbcPendingPipelineBatches(jdbc)
            .find(version, NOW.minus(WINDOW), 20);

        // then
        assertSoftly(softly -> softly.assertThat(found)
            .extracting(PendingPipelineBatches.Pending::observationBatchId).containsExactly(lower, higher));
    }

    @Test
    void 노선_판본_하나의_신선도_창을_읽는_조회는_수신_시각_인덱스를_타고_정렬을_따로_하지_않는다() {
        // given
        long version = 0;
        for (int route = 0; route < ROUTES; route++) {
            version = routeVersion();
            jdbc.sql("""
                INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,
                    response_received_at,outcome,stored_rows,normalization_version,collection_strategy_version,
                    ingestion_origin)
                SELECT :version, at, 1, 'plan-' || step, at, at, 'SUCCESS_ROWS', 1, 'normalization-v1.0.0',
                    'adaptive-kst-v1.0.1', 'LIVE'
                FROM (SELECT step, CAST(:now AS timestamptz) - make_interval(secs => step * 30) AS at
                    FROM generate_series(0, :batches - 1) step) steps
                """).param("version", version).param("now", NOW.atOffset(ZoneOffset.UTC))
                .param("batches", BATCHES_PER_ROUTE).update();
        }
        jdbc.sql("""
            INSERT INTO forecast_run(source_date,observation_batch_id,route_id,route_version_id,received_at,
                confirmed_at,outcome,prediction_count)
            SELECT (b.scheduled_at AT TIME ZONE 'Asia/Seoul')::date, b.id, v.route_id, b.route_version_id,
                b.response_received_at, b.response_received_at, 'EMPTY', 0
            FROM observation_batch b JOIN route_version v ON v.id=b.route_version_id
            WHERE b.attempt_key LIKE 'plan-%' AND b.response_received_at < :recent
            """).param("recent", NOW.minusSeconds(60).atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("ANALYZE observation_batch").update();
        jdbc.sql("ANALYZE forecast_run").update();

        // when
        List<String> plan = jdbc.sql("EXPLAIN " + JdbcPendingPipelineBatches.FIND)
            .param("routeVersionId", version)
            .param("notBefore", NOW.minus(WINDOW).atOffset(ZoneOffset.UTC))
            .param("limit", 20)
            .query(String.class)
            .list();
        List<PendingPipelineBatches.Pending> found = new JdbcPendingPipelineBatches(jdbc)
            .find(version, NOW.minus(WINDOW), 20);

        // then
        String joined = String.join("\n", plan);
        assertSoftly(softly -> {
            softly.assertThat(joined).contains("ix_batch_forecast_candidates");
            softly.assertThat(joined).doesNotContain("Seq Scan on observation_batch");
            softly.assertThat(joined).doesNotContain("Sort");
            softly.assertThat(found).hasSize(3);
        });
    }

    private long routeVersion() {
        String key = "pending-" + UUID.randomUUID().toString().substring(0, 12);
        final long routeId = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES(?,'GBIS',?,?,'A','B') RETURNING id
            """).params(key, key, key).query(Long.class).single();
        return jdbc.sql("""
            INSERT INTO route_version(route_id,content_digest,valid_from) VALUES(?,?,?) RETURNING id
            """).params(routeId, "0".repeat(64), NOW.minus(Duration.ofDays(30)).atOffset(ZoneOffset.UTC))
            .query(Long.class).single();
    }

    private long batch(final long version, Instant receivedAt, String outcome) {
        return jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,
                response_received_at,outcome,failure_code,stored_rows,normalization_version,
                collection_strategy_version,ingestion_origin)
            VALUES(?,?,1,?,?,?,?,?,?,'normalization-v1.0.0','adaptive-kst-v1.0.1','LIVE') RETURNING id
            """).params(version, receivedAt.atOffset(ZoneOffset.UTC), UUID.randomUUID().toString(),
                receivedAt.atOffset(ZoneOffset.UTC), receivedAt.atOffset(ZoneOffset.UTC), outcome,
                failureCode(outcome), storedRows(outcome))
            .query(Long.class).single();
    }

    private static String failureCode(String outcome) {
        if (outcome.startsWith("FAILED")) {
            return "UPSTREAM_ERROR";
        }
        return null;
    }

    private static Integer storedRows(String outcome) {
        if ("SUCCESS_ROWS".equals(outcome)) {
            return 1;
        }
        return null;
    }

    private void run(final long batchId, final long version, Instant receivedAt) {
        jdbc.sql("""
            INSERT INTO forecast_run(source_date,observation_batch_id,route_id,route_version_id,received_at,
                confirmed_at,outcome,prediction_count)
            SELECT (b.scheduled_at AT TIME ZONE 'Asia/Seoul')::date, b.id, v.route_id, b.route_version_id, ?, ?,
                'EMPTY', 0
            FROM observation_batch b JOIN route_version v ON v.id=b.route_version_id WHERE b.id=? AND v.id=?
            """).params(receivedAt.atOffset(ZoneOffset.UTC), receivedAt.atOffset(ZoneOffset.UTC), batchId, version)
            .update();
    }
}

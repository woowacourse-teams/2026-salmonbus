package com.gustler.backend.forecasting.infrastructure.pipeline;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.application.pipeline.PendingPipelineBatches;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final Pattern RELATION_READ =
        Pattern.compile("(Seq Scan|Index Scan|Index Only Scan|Bitmap Heap Scan)(?: using \\S+)? on (\\S+)");

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
    void 노선_판본_하나의_신선도_창을_읽는_조회는_줄이_있는_테이블을_노선_판본과_수신_시각의_인덱스_조건으로_읽는다() {
        // given
        final long version = manyBatchesPerRoute();

        // when
        List<String> plan = explainFind(version);
        List<PendingPipelineBatches.Pending> found = new JdbcPendingPipelineBatches(jdbc)
            .find(version, NOW.minus(WINDOW), 20);

        // then
        Map<String, String> reads = reads(plan);
        assertSoftly(softly -> {
            softly.assertThat(relationsHoldingPreparedRows()).as(String.join("\n", plan)).isNotEmpty()
                .allMatch(relation -> isBoundedByVersionAndReceivedAt(reads.get(relation)));
            softly.assertThat(found).hasSize(3);
        });
    }

    @Test
    void 노선_판본과_수신_시각의_인덱스가_없으면_줄이_있는_테이블을_그_조건_없이_읽어_같은_판정에_걸린다() {
        // given
        final long version = manyBatchesPerRoute();
        jdbc.sql("DROP INDEX IF EXISTS ix_batch_forecast_candidates, ix_batch_recent_history").update();

        // when
        List<String> plan = explainFind(version);

        // then
        Map<String, String> reads = reads(plan);
        assertSoftly(softly -> softly.assertThat(relationsHoldingPreparedRows()).as(String.join("\n", plan))
            .isNotEmpty()
            .noneMatch(relation -> isBoundedByVersionAndReceivedAt(reads.get(relation))));
    }

    /**
     * 노선 판본 여럿에 배치를 넣고 최근 것 셋만 처리하지 않은 채로 둔 뒤 통계를 모은다. 줄은 넣는 날의 테이블(날짜 파티션
     * 테이블이면 그날의 파티션 하나)에 들어간다.
     *
     * @return 마지막으로 만든 노선 판본
     */
    private long manyBatchesPerRoute() {
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
        return version;
    }

    private List<String> explainFind(final long version) {
        return jdbc.sql("EXPLAIN " + JdbcPendingPipelineBatches.FIND)
            .param("routeVersionId", version)
            .param("notBefore", NOW.minus(WINDOW).atOffset(ZoneOffset.UTC))
            .param("limit", 20)
            .query(String.class)
            .list();
    }

    /** 준비한 줄이 든 관계 이름이다. 일반 테이블이면 그 테이블, 날짜 파티션 테이블이면 줄이 들어간 파티션이다. */
    private List<String> relationsHoldingPreparedRows() {
        return jdbc.sql("""
            SELECT DISTINCT tableoid::regclass::text FROM observation_batch WHERE attempt_key LIKE 'plan-%'
            """).query(String.class).list();
    }

    /**
     * 계획이 관계마다 어떻게 읽는지를 모은다. 값은 읽는 노드의 종류이고, 그 노드에 인덱스 조건이 있으면 뒤에 붙인다.
     * 날짜 파티션 테이블에서는 줄이 없는 파티션을 통째로 읽는 노드와, 파티션들을 모은 위의 정렬이 함께 나온다.
     */
    private static Map<String, String> reads(List<String> plan) {
        Map<String, String> reads = new HashMap<>();
        String relation = null;
        for (String line : plan) {
            Matcher read = RELATION_READ.matcher(line);
            if (read.find()) {
                relation = read.group(2);
                reads.put(relation, read.group(1));
                continue;
            }
            if (relation != null && line.contains("Index Cond:")) {
                reads.merge(relation, line.strip(), (node, condition) -> node + " " + condition);
            }
        }
        return reads;
    }

    /** 관계를 인덱스로 읽고 그 인덱스 조건이 노선 판본과 수신 시각 하한을 모두 담는지다. 관계를 통째로 읽으면 거짓이다. */
    private static boolean isBoundedByVersionAndReceivedAt(String read) {
        if (read == null || !read.startsWith("Index Scan")) {
            return false;
        }
        return read.contains("route_version_id = ") && read.contains("response_received_at >= ");
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

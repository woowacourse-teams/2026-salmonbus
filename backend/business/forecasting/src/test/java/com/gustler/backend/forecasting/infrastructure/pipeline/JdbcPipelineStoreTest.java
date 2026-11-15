package com.gustler.backend.forecasting.infrastructure.pipeline;

import static com.gustler.backend.forecasting.support.PipelineSamples.ROUTE_ID;
import static com.gustler.backend.forecasting.support.PipelineSamples.ROUTE_VERSION_ID;
import static com.gustler.backend.forecasting.support.PipelineSamples.pass;
import static com.gustler.backend.forecasting.support.PipelineSamples.published;
import static com.gustler.backend.forecasting.support.PipelineSamples.skipped;
import static com.gustler.backend.forecasting.support.PipelineSamples.vehicle;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.pipeline.ForecastOutcome;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.application.pipeline.ForecastRun;
import com.gustler.backend.forecasting.application.pipeline.PassLabel;
import com.gustler.backend.forecasting.application.pipeline.UnresolvedForecast;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@ForecastingIntegrationTest
@Transactional
class JdbcPipelineStoreTest {
    private static final LocalDate DAY = LocalDate.of(2030, 1, 1);
    private static final Instant RECEIVED_AT = Instant.parse("2030-01-01T00:00:10Z");

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JdbcForecastRecordStore records;

    @Autowired
    private JdbcStopPassStore passes;

    @Autowired
    private JdbcPassLabelStore labels;

    @Test
    void 실행_한_줄과_버스별_기록을_넣고_배치_안_줄_번호_순으로_읽는다() {
        // given
        ForecastPublished published = published(DAY, 11, RECEIVED_AT,
            List.of(vehicle(1003, 3), vehicle(1001, 1), vehicle(1002, 2)));

        // when
        final boolean inserted = records.save(published);

        // then
        assertSoftly(softly -> {
            softly.assertThat(inserted).isTrue();
            softly.assertThat(count("forecast_run")).isOne();
            softly.assertThat(records.findRecords(DAY, 11))
                .extracting(ForecastPublished.VehicleForecast::vehicleObservationId)
                .containsExactly(1001L, 1002L, 1003L);
            softly.assertThat(jdbcClient.sql("SELECT prediction_count FROM forecast_run").query(Integer.class).single())
                .isEqualTo(9);
        });
    }

    @Test
    void 같은_배치를_다시_저장하면_건너뛰고_줄이_늘지_않는다() {
        // given
        ForecastPublished published = published(DAY, 11, RECEIVED_AT, List.of(vehicle(1001, 1), vehicle(1002, 2)));
        records.save(published);

        // when
        final boolean inserted = records.save(published);

        // then
        assertSoftly(softly -> {
            softly.assertThat(inserted).isFalse();
            softly.assertThat(count("forecast_run")).isOne();
            softly.assertThat(count("forecast_record")).isEqualTo(2);
        });
    }

    @Test
    void 배열의_값과_순서와_실수_비트를_그대로_돌려준다() {
        // given
        double[] seats = {0.1, 1.0 / 3.0, Math.nextUp(1.0), -0.0, Double.MIN_VALUE, 1.0e300};
        double[] probabilities = {Double.MIN_NORMAL, 0.9999999999999999, 0.5, 0.0, 1.0 / 7.0, 2.5e-17};
        int[] targets = {12, 3, 7, 1, 30, 2};
        ForecastPublished.VehicleForecast vehicle = new ForecastPublished.VehicleForecast(1001, 1, "bus-1001", 2,
            900, 950, true, 49, true, targets, seats, probabilities);
        records.save(published(DAY, 11, RECEIVED_AT, List.of(vehicle)));

        // when
        ForecastPublished.VehicleForecast actual = records.findRecords(DAY, 11).getFirst();

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isEqualTo(vehicle);
            softly.assertThat(actual.targetStopOrders()).containsExactly(targets);
            softly.assertThat(rawBits(actual.expectedSeats())).containsExactly(rawBits(seats));
            softly.assertThat(rawBits(actual.fullProbabilities())).containsExactly(rawBits(probabilities));
        });
    }

    @Test
    void 날짜_파티션이_있으면_그_파티션에_없으면_기본_파티션에_들어간다() {
        // given
        jdbcClient.sql("""
            CREATE TABLE forecast_record_p20300101 PARTITION OF forecast_record
            FOR VALUES FROM ('2030-01-01') TO ('2030-01-02')
            """).update();

        // when
        records.save(published(DAY, 11, RECEIVED_AT, List.of(vehicle(1001, 1))));
        records.save(published(DAY.plusDays(1), 12, RECEIVED_AT.plusSeconds(86_400), List.of(vehicle(1002, 1))));

        // then
        assertSoftly(softly -> {
            softly.assertThat(jdbcClient.sql("""
                SELECT tableoid::regclass::text FROM forecast_record ORDER BY vehicle_observation_id
                """).query(String.class).list())
                .containsExactly("forecast_record_p20300101", "forecast_record_default");
            softly.assertThat(jdbcClient.sql("SELECT tableoid::regclass::text FROM forecast_run")
                .query(String.class).list()).containsOnly("forecast_run_default");
        });
    }

    @Test
    void 노선_판본의_최신_실행을_고른_처리_결과_안에서_찾는다() {
        // given
        records.save(published(DAY, 11, RECEIVED_AT, List.of(vehicle(1001, 1))));
        records.save(published(DAY, 12, RECEIVED_AT.plusSeconds(30), List.of()));
        records.save(skipped(DAY, 13, RECEIVED_AT.plusSeconds(60), ForecastOutcome.LATE));

        // when
        Optional<ForecastRun> latest = records.findLatestRun(ROUTE_VERSION_ID, Set.of(ForecastOutcome.values()));
        Optional<ForecastRun> published = records.findLatestRun(ROUTE_VERSION_ID, Set.of(ForecastOutcome.PUBLISHED));
        Optional<ForecastRun> other = records.findLatestRun(ROUTE_VERSION_ID + 1, Set.of(ForecastOutcome.values()));

        // then
        assertSoftly(softly -> {
            softly.assertThat(latest).map(ForecastRun::observationBatchId).contains(13L);
            softly.assertThat(latest).map(ForecastRun::modelDeploymentId).isEmpty();
            softly.assertThat(published).map(ForecastRun::observationBatchId).contains(11L);
            softly.assertThat(other).isEmpty();
        });
    }

    @Test
    void 노선의_실행을_수신_시각_범위로_사건_순서대로_되살린다() {
        // given
        ForecastPublished second = published(DAY, 12, RECEIVED_AT.plusSeconds(30),
            List.of(vehicle(1012, 2), vehicle(1011, 1)));
        ForecastPublished first = skipped(DAY, 11, RECEIVED_AT, ForecastOutcome.OUT_OF_SCOPE);
        ForecastPublished outside = published(DAY, 13, RECEIVED_AT.plusSeconds(60), List.of(vehicle(1013, 1)));
        records.save(second);
        records.save(outside);
        records.save(first);

        // when
        List<ForecastPublished> actual = records.findPublishedBetween(ROUTE_ID, RECEIVED_AT,
            RECEIVED_AT.plusSeconds(60));

        // then
        assertThat(actual).containsExactly(first, published(DAY, 12, RECEIVED_AT.plusSeconds(30),
            List.of(vehicle(1011, 1), vehicle(1012, 2))));
    }

    @Test
    void 통과를_여러_줄_넣고_닫힌_순서대로_읽으며_다시_넣으면_건너뛴다() {
        // given
        PassClosed late = pass(DAY, 500, 3, RECEIVED_AT.plusSeconds(60));
        PassClosed early = pass(DAY, 600, 2, RECEIVED_AT);
        PassClosed open = new PassClosed(DAY, ROUTE_ID, 700, 1, ROUTE_VERSION_ID, "bus-700", 701, DAY, 710, 0,
            RECEIVED_AT, RECEIVED_AT, 711, RECEIVED_AT, 712, 0, RECEIVED_AT, RECEIVED_AT, null, 713, 714,
            RECEIVED_AT.plusSeconds(30));

        // when
        final int inserted = passes.save(List.of(late, early, open));
        final int again = passes.save(List.of(early));

        // then
        assertSoftly(softly -> {
            softly.assertThat(inserted).isEqualTo(3);
            softly.assertThat(again).isZero();
            softly.assertThat(passes.findClosedBetween(ROUTE_ID, RECEIVED_AT, RECEIVED_AT.plusSeconds(61)))
                .containsExactly(early, open, late);
        });
    }

    @Test
    void 한_문장의_줄_수를_넘는_묶음도_나눠서_모두_넣는다() {
        // given
        List<PassClosed> many = IntStream.rangeClosed(1, MultiRowInsert.ROWS_PER_STATEMENT + 1)
            .mapToObj(stop -> pass(DAY, 500, stop, RECEIVED_AT))
            .toList();

        // when
        final int actual = passes.save(many);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isEqualTo(MultiRowInsert.ROWS_PER_STATEMENT + 1);
            softly.assertThat(count("stop_pass")).isEqualTo(MultiRowInsert.ROWS_PER_STATEMENT + 1);
        });
    }

    @Test
    void 정답과_미해결_예측을_넣고_같은_키를_다시_넣으면_건너뛴다() {
        // given
        PassLabel settled = new PassLabel(DAY, ROUTE_ID, 500, 3, 45, "alighting-identity-safe-v1", 9,
            PassLabel.State.SETTLED, null, 12.5, 400L, 401L, "a".repeat(64), RECEIVED_AT);
        PassLabel unsupported = new PassLabel(DAY, ROUTE_ID, 500, 4, 45, "alighting-identity-safe-v1", 9,
            PassLabel.State.UNSUPPORTED, "NO_PREVIOUS_PASS", null, null, null, null, RECEIVED_AT);
        UnresolvedForecast lost = new UnresolvedForecast(DAY, 1001, 5, ROUTE_ID, UnresolvedForecast.State.LOST,
            "PASS_NOT_SEEN", RECEIVED_AT);

        // when
        final int insertedLabels = labels.saveLabels(List.of(settled, unsupported));
        final int insertedUnresolved = labels.saveUnresolved(List.of(lost));
        final int againLabels = labels.saveLabels(List.of(settled));
        final int againUnresolved = labels.saveUnresolved(List.of(lost));

        // then
        assertSoftly(softly -> {
            softly.assertThat(insertedLabels).isEqualTo(2);
            softly.assertThat(insertedUnresolved).isOne();
            softly.assertThat(againLabels).isZero();
            softly.assertThat(againUnresolved).isZero();
            softly.assertThat(jdbcClient.sql("SELECT label_value FROM pass_label WHERE state = 'SETTLED'")
                .query(Double.class).single()).isEqualTo(12.5);
        });
    }

    @Test
    void 같은_통과_같은_정원이라도_모델_배포가_다르면_정답을_따로_넣는다() {
        // given
        PassLabel first = new PassLabel(DAY, ROUTE_ID, 500, 3, 45, "alighting-identity-safe-v1", 9,
            PassLabel.State.SETTLED, null, 12.5, 400L, 401L, "a".repeat(64), RECEIVED_AT);
        PassLabel second = new PassLabel(DAY, ROUTE_ID, 500, 3, 45, "alighting-identity-safe-v1", 10,
            PassLabel.State.SETTLED, null, 13.5, 400L, 401L, "b".repeat(64), RECEIVED_AT);

        // when
        final int inserted = labels.saveLabels(List.of(first, second));

        // then
        assertSoftly(softly -> {
            softly.assertThat(inserted).isEqualTo(2);
            softly.assertThat(jdbcClient.sql("""
                SELECT model_deployment_id FROM pass_label WHERE route_id = ? AND trip_start_observation_id = 500
                ORDER BY model_deployment_id
                """).param(ROUTE_ID).query(Long.class).list()).containsExactly(9L, 10L);
        });
    }

    private static long[] rawBits(double[] values) {
        return Arrays.stream(values).mapToLong(Double::doubleToRawLongBits).toArray();
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}

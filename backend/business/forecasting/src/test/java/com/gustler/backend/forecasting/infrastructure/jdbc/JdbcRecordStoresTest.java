package com.gustler.backend.forecasting.infrastructure.jdbc;

import static com.gustler.backend.forecasting.support.ForecastSamples.ROUTE_ID;
import static com.gustler.backend.forecasting.support.ForecastSamples.ROUTE_VERSION_ID;
import static com.gustler.backend.forecasting.support.ForecastSamples.pass;
import static com.gustler.backend.forecasting.support.ForecastSamples.published;
import static com.gustler.backend.forecasting.support.ForecastSamples.seenWithoutRows;
import static com.gustler.backend.forecasting.support.ForecastSamples.skipped;
import static com.gustler.backend.forecasting.support.ForecastSamples.vehicle;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.forecast.CollectionResult;
import com.gustler.backend.forecasting.api.forecast.ForecastOutcome;
import com.gustler.backend.forecasting.api.forecast.ForecastPublished;
import com.gustler.backend.forecasting.api.forecast.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.forecast.PassClosed;
import com.gustler.backend.forecasting.application.forecast.PassLabel;
import com.gustler.backend.forecasting.application.forecast.RecordedBatch;
import com.gustler.backend.forecasting.application.forecast.UnresolvedForecast;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@ForecastingIntegrationTest
@Transactional
class JdbcRecordStoresTest {
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
        final boolean inserted = save(published);

        // then
        assertSoftly(softly -> {
            softly.assertThat(inserted).isTrue();
            softly.assertThat(count("forecast_run")).isOne();
            softly.assertThat(storedVehicles(RECEIVED_AT))
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
        save(published);

        // when
        final boolean inserted = save(published);

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
        save(published(DAY, 11, RECEIVED_AT, List.of(vehicle)));

        // when
        ForecastPublished.VehicleForecast actual = storedVehicles(RECEIVED_AT).getFirst();

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
        save(published(DAY, 11, RECEIVED_AT, List.of(vehicle(1001, 1))));
        save(published(DAY.plusDays(1), 12, RECEIVED_AT.plusSeconds(86_400), List.of(vehicle(1002, 1))));

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
    void 노선의_실행을_수신_시각_범위로_사건_순서대로_되살린다() {
        // given
        ForecastPublished second = published(DAY, 12, RECEIVED_AT.plusSeconds(30),
            List.of(vehicle(1012, 2), vehicle(1011, 1)));
        ForecastPublished first = skipped(DAY, 11, RECEIVED_AT, ForecastOutcome.OUT_OF_SCOPE);
        ForecastPublished outside = published(DAY, 13, RECEIVED_AT.plusSeconds(60), List.of(vehicle(1013, 1)));
        save(second);
        save(outside);
        save(first);

        // when
        List<ForecastPublished> actual = records.findPublishedBetween(ROUTE_ID, RECEIVED_AT,
            RECEIVED_AT.plusSeconds(60));

        // then
        assertThat(actual).containsExactly(first, published(DAY, 12, RECEIVED_AT.plusSeconds(30),
            List.of(vehicle(1011, 1), vehicle(1012, 2))));
    }

    @Test
    void 실행_줄의_수집_사실을_수신_시각_범위_안의_것만_사건_순서대로_읽는다() {
        // given
        records.save(published(DAY, 12, RECEIVED_AT.plusSeconds(30), List.of()),
            seen(12, RECEIVED_AT.plusSeconds(30), CollectionResult.SUCCESS, 5, 4));
        records.save(published(DAY, 11, RECEIVED_AT, List.of(vehicle(1001, 1))),
            seen(11, RECEIVED_AT, CollectionResult.SUCCESS, 3, 3));
        records.save(skipped(DAY, 13, RECEIVED_AT.plusSeconds(60), ForecastOutcome.LATE),
            seen(13, RECEIVED_AT.plusSeconds(60), CollectionResult.SUCCESS, 2, 1));
        records.save(skipped(DAY, 14, RECEIVED_AT.plusSeconds(90), ForecastOutcome.EMPTY),
            seen(14, RECEIVED_AT.plusSeconds(90), CollectionResult.EMPTY, 0, 0));
        records.save(published(DAY, 16, RECEIVED_AT.plusSeconds(120), List.of()),
            seen(16, RECEIVED_AT.plusSeconds(120), CollectionResult.SUCCESS, 1, 1));

        // when
        List<RecordedBatch> actual = records.findRecordedBatchesBetween(ROUTE_ID, RECEIVED_AT,
            RECEIVED_AT.plusSeconds(120));

        // then
        assertThat(actual).containsExactly(
            new RecordedBatch(11, ROUTE_ID, RECEIVED_AT, RECEIVED_AT.plusSeconds(2), false, false, 3),
            new RecordedBatch(12, ROUTE_ID, RECEIVED_AT.plusSeconds(30), RECEIVED_AT.plusSeconds(32), false, true,
                4),
            new RecordedBatch(13, ROUTE_ID, RECEIVED_AT.plusSeconds(60), RECEIVED_AT.plusSeconds(155), true, true,
                1),
            new RecordedBatch(14, ROUTE_ID, RECEIVED_AT.plusSeconds(90), RECEIVED_AT.plusSeconds(185), false, false,
                0));
    }

    @Test
    void 예보_사건과_수집_사건의_배치가_다르면_저장하지_않는다() {
        // when
        Throwable actual = catchThrowable(() -> records.save(published(DAY, 11, RECEIVED_AT, List.of()),
            seen(12, RECEIVED_AT, CollectionResult.SUCCESS, 1, 1)));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).hasMessageContaining("배치가 다르다");
            softly.assertThat(count("forecast_run")).isZero();
        });
    }

    @Test
    void 정답이_저장된_통과_가운데_범위_안에서_가장_늦게_닫힌_것의_닫힌_시각을_찾는다() {
        // given
        passes.save(List.of(pass(DAY, 500, 3, RECEIVED_AT), pass(DAY, 600, 3, RECEIVED_AT.plusSeconds(60)),
            pass(DAY, 700, 3, RECEIVED_AT.plusSeconds(120))));
        labels.saveLabels(List.of(label(500, 3), label(600, 3), label(900, 3)));

        // when
        Optional<Instant> last = passes.findLastScoredClosedAt(ROUTE_ID, RECEIVED_AT, RECEIVED_AT.plusSeconds(300));
        Optional<Instant> earlier = passes.findLastScoredClosedAt(ROUTE_ID, RECEIVED_AT, RECEIVED_AT.plusSeconds(60));
        Optional<Instant> unscored = passes.findLastScoredClosedAt(ROUTE_ID, RECEIVED_AT.plusSeconds(61),
            RECEIVED_AT.plusSeconds(300));
        Optional<Instant> otherRoute = passes.findLastScoredClosedAt(ROUTE_ID + 1, RECEIVED_AT,
            RECEIVED_AT.plusSeconds(300));

        // then
        assertSoftly(softly -> {
            softly.assertThat(last).contains(RECEIVED_AT.plusSeconds(60));
            softly.assertThat(earlier).contains(RECEIVED_AT);
            softly.assertThat(unscored).isEmpty();
            softly.assertThat(otherRoute).isEmpty();
        });
    }

    @Test
    void 미해결_줄이_저장된_예측을_가진_실행_가운데_범위_안에서_가장_늦게_수신한_것의_착수_시각을_찾는다() {
        // given
        save(published(DAY, 11, RECEIVED_AT, List.of(vehicle(1001, 1))));
        save(published(DAY, 12, RECEIVED_AT.plusSeconds(30), List.of(vehicle(1002, 1), vehicle(1003, 2))));
        save(published(DAY, 13, RECEIVED_AT.plusSeconds(60), List.of(vehicle(1004, 1))));
        labels.saveUnresolved(List.of(unresolved(1001, 5), unresolved(1003, 7), unresolved(9999, 5)));

        // when
        Optional<Instant> last = records.findLastUnresolvedConfirmedAt(ROUTE_ID, RECEIVED_AT,
            RECEIVED_AT.plusSeconds(300));
        Optional<Instant> earlier = records.findLastUnresolvedConfirmedAt(ROUTE_ID, RECEIVED_AT,
            RECEIVED_AT.plusSeconds(30));
        Optional<Instant> open = records.findLastUnresolvedConfirmedAt(ROUTE_ID, RECEIVED_AT.plusSeconds(31),
            RECEIVED_AT.plusSeconds(300));
        Optional<Instant> otherRoute = records.findLastUnresolvedConfirmedAt(ROUTE_ID + 1, RECEIVED_AT,
            RECEIVED_AT.plusSeconds(300));

        // then
        assertSoftly(softly -> {
            softly.assertThat(last).contains(RECEIVED_AT.plusSeconds(32));
            softly.assertThat(earlier).contains(RECEIVED_AT.plusSeconds(2));
            softly.assertThat(open).isEmpty();
            softly.assertThat(otherRoute).isEmpty();
        });
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
        List<PassLabel> insertedLabels = labels.saveLabels(List.of(settled, unsupported));
        List<UnresolvedForecast> insertedUnresolved = labels.saveUnresolved(List.of(lost));
        List<PassLabel> againLabels = labels.saveLabels(List.of(settled));
        List<UnresolvedForecast> againUnresolved = labels.saveUnresolved(List.of(lost));

        // then
        assertSoftly(softly -> {
            softly.assertThat(insertedLabels).hasSize(2);
            softly.assertThat(insertedUnresolved).hasSize(1);
            softly.assertThat(againLabels).isEmpty();
            softly.assertThat(againUnresolved).isEmpty();
            softly.assertThat(jdbcClient.sql("SELECT label_value FROM pass_label WHERE state = 'SETTLED'")
                .query(Double.class).single()).isEqualTo(12.5);
        });
    }

    @Test
    void 새로_넣은_줄만_받은_객체_그대로_돌려주고_목록_안에서_같은_키가_다시_나오면_앞의_줄만_넣는다() {
        // given
        PassLabel stored = new PassLabel(DAY, ROUTE_ID, 500, 3, 45, "alighting-identity-safe-v1", 9,
            PassLabel.State.SETTLED, null, 12.5, 400L, 401L, "a".repeat(64), RECEIVED_AT);
        PassLabel first = new PassLabel(DAY, ROUTE_ID, 600, 3, 45, "alighting-identity-safe-v1", 9,
            PassLabel.State.SETTLED, null, 13.5, 400L, 401L, "a".repeat(64), RECEIVED_AT);
        PassLabel again = new PassLabel(DAY, ROUTE_ID, 600, 3, 45, "alighting-identity-safe-v1", 9,
            PassLabel.State.SETTLED, null, 14.5, 400L, 401L, "a".repeat(64), RECEIVED_AT.plusSeconds(1));
        UnresolvedForecast lost = new UnresolvedForecast(DAY, 1001, 5, ROUTE_ID, UnresolvedForecast.State.LOST,
            "PASS_NOT_SEEN", RECEIVED_AT);
        UnresolvedForecast lostAgain = new UnresolvedForecast(DAY, 1001, 5, ROUTE_ID,
            UnresolvedForecast.State.UNSUPPORTED, "SOURCE_HISTORY_INCOMPLETE", RECEIVED_AT.plusSeconds(1));
        labels.saveLabels(List.of(stored));

        // when
        List<PassLabel> insertedLabels = labels.saveLabels(List.of(stored, first, again));
        List<UnresolvedForecast> insertedUnresolved = labels.saveUnresolved(List.of(lost, lostAgain));

        // then
        assertSoftly(softly -> {
            softly.assertThat(insertedLabels).hasSize(1);
            softly.assertThat(insertedLabels.getFirst()).isSameAs(first);
            softly.assertThat(insertedUnresolved).hasSize(1);
            softly.assertThat(insertedUnresolved.getFirst()).isSameAs(lost);
            softly.assertThat(jdbcClient.sql("""
                SELECT label_value FROM pass_label WHERE route_id = ? AND trip_start_observation_id = 600
                """).param(ROUTE_ID).query(Double.class).list()).containsExactly(13.5);
            softly.assertThat(jdbcClient.sql("""
                SELECT state FROM forecast_unresolved WHERE route_id = ? AND vehicle_observation_id = 1001
                """).param(ROUTE_ID).query(String.class).list()).containsExactly("LOST");
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
        List<PassLabel> inserted = labels.saveLabels(List.of(first, second));

        // then
        assertSoftly(softly -> {
            softly.assertThat(inserted).hasSize(2);
            softly.assertThat(jdbcClient.sql("""
                SELECT model_deployment_id FROM pass_label WHERE route_id = ? AND trip_start_observation_id = 500
                ORDER BY model_deployment_id
                """).param(ROUTE_ID).query(Long.class).list()).containsExactly(9L, 10L);
        });
    }

    private static ObservationBatchSeen seen(
        final long batchId,
        Instant receivedAt,
        CollectionResult result,
        final int storedRows,
        final int savedRows
    ) {
        return new ObservationBatchSeen(batchId, ROUTE_ID, ROUTE_VERSION_ID, receivedAt, result, storedRows,
            savedRows);
    }

    /** 수집 구멍과 관측 줄 수가 없는 수집 사건과 함께 저장한다. */
    private boolean save(ForecastPublished published) {
        return records.save(published, seenWithoutRows(published));
    }

    /** 수신 시각이 {@code receivedAt}인 실행 하나의 버스별 기록을 저장소로 다시 읽는다. */
    private List<ForecastPublished.VehicleForecast> storedVehicles(Instant receivedAt) {
        return records.findPublishedBetween(ROUTE_ID, receivedAt, receivedAt.plusMillis(1)).getFirst().vehicles();
    }

    private static PassLabel label(final long tripStart, final int stopOrder) {
        return new PassLabel(DAY, ROUTE_ID, tripStart, stopOrder, 45, "alighting-identity-safe-v1", 9,
            PassLabel.State.SETTLED, null, 12.5, null, null, "a".repeat(64), RECEIVED_AT);
    }

    private static UnresolvedForecast unresolved(final long vehicleObservationId, final int targetStopOrder) {
        return new UnresolvedForecast(DAY, vehicleObservationId, targetStopOrder, ROUTE_ID,
            UnresolvedForecast.State.LOST, "PASS_NOT_CLOSED", RECEIVED_AT);
    }

    private static long[] rawBits(double[] values) {
        return Arrays.stream(values).mapToLong(Double::doubleToRawLongBits).toArray();
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}

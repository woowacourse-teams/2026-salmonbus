package com.gustler.backend.forecasting.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ForecastMonitoringTest {
    @Test
    void 수집_실패와_실제_정리_건수만_발생시각에_반영한다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ForecastMonitoring monitoring = new ForecastMonitoring(registry, mock(DataSource.class),
            Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC));

        monitoring.completed("forecast_tick", "all", 1, true);
        monitoring.collectionRecovered(0);
        assertThat(registry.get("salmonbus.collection.failure.last.timestamp").gauge().value()).isZero();
        assertThat(registry.get("salmonbus.collection.recovery.last.timestamp").gauge().value()).isZero();

        monitoring.completed("collection_failure_close", "all", 1, true);
        monitoring.collectionRecovered(3);

        assertThat(registry.get("salmonbus.collection.failure.last.timestamp").gauge().value()).isEqualTo(1000);
        assertThat(registry.get("salmonbus.collection.recovery.last.timestamp").gauge().value()).isEqualTo(1000);
        assertThat(registry.get("salmonbus.collection.recovered.batches").counter().count()).isEqualTo(3);
    }

    @Test
    void 격리_판정별로_건수와_마지막_발생_시각을_노출한다() {
        // given
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ForecastMonitoring monitoring = new ForecastMonitoring(registry, mock(DataSource.class),
            Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC));

        // when
        monitoring.collectionSeatQuality(2, 0);
        monitoring.collectionSeatQuality(1, 1);

        // then
        assertThat(registry.get("salmonbus.collection.seat.quality").tag("decision", "REVIEW").counter().count())
            .isEqualTo(3);
        assertThat(registry.get("salmonbus.collection.seat.quality").tag("decision", "OUTLIER").counter().count())
            .isEqualTo(1);
        assertThat(registry.get("salmonbus.collection.seat.quality.last.timestamp").gauges())
            .extracting(gauge -> gauge.value()).containsExactlyInAnyOrder(1000.0, 1000.0);
    }

    @Test
    void 정상_실행도_처리시간_분포에_포함하고_노선별_히스토그램은_만들지_않는다() {
        // when
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ForecastMonitoring monitoring = new ForecastMonitoring(registry, mock(DataSource.class), Clock.systemUTC());
        monitoring.completed("forecast_tick", "all", 5000000, false);
        monitoring.completed("forecast_tick", 123L, 2000000, false);

        // then
        assertThat(registry.get("salmonbus.worker.operation").tag("outcome", "returned").timer().count()).isEqualTo(2);
        assertThat(registry.find("salmonbus.worker.operation").timers()).hasSize(1);
    }

    @Test
    void 이름의_공백_따옴표_개행을_로그_필드에서_안전하게_표현한다() {
        // when
        String quotedValue1 = ForecastMonitoring.quote("역 \"A\"\n출구");

        // then
        assertThat(quotedValue1).isEqualTo("\"역 \\\"A\\\"\\n출구\"");
    }

    @Test
    void 재시작_직후는_미확인이고_빈_차량_수집과_미발행_지연을_구분한다() throws Exception {
        // when
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC);
        ForecastMonitoring monitoring = new ForecastMonitoring(registry, mock(DataSource.class), clock);
        Method register = ForecastMonitoring.class.getDeclaredMethod("registerRoute", long.class, String.class);
        register.setAccessible(true);
        Object state = register.invoke(monitoring, 1L, "9300");
        Field routes = ForecastMonitoring.class.getDeclaredField("routes");
        routes.setAccessible(true);
        ((Map) routes.get(monitoring)).put(1L, state);
        Field sources = ForecastMonitoring.class.getDeclaredField("sourceRoutes");
        sources.setAccessible(true);
        sources.set(monitoring, Map.of("234000886", 1L));
        Field versions = ForecastMonitoring.class.getDeclaredField("versionRoutes");
        versions.setAccessible(true);
        versions.set(monitoring, Map.of(4L, 1L));

        // then
        assertThat(registry.get("salmonbus.collection.last.success.timestamp").gauge().value()).isNaN();
        monitoring.collectionAttempted("234000886", 900);
        monitoring.collectionAttempted("234000886", 910);
        assertThat(registry.get("salmonbus.collection.first.attempt.timestamp").gauge().value()).isEqualTo(900);
        monitoring.collectionCommitted("234000886", 990, 0);
        assertThat(registry.get("salmonbus.collection.last.success.timestamp").gauge().value()).isEqualTo(990);
        assertThat(registry.get("salmonbus.collection.usable.rows").gauge().value()).isZero();
        monitoring.pending(4L, Instant.ofEpochSecond(600));
        assertThat(registry.get("salmonbus.forecast.pending.age.seconds").gauge().value()).isEqualTo(400);
        monitoring.pending(4L, null);
        assertThat(registry.get("salmonbus.forecast.pending.age.seconds").gauge().value()).isZero();
        assertThat(monitoring.context("collection_attempt", "234000886")).contains("routeName=\"9300\"");
    }

    @Test
    void 예보_커밋은_마지막_커밋_시각을_갱신하고_예측이_있는_커밋만_마지막_예보와_입력_시각을_갱신한다() throws Exception {
        // given
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PipelineClock clock = new PipelineClock(Instant.ofEpochSecond(1000));
        ForecastMonitoring monitoring = new ForecastMonitoring(registry, mock(DataSource.class), clock);
        Method register = ForecastMonitoring.class.getDeclaredMethod("registerRoute", long.class, String.class);
        register.setAccessible(true);
        Object state = register.invoke(monitoring, 1L, "9300");
        Field routes = ForecastMonitoring.class.getDeclaredField("routes");
        routes.setAccessible(true);
        ((Map) routes.get(monitoring)).put(1L, state);

        // when
        monitoring.published(1L, 4L, Instant.ofEpochSecond(990), 0);

        // then
        assertThat(registry.get("salmonbus.forecast.last.commit.timestamp").gauge().value()).isEqualTo(1000);
        assertThat(registry.get("salmonbus.forecast.last.nonempty.timestamp").gauge().value()).isNaN();
        assertThat(registry.get("salmonbus.forecast.last.input.timestamp").gauge().value()).isNaN();

        // when
        clock.now = Instant.ofEpochSecond(1030);
        monitoring.published(1L, 4L, Instant.ofEpochSecond(1020), 12);
        clock.now = Instant.ofEpochSecond(1060);
        monitoring.published(1L, 4L, Instant.ofEpochSecond(1050), 0);
        monitoring.published(2L, 5L, Instant.ofEpochSecond(1050), 3);

        // then
        assertThat(registry.get("salmonbus.forecast.last.commit.timestamp").gauge().value()).isEqualTo(1060);
        assertThat(registry.get("salmonbus.forecast.last.nonempty.timestamp").gauge().value()).isEqualTo(1030);
        assertThat(registry.get("salmonbus.forecast.last.input.timestamp").gauge().value()).isEqualTo(1020);
    }

    private static final class PipelineClock extends Clock {
        private Instant now;

        private PipelineClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

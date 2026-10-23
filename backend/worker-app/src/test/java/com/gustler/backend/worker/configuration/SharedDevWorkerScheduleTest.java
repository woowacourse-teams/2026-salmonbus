package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.worker.scheduling.AdaptiveCollectionTrigger;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Map.Entry;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.support.SimpleTriggerContext;

class SharedDevWorkerScheduleTest {
    @Test
    void 공유_개발_환경은_운영_작업_주기와_수집_노선을_쓴다() throws Exception {
        // when
        MockEnvironment base = environment(false);
        MockEnvironment shared = environment(true);
        CollectionProperties productionCollection = Binder.get(base)
            .bind("collection", CollectionProperties.class)
            .get();
        CollectionProperties devCollection = Binder.get(shared).bind("collection", CollectionProperties.class).get();
        ForecastProperties productionForecast = Binder.get(base).bind("forecast", ForecastProperties.class).get();
        ForecastProperties devForecast = Binder.get(shared).bind("forecast", ForecastProperties.class).get();

        // then
        assertThat(devCollection.enabled()).isTrue();
        assertThat(devCollection.fixedInterval()).isNull();
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(devCollection.routeIds()).containsExactlyElementsOf(productionCollection.routeIds());
            softly.assertThat(devForecast.enabled()).isTrue();
            softly.assertThat(devForecast.interval()).isEqualTo(productionForecast.interval());
            softly.assertThat(devForecast.settlementInterval()).isEqualTo(productionForecast.settlementInterval());
            softly.assertThat(devForecast.statisticsInterval()).isEqualTo(productionForecast.statisticsInterval());
            softly.assertThat(devForecast.pendingLimit()).isEqualTo(productionForecast.pendingLimit());
        }
    }

    @Test
    void 공유_개발서버도_시간대별_수집_주기를_사용한다() throws Exception {
        // given
        CollectionProperties properties = Binder.get(environment(true))
            .bind("collection", CollectionProperties.class)
            .get();

        for (Entry<Instant, Duration> entry : Map.of(
            Instant.parse("2026-10-15T09:00:00Z"), Duration.ofSeconds(20),
            Instant.parse("2026-10-15T12:00:00Z"), Duration.ofSeconds(20),
            Instant.parse("2026-10-15T15:00:00Z"), Duration.ofSeconds(30),
            Instant.parse("2026-10-15T21:00:00Z"), Duration.ofSeconds(30),
            Instant.parse("2026-10-15T23:00:00Z"), Duration.ofSeconds(20),
            Instant.parse("2026-10-15T03:00:00Z"), Duration.ofSeconds(30),
            Instant.parse("2026-10-15T17:00:00Z"), Duration.ofMinutes(10)).entrySet()) {
            Instant now = entry.getKey();
            AdaptiveCollectionTrigger trigger =
                new AdaptiveCollectionTrigger(Clock.fixed(now, ZoneOffset.UTC), properties.fixedInterval());

            // when
            Instant nextExecution = trigger.nextExecution(new SimpleTriggerContext(now, now, now));

            // then
            assertThat(nextExecution).isEqualTo(now.plus(entry.getValue()));
        }
    }

    private MockEnvironment environment(final boolean shared) throws Exception {
        MockEnvironment environment = new MockEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (
            PropertySource<?> source : loader.load("base",
                new FileSystemResource("src/main/resources/application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        if (shared) {
            Path file = Path.of("../../dev/server/worker.yml");
            assertThat(file).exists();
            for (PropertySource<?> source : loader.load("shared-dev", new FileSystemResource(file))) {
                environment.getPropertySources().addFirst(source);
            }
        }
        return environment;
    }
}

package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.worker.scheduling.AdaptiveCollectionTrigger;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.support.SimpleTriggerContext;

class SharedDevWorkerScheduleTest {
    @Test
    void sharedDevUsesProductionJobIntervalsAndCollectionRoutes() throws Exception {
        var base = environment(false);
        var shared = environment(true);
        var productionCollection = Binder.get(base).bind("collection", CollectionProperties.class).get();
        var devCollection = Binder.get(shared).bind("collection", CollectionProperties.class).get();
        var productionForecast = Binder.get(base).bind("forecast", ForecastProperties.class).get();
        var devForecast = Binder.get(shared).bind("forecast", ForecastProperties.class).get();

        assertThat(devCollection.enabled()).isTrue();
        assertThat(devCollection.fixedInterval()).isNull();
        assertThat(devCollection.routeIds()).containsExactlyElementsOf(productionCollection.routeIds());
        assertThat(devForecast.enabled()).isTrue();
        assertThat(devForecast.interval()).isEqualTo(productionForecast.interval());
        assertThat(devForecast.settlementInterval()).isEqualTo(productionForecast.settlementInterval());
        assertThat(devForecast.statisticsInterval()).isEqualTo(productionForecast.statisticsInterval());
        assertThat(devForecast.pendingLimit()).isEqualTo(productionForecast.pendingLimit());
    }

    @Test
    void sharedDevRetainsPeakAndNightCollectionSchedules() throws Exception {
        var properties = Binder.get(environment(true)).bind("collection", CollectionProperties.class).get();
        for (var entry : java.util.Map.of(
                Instant.parse("2026-10-15T09:00:00Z"), Duration.ofSeconds(15),
                Instant.parse("2026-10-15T17:00:00Z"), Duration.ofMinutes(10)).entrySet()) {
            var now = entry.getKey();
            var trigger = new AdaptiveCollectionTrigger(Clock.fixed(now, ZoneOffset.UTC), properties.fixedInterval());
            assertThat(trigger.nextExecution(new SimpleTriggerContext(now, now, now)))
                .isEqualTo(now.plus(entry.getValue()));
        }
    }

    private MockEnvironment environment(final boolean shared) throws Exception {
        var environment = new MockEnvironment();
        var loader = new YamlPropertySourceLoader();
        for (var source : loader.load("base", new FileSystemResource("src/main/resources/application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        if (shared) {
            var file = Path.of("../../dev/server/worker.yml");
            assertThat(file).exists();
            for (var source : loader.load("shared-dev", new FileSystemResource(file))) {
                environment.getPropertySources().addFirst(source);
            }
        }
        return environment;
    }
}

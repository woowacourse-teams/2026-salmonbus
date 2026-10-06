package com.gustler.backend.worker.scheduling;

import com.gustler.backend.worker.configuration.CollectionProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixedCollectionIntervalTest {
    @Test
    void bindsOptionalIntervalAlongsideExistingProperties() {
        var source = new MapConfigurationPropertySource(Map.of("collection.enabled", "true",
            "collection.route-ids[0]", "234000050", "collection.fixed-interval", "20s"));
        var properties = new Binder(source).bind("collection", Bindable.of(CollectionProperties.class)).get();
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.routeIds()).containsExactly("234000050");
        assertThat(properties.fixedInterval()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void fixedIntervalUsesCompletionRegardlessOfTimeOfDay() {
        Instant started = Instant.parse("2026-10-06T16:00:00Z");
        Instant completed = started.plusSeconds(3);
        var trigger = new AdaptiveCollectionTrigger(Clock.fixed(started, ZoneOffset.UTC), Duration.ofSeconds(20));
        assertThat(trigger.nextExecution(new SimpleTriggerContext(started, started, completed)))
            .isEqualTo(completed.plusSeconds(20));
        assertThat(trigger.nextExecution(new SimpleTriggerContext())).isEqualTo(started);
    }

    @Test
    void omittedIntervalKeepsAdaptiveNightSchedule() {
        Instant at = Instant.parse("2026-10-06T16:00:00Z");
        var trigger = new AdaptiveCollectionTrigger(Clock.fixed(at, ZoneOffset.UTC));
        assertThat(trigger.nextExecution(new SimpleTriggerContext(at, at, at))).isEqualTo(at.plusSeconds(600));
        assertThat(new CollectionProperties(true, List.of()).fixedInterval()).isNull();
    }

    @Test
    void rejectsNonpositiveOrSubsecondInterval() {
        for (Duration interval : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(500))) {
            assertThatThrownBy(() -> new CollectionProperties(true, List.of(), interval))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

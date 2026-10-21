package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.worker.configuration.CollectionProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.scheduling.support.SimpleTriggerContext;

class FixedCollectionIntervalTest {
    @Test
    void 기존_설정과_함께_선택적인_주기를_바인딩한다() {
        // when
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of("collection.enabled", "true",
            "collection.route-ids[0]", "234000050", "collection.fixed-interval", "20s"));
        CollectionProperties properties = new Binder(source).bind("collection", Bindable.of(CollectionProperties.class))
            .get();

        // then
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.routeIds()).containsExactly("234000050");
        assertThat(properties.fixedInterval()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void 고정_주기는_시간대와_관계없이_완료_시각을_기준으로_한다() {
        // when
        Instant started = Instant.parse("2026-10-06T16:00:00Z");
        Instant completed = started.plusSeconds(3);
        AdaptiveCollectionTrigger trigger =
            new AdaptiveCollectionTrigger(Clock.fixed(started, ZoneOffset.UTC), Duration.ofSeconds(20));

        // then
        assertThat(trigger.nextExecution(new SimpleTriggerContext(started, started, completed)))
            .isEqualTo(completed.plusSeconds(20));
        assertThat(trigger.nextExecution(new SimpleTriggerContext())).isEqualTo(started);
    }

    @Test
    void 주기를_생략하면_야간_적응형_일정을_유지한다() {
        // when
        Instant at = Instant.parse("2026-10-06T16:00:00Z");
        AdaptiveCollectionTrigger trigger = new AdaptiveCollectionTrigger(Clock.fixed(at, ZoneOffset.UTC));

        // then
        assertThat(trigger.nextExecution(new SimpleTriggerContext(at, at, at))).isEqualTo(at.plusSeconds(600));
        assertThat(new CollectionProperties(true, List.of()).fixedInterval()).isNull();
    }

    @Test
    void 양수가_아니거나_1초_미만인_주기를_거절한다() {
        // when
        for (Duration interval : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(500))) {

            // then

            // when
            Throwable phaseFailure1 = catchThrowable(() -> new CollectionProperties(true, List.of(), interval));

            // then
            assertThat(phaseFailure1)
                .isNotNull()
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

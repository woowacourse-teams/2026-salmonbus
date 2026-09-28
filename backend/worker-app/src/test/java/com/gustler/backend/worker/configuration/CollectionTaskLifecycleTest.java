package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.gustler.backend.worker.scheduling.AdaptiveCollectionTrigger;
import com.gustler.backend.worker.scheduling.CollectionScheduler;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class CollectionTaskLifecycleTest {
    @Test
    void 컨텍스트가_종료되면_관리하던_수집_예약을_취소한다() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(AdaptiveCollectionTrigger.class));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("test", Map.of("collection.enabled", "true")));
            context.registerBean(CollectionProperties.class, () -> new CollectionProperties(true, List.of()));
            context.registerBean(CollectionScheduler.class, () -> mock(CollectionScheduler.class));
            context.registerBean(Clock.class, Clock::systemUTC);
            context.registerBean(GbisProperties.class, () -> new GbisProperties("https://example.invalid", "fixture", 10000));
            context.registerBean("collectionTaskScheduler", TaskScheduler.class, () -> scheduler);
            context.register(CollectionConfig.ScheduledCollection.class);
            context.refresh();
            assertThat(context.getBean("collectionTaskRegistrar", ScheduledTaskRegistrar.class).getScheduledTasks())
                .hasSize(1);
        }
        verify(scheduler).schedule(any(Runnable.class), any(AdaptiveCollectionTrigger.class));
        verify(future).cancel(anyBoolean());
    }
}

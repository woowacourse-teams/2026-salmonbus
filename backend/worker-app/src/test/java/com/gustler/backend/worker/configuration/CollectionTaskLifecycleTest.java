package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.gustler.backend.forecasting.api.pipeline.MaintainPipelinePartitions;
import com.gustler.backend.worker.configuration.CollectionConfig.ScheduledCollection;
import com.gustler.backend.worker.scheduling.AdaptiveCollectionTrigger;
import com.gustler.backend.worker.scheduling.CollectionScheduler;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class CollectionTaskLifecycleTest {
    @Test
    void 컨텍스트가_종료되면_관리하던_수집_예약을_취소한다() {
        // when
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(AdaptiveCollectionTrigger.class));
        try (AnnotationConfigApplicationContext context = collectionContext(scheduler, new CopyOnWriteArrayList<>())) {
            context.refresh();

            // then
            assertThat(context.getBean("collectionTaskRegistrar", ScheduledTaskRegistrar.class).getScheduledTasks())
                .hasSize(1);
        }
        verify(scheduler).schedule(any(Runnable.class), any(AdaptiveCollectionTrigger.class));
        verify(future).cancel(anyBoolean());
    }

    @Test
    void 날짜_파티션_유지가_첫_실행을_끝낸_뒤에_수집을_예약한다() {
        // given
        List<String> order = new CopyOnWriteArrayList<>();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doAnswer(invocation -> {
            order.add("collection_scheduled");
            return mock(ScheduledFuture.class);
        }).when(scheduler).schedule(any(Runnable.class), any(AdaptiveCollectionTrigger.class));

        // when
        try (AnnotationConfigApplicationContext context = collectionContext(scheduler, order)) {
            context.refresh();
        }

        // then
        assertThat(order).containsExactly("partitions_maintained", "collection_scheduled");
    }

    /**
     * 수집을 켠 worker의 수집 예약과 날짜 파티션 유지 구성만 올린 컨텍스트다. 수집 예약을 유지 구성보다 먼저 등록해, 등록 순서가
     * 아니라 선언한 의존 관계가 순서를 정하는지 본다.
     */
    private static AnnotationConfigApplicationContext collectionContext(TaskScheduler scheduler, List<String> order) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("test", Map.of("collection.enabled", "true")));
        context.registerBean(CollectionProperties.class, () -> new CollectionProperties(true, List.of()));
        context.registerBean(CollectionScheduler.class, () -> mock(CollectionScheduler.class));
        context.registerBean(Clock.class, Clock::systemUTC);
        context.registerBean(GbisProperties.class,
            () -> new GbisProperties("https://example.invalid", "fixture", 10000));
        context.registerBean("collectionTaskScheduler", TaskScheduler.class, () -> scheduler);
        context.registerBean("calibrationTaskScheduler", TaskScheduler.class, () -> mock(TaskScheduler.class));
        context.registerBean(MaintainPipelinePartitions.class, () -> () -> {
            order.add("partitions_maintained");
            return new MaintainPipelinePartitions.Report(List.of(), List.of(), Map.of());
        });
        context.register(ScheduledCollection.class, ForecastPipelineConfiguration.class);
        return context;
    }
}

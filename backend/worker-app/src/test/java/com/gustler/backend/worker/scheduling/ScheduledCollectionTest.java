package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.gustler.backend.support.PostgresTestContainer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 수집할 노선을 비워둔다. 활성화하면 첫 수집이 바로 실행되지만 노선이 없으면
 * 아무 작업도 하지 않아 상류 호출은 나가지 않는다. 이상 차량 조사 작업은 빈 조사 표를 확인할 수 있다.
 * 여기서는 수집과 편도 조사의 스케줄 등록을 확인한다.
 */
@SpringBootTest(properties = {"collection.enabled=true", "forecast.enabled=false", "collection.route-ids="})
@Import(PostgresTestContainer.class)
class ScheduledCollectionTest {
    @Autowired
    private List<ScheduledTaskHolder> scheduledTaskHolders;

    @MockitoSpyBean(name = "collectionTaskScheduler", reset = MockReset.NONE)
    private ThreadPoolTaskScheduler collectionScheduler;

    @MockitoSpyBean(name = "taskScheduler", reset = MockReset.NONE)
    private ThreadPoolTaskScheduler processingScheduler;

    @Test
    void 수집은_전용_스케줄러에_한번_등록하고_관리_목록에도_나타난다() {
        // given
        // 수집은 켜고 예보 계산은 끈 컨텍스트다.

        // when
        List<Trigger> triggers = scheduledTaskHolders.stream()
            .flatMap(holder -> holder.getScheduledTasks()
                .stream())
            .map(scheduled -> scheduled.getTask())
            .filter(TriggerTask.class::isInstance)
            .map(TriggerTask.class::cast)
            .map(TriggerTask::getTrigger)
            .toList();

        // then
        assertThat(triggers).filteredOn(AdaptiveCollectionTrigger.class::isInstance).hasSize(1);
        var nightly = triggers.stream().filter(CronTrigger.class::isInstance).map(CronTrigger.class::cast).toList();
        assertThat(nightly).hasSize(1);
        assertThat(nightly.getFirst().getExpression()).isEqualTo("0 0 4 * * *");
        assertThat(nightly.getFirst().nextExecution(new SimpleTriggerContext(
            Clock.fixed(Instant.parse("2026-11-04T18:59:59Z"), ZoneOffset.UTC))))
            .isEqualTo(Instant.parse("2026-11-04T19:00:00Z"));
        verify(collectionScheduler).schedule(any(Runnable.class), any(AdaptiveCollectionTrigger.class));
        verify(collectionScheduler).schedule(any(Runnable.class), any(CronTrigger.class));
        verify(processingScheduler, never()).schedule(any(Runnable.class), any(AdaptiveCollectionTrigger.class));
        verify(processingScheduler, never()).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void 수집을_켜도_편도_조사_스케줄을_등록하지_않는다() {
        // given
        // 수집은 켜고 예보 계산은 끈 컨텍스트다.

        // when
        List<String> registeredTasks = scheduledTaskHolders.stream()
            .flatMap(holder -> holder.getScheduledTasks()
                .stream())
            .map(scheduled -> scheduled.getTask().toString())
            .toList();

        // then
        assertThat(registeredTasks).noneMatch(task -> task.endsWith("TripQualityInvestigationJob.investigate"));
    }
}

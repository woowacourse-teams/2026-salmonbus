package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.gustler.backend.support.PostgresTestContainer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 돌 노선을 비워둔다. 켜자마자 첫 판이 도는데 노선이 없으면 아무것도 안 해서
 * 상류 호출은 나가지 않는다. 이상 차량 조사 작업은 빈 조사 표를 확인할 수 있다.
 * 여기서는 수집과 편도 조사의 스케줄 등록을 확인한다.
 */
@SpringBootTest(properties = {"collection.enabled=true", "forecast.enabled=false", "collection.route-ids="})
@Import(PostgresTestContainer.class)
class ScheduledCollectionTest {

    @Autowired
    private List<ScheduledTaskHolder> scheduledTaskHolders;

    @MockitoSpyBean(name="collectionTaskScheduler", reset=MockReset.NONE)
    private ThreadPoolTaskScheduler collectionScheduler;

    @MockitoSpyBean(name="taskScheduler", reset=MockReset.NONE)
    private ThreadPoolTaskScheduler processingScheduler;

    @Test
    void 수집은_전용_스케줄러에_한번_등록하고_관리_목록에도_나타난다() {
        // given: 수집은 켜고 예보 계산은 끈 컨텍스트다.

        // when
        var triggers = scheduledTaskHolders.stream()
            .flatMap(holder -> holder.getScheduledTasks().stream())
            .map(scheduled -> scheduled.getTask())
            .filter(TriggerTask.class::isInstance)
            .map(TriggerTask.class::cast)
            .map(TriggerTask::getTrigger)
            .toList();

        // then
        assertThat(triggers).singleElement().isInstanceOf(AdaptiveCollectionTrigger.class);
        verify(collectionScheduler).schedule(any(Runnable.class),any(AdaptiveCollectionTrigger.class));
        verify(processingScheduler, never()).schedule(any(Runnable.class),any(AdaptiveCollectionTrigger.class));
    }

    @Test
    void 예보_계산을_꺼도_수집을_켜면_이상_차량의_편도_조사를_등록한다() {
        // given: 수집은 켜고 예보 계산은 끈 컨텍스트다.

        // when
        var registeredTasks = scheduledTaskHolders.stream()
            .flatMap(holder -> holder.getScheduledTasks().stream())
            .map(scheduled -> scheduled.getTask().toString())
            .toList();

        // then
        assertThat(registeredTasks).contains(TripQualityInvestigationJob.class.getName() + ".investigate");
    }
}

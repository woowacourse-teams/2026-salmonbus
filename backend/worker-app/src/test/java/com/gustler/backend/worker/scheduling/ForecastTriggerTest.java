package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.gustler.backend.forecasting.api.publication.PublishPendingForecasts;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.api.ObservationDeliveryBatch.Kind;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ForecastTriggerTest {
    private static final long ROUTE_VERSION_ID = 7L;

    private final PublishPendingForecasts service = mock(PublishPendingForecasts.class);
    private final QueuedExecutor executor = new QueuedExecutor();
    private final ForecastTrigger trigger = new ForecastTrigger(service, executor);

    @AfterEach
    void 트랜잭션_동기화를_정리한다() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 관측_저장이_커밋된_뒤에만_그_노선의_예보를_예약한다() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        trigger.record(batch(Kind.STORED));
        assertThat(executor.tasks).isEmpty();

        // when
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        executor.runAll();

        // then
        verify(service).writeForecastsOfRoute(ROUTE_VERSION_ID);
    }

    @Test
    void 롤백된_관측과_관측_삭제_전달은_예보를_예약하지_않는다() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        trigger.record(batch(Kind.STORED));
        trigger.record(batch(Kind.CLEARED));

        // when
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        // then
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        assertThat(executor.tasks).isEmpty();
    }

    @Test
    void 실행_전에_같은_노선_이벤트가_쌓이면_한_번만_예보한다() {
        // when
        trigger.onObservationStored(ROUTE_VERSION_ID);
        trigger.onObservationStored(ROUTE_VERSION_ID);
        trigger.onObservationStored(ROUTE_VERSION_ID);
        executor.runAll();

        // then
        verify(service, times(1)).writeForecastsOfRoute(ROUTE_VERSION_ID);
    }

    @Test
    void 예보하는_동안_들어온_이벤트는_끝난_뒤_다시_예보한다() {
        // given
        doAnswer(invocation -> {
            if (executor.runs == 1) {
                trigger.onObservationStored(ROUTE_VERSION_ID);
            }
            return null;
        }).when(service).writeForecastsOfRoute(ROUTE_VERSION_ID);

        // when
        trigger.onObservationStored(ROUTE_VERSION_ID);
        executor.runAll();

        // then
        verify(service, times(2)).writeForecastsOfRoute(ROUTE_VERSION_ID);
    }

    @Test
    void 예보가_실패한_노선도_다음_이벤트에_다시_예보한다() {
        // given
        doThrow(new IllegalStateException("commit failed")).doNothing()
            .when(service).writeForecastsOfRoute(ROUTE_VERSION_ID);
        trigger.onObservationStored(ROUTE_VERSION_ID);
        executor.runAll();

        // when
        trigger.onObservationStored(ROUTE_VERSION_ID);
        executor.runAll();

        // then
        verify(service, times(2)).writeForecastsOfRoute(ROUTE_VERSION_ID);
    }

    @Test
    void 실행기가_이벤트를_거절하면_다음_이벤트를_다시_받는다() {
        // given
        executor.rejectNext = true;
        trigger.onObservationStored(ROUTE_VERSION_ID);

        // when
        trigger.onObservationStored(ROUTE_VERSION_ID);
        executor.runAll();

        // then
        verify(service, times(1)).writeForecastsOfRoute(ROUTE_VERSION_ID);
    }

    @Test
    void 회차는_전체_노선을_예보하고_이벤트와_같은_예보_전용_실행기를_쓴다() throws Exception {
        // when
        trigger.writeForecasts();

        // then
        verify(service).writeForecasts();
        verify(service, never()).writeForecastsOfRoute(ROUTE_VERSION_ID);
        assertThat(ForecastTrigger.class.getMethod("writeForecasts").getAnnotation(Scheduled.class).scheduler())
            .isEqualTo("forecastTaskScheduler");
    }

    private static ObservationDeliveryBatch batch(Kind kind) {
        return new ObservationDeliveryBatch(1L, ROUTE_VERSION_ID, 1, kind, Instant.EPOCH, List.of());
    }

    private static final class QueuedExecutor implements TaskExecutor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean rejectNext;
        private int runs;

        @Override
        public void execute(Runnable task) {
            if (rejectNext) {
                rejectNext = false;
                throw new TaskRejectedException("stopping");
            }
            tasks.add(task);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                runs++;
                tasks.poll().run();
            }
        }
    }
}

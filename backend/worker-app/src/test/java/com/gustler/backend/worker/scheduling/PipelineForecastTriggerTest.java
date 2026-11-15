package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.pipeline.ForecastPipeline;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PipelineForecastTriggerTest {
    private final RecordingPipeline pipeline = new RecordingPipeline();
    private final PipelineForecastTrigger trigger = new PipelineForecastTrigger(pipeline);

    @AfterEach
    void 트랜잭션_동기화를_정리한다() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 관측_저장이_커밋된_뒤에만_그_배치를_본선에_넘긴다() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        trigger.record(batch(ObservationDeliveryBatch.Kind.STORED));
        assertThat(pipeline.stored).isEmpty();

        // when
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        // then
        assertThat(pipeline.stored).containsExactly("11:7");
    }

    @Test
    void 롤백된_관측과_관측_삭제_전달은_넘기지_않는다() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        trigger.record(batch(ObservationDeliveryBatch.Kind.STORED));
        trigger.record(batch(ObservationDeliveryBatch.Kind.CLEARED));

        // when
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        // then
        assertThat(pipeline.stored).isEmpty();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    @Test
    void 줍기는_기동_직후와_주기적으로_예보_전용_스케줄러에서_돈다() throws Exception {
        // when
        trigger.pickUp();

        // then
        Scheduled scheduled = PipelineForecastTrigger.class.getMethod("pickUp").getAnnotation(Scheduled.class);
        assertSoftly(softly -> {
            softly.assertThat(pipeline.pickUps).isOne();
            softly.assertThat(scheduled.scheduler()).isEqualTo("forecastTaskScheduler");
            softly.assertThat(scheduled.initialDelay()).isZero();
            softly.assertThat(scheduled.fixedDelayString()).isEqualTo("${forecast.interval}");
        });
    }

    private static ObservationDeliveryBatch batch(ObservationDeliveryBatch.Kind kind) {
        return new ObservationDeliveryBatch(11L, 7L, 1, kind, observedAt(kind), List.of());
    }

    private static Instant observedAt(ObservationDeliveryBatch.Kind kind) {
        if (kind == ObservationDeliveryBatch.Kind.STORED) {
            return Instant.EPOCH;
        }
        return null;
    }

    private static final class RecordingPipeline implements ForecastPipeline {
        private final List<String> stored = new ArrayList<>();
        private int pickUps;

        @Override
        public void batchStored(final long observationBatchId, final long routeVersionId) {
            stored.add(observationBatchId + ":" + routeVersionId);
        }

        @Override
        public void pickUp() {
            pickUps++;
        }
    }
}

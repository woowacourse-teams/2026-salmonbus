package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.forecast.RunForecasts;
import com.gustler.backend.observations.api.ObservationChange;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ForecastSchedulerTest {

    private final RecordingForecasts forecasts = new RecordingForecasts();
    private final ForecastScheduler trigger = new ForecastScheduler(forecasts);

    @AfterEach
    void 트랜잭션_동기화를_정리한다() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 관측_저장이_커밋된_뒤에만_그_배치를_예보에_넘긴다() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        trigger.record(batch(ObservationChange.Kind.STORED));
        assertThat(forecasts.stored).isEmpty();

        // when
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        // then
        assertThat(forecasts.stored).containsExactly("11:7");
    }

    @Test
    void 롤백된_관측과_관측_삭제_전달은_넘기지_않는다() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        trigger.record(batch(ObservationChange.Kind.STORED));
        trigger.record(batch(ObservationChange.Kind.CLEARED));

        // when
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        // then
        assertSoftly(checks -> {
            checks.assertThat(forecasts.stored).isEmpty();
            checks.assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        });
    }

    @Test
    void 줍기의_주기_실행은_한_주기_뒤에_시작해_예보_전용_스케줄러에서_돈다() throws Exception {
        // when
        trigger.pickUp();

        // then
        Scheduled scheduled = ForecastScheduler.class.getMethod("pickUp").getAnnotation(Scheduled.class);
        assertSoftly(softly -> {
            softly.assertThat(forecasts.pickUps).isOne();
            softly.assertThat(scheduled.scheduler()).isEqualTo("forecastTaskScheduler");
            softly.assertThat(scheduled.initialDelayString()).as("기동 직후의 첫 배치 조회는 모델을 적재한 뒤 호출한다")
                .isEqualTo("${forecast.interval}");
            softly.assertThat(scheduled.fixedDelayString()).isEqualTo("${forecast.interval}");
        });
    }

    private static ObservationChange batch(ObservationChange.Kind kind) {
        return new ObservationChange(11L, 7L, kind);
    }

    private static final class RecordingForecasts implements RunForecasts {

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

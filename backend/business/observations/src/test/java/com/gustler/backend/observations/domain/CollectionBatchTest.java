package com.gustler.backend.observations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class CollectionBatchTest {
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-09-23T08:00:00+09:00");
    private static final OffsetDateTime RECEIVED = START.plusSeconds(1);
    private static final ObservationBatchConclusion EMPTY =
        new ObservationBatchConclusion(ObservationBatchOutcome.SUCCESS_EMPTY, null, 4);

    @Test
    void 같은_계획을_재시도하면_시도번호를_올리고_이전_결과를_비운다() {
        CollectionBatch batch = dispatched();
        batch.conclude(EMPTY, RECEIVED);

        batch.startAttempt(true);

        assertThat(batch.state().attemptNumber()).isEqualTo(2);
        assertThat(batch.state().outcome()).isEqualTo(ObservationBatchOutcome.RESERVED);
        assertThat(batch.state().requestedAt()).isNull();
        assertThat(batch.state().responseReceivedAt()).isNull();
        assertThat(batch.state().providerRows()).isNull();
    }

    @Test
    void 입력으로_확정한_배치는_다시_열_수_없다() {
        CollectionBatch batch = dispatched();
        batch.conclude(EMPTY, RECEIVED);

        batch.confirmInput(RECEIVED.plusSeconds(1));

        assertThat(batch.state().inputConfirmedAt()).isEqualTo(RECEIVED.plusSeconds(1));
        assertThatThrownBy(() -> batch.startAttempt(true)).isInstanceOf(InputAlreadyConfirmedException.class);
    }

    private CollectionBatch dispatched() {
        CollectionBatch batch = saved(true);
        batch.dispatch(START);
        return batch;
    }

    private CollectionBatch saved(final boolean reserved) {
        var state = CollectionBatch.start(new CollectionPlan(10, START, "route-10-at-8"), reserved).state();
        return CollectionBatch.restore(new CollectionBatch.State(1L, state.routeVersionId(), state.scheduledAt(),
            state.attemptKey(), state.attemptNumber(), state.requestedAt(), state.responseReceivedAt(),
            state.inputConfirmedAt(), state.outcome(), state.failureCode(), state.resultCode(), state.providerRows(),
            state.storedRows(), state.excludedRows(), state.normalizationVersion(), state.collectionStrategyVersion()));
    }
}

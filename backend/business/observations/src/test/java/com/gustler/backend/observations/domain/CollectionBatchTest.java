package com.gustler.backend.observations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.OffsetDateTime;
import java.util.List;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;

class CollectionBatchTest {
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-09-23T08:00:00+09:00");
    private static final OffsetDateTime RECEIVED = START.plusSeconds(1);
    private static final CollectionResult EMPTY = CollectionResult.received(
            CollectionBatchStatus.SUCCESS_EMPTY,
            4,
            ObservationRows.fromObservations(List.of()),
            RECEIVED
    );

    @Test
    void 같은_계획을_재시도하면_시도번호를_올리고_이전_결과를_비운다() {
        // given
        CollectionBatch batch = dispatched();
        batch.conclude(EMPTY, RECEIVED);

        // when
        batch.startAttempt(true);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(batch.state().attemptNumber()).isEqualTo(2);
            softly.assertThat(batch.state().outcome()).isEqualTo(CollectionBatchStatus.DISPATCHING);
        }
        assertSoftly(checks -> {
            checks.assertThat(batch.state().requestedAt()).isNull();
            checks.assertThat(batch.state().responseReceivedAt()).isNull();
            checks.assertThat(batch.state().providerRows()).isNull();
        });
    }

    @Test
    void 입력_확인_시각이_있는_배치는_다시_열_수_없다() {
        // given
        CollectionBatch concluded = dispatched();
        concluded.conclude(EMPTY, RECEIVED);
        CollectionBatch.State state = concluded.state();
        CollectionBatch batch = CollectionBatch.restore(new CollectionBatch.State(
                state.id(),
                state.routeVersionId(),
                state.scheduledAt(),
                state.attemptKey(),
                state.attemptNumber(),
                state.requestedAt(),
                state.responseReceivedAt(),
                RECEIVED.plusSeconds(1),
                state.outcome(),
                state.failureCode(),
                state.resultCode(),
                state.providerRows(),
                state.storedRows(),
                state.excludedRows(),
                state.normalizationVersion(),
                state.collectionStrategyVersion()
        ));

        // when
        Throwable actualFailure = catchThrowable(() -> batch.startAttempt(true));

        // then
        assertSoftly(checks -> {
            checks.assertThat(actualFailure)
                .isNotNull().isInstanceOf(CollectionInputConfirmedException.class);
            checks.assertThat(batch.state().inputConfirmedAt()).isEqualTo(RECEIVED.plusSeconds(1));
        });
    }

    private CollectionBatch dispatched() {
        CollectionBatch batch = saved(true);

        return batch;
    }

    private CollectionBatch saved(final boolean reserved) {
        CollectionBatch.State state = CollectionBatch.start(new CollectionPlan(10, START, "route-10-at-8"),
            reserved).state();
        return CollectionBatch.restore(new CollectionBatch.State(
                1L,
                state.routeVersionId(),
                state.scheduledAt(),
                state.attemptKey(),
                state.attemptNumber(),
                state.requestedAt(),
                state.responseReceivedAt(),
                state.inputConfirmedAt(),
                state.outcome(),
                state.failureCode(),
                state.resultCode(),
                state.providerRows(),
                state.storedRows(),
                state.excludedRows(),
                state.normalizationVersion(),
                state.collectionStrategyVersion()
        ));
    }
}

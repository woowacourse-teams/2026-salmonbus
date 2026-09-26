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
        batch.completeAttempt(batch.token(), EMPTY, RECEIVED, 0, 0, 0);

        batch.startAttempt(true);

        assertThat(batch.token()).isEqualTo(new CollectionAttemptToken(1, 2));
        assertThat(batch.state().outcome()).isEqualTo(ObservationBatchOutcome.RESERVED);
        assertThat(batch.state().requestedAt()).isNull();
        assertThat(batch.state().responseReceivedAt()).isNull();
        assertThat(batch.state().providerRows()).isNull();
    }

    @Test
    void 이전_시도의_응답은_현재_결과를_변경하지_못한다() {
        CollectionBatch batch = dispatched();
        CollectionAttemptToken previous = batch.token();
        batch.startAttempt(true);
        batch.dispatch(batch.token(), START.plusSeconds(2));
        CollectionBatch.State before = batch.state();

        assertThatThrownBy(() -> batch.completeAttempt(previous, EMPTY, RECEIVED, 0, 0, 0))
            .isInstanceOf(StaleCollectionAttemptException.class);
        assertThat(batch.state()).isEqualTo(before);
    }

    @Test
    void 전송_전에는_수집을_완료할_수_없다() {
        CollectionBatch batch = saved(true);

        assertThatThrownBy(() -> batch.completeAttempt(batch.token(), EMPTY, RECEIVED, 0, 0, 0))
            .isInstanceOf(InvalidCollectionStateException.class);
    }

    @Test
    void 같은_완료_결과를_다시_전달하면_변경하지_않는다() {
        CollectionBatch batch = dispatched();
        assertThat(batch.completeAttempt(batch.token(), EMPTY, RECEIVED, 0, 0, 0)).isTrue();
        CollectionBatch.State completed = batch.state();

        assertThat(batch.completeAttempt(batch.token(), EMPTY, RECEIVED, 0, 0, 0)).isFalse();
        assertThat(batch.state()).isEqualTo(completed);
    }

    @Test
    void 같은_시도의_결과를_다른_결과로_덮어쓸_수_없다() {
        CollectionBatch batch = dispatched();
        batch.completeAttempt(batch.token(), EMPTY, RECEIVED, 0, 0, 0);
        var failure = new ObservationBatchConclusion(ObservationBatchOutcome.UNKNOWN_AFTER_DISPATCH, null, null);

        assertThatThrownBy(() -> batch.completeAttempt(batch.token(), failure, RECEIVED, null, null, null))
            .isInstanceOf(ConflictingCollectionResultException.class);
        assertThat(batch.state().outcome()).isEqualTo(ObservationBatchOutcome.SUCCESS_EMPTY);
    }

    @Test
    void 실제_시각이_같은_다른_오프셋의_재전달도_동일_결과다() {
        CollectionBatch batch = dispatched();
        batch.completeAttempt(batch.token(), EMPTY, RECEIVED, 0, 0, 0);

        assertThat(batch.completeAttempt(batch.token(), EMPTY,
            RECEIVED.withOffsetSameInstant(java.time.ZoneOffset.UTC), 0, 0, 0)).isFalse();
    }

    @Test
    void 저장_정밀도보다_작은_시각_차이는_같은_응답으로_처리한다() {
        CollectionBatch batch = dispatched();
        OffsetDateTime received = RECEIVED.plusNanos(999);
        batch.completeAttempt(batch.token(), EMPTY, received, 0, 0, 0);

        assertThat(batch.state().responseReceivedAt()).isEqualTo(RECEIVED);
        assertThat(batch.completeAttempt(batch.token(), EMPTY, received, 0, 0, 0)).isFalse();
    }

    @Test
    void 정상_응답의_제공_행수는_저장과_제외_행수의_합이어야_한다() {
        CollectionBatch batch = dispatched();
        var success = new ObservationBatchConclusion(ObservationBatchOutcome.SUCCESS_ROWS, null, 0);

        assertThatThrownBy(() -> batch.completeAttempt(batch.token(), success, RECEIVED, 3, 1, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(batch.state().outcome()).isEqualTo(ObservationBatchOutcome.DISPATCHING);
    }

    @Test
    void 한도가_없는_시도는_전송할_수_없다() {
        CollectionBatch batch = saved(false);

        assertThatThrownBy(() -> batch.dispatch(batch.token(), START))
            .isInstanceOf(InvalidCollectionStateException.class);
        assertThat(batch.state().failureCode()).isEqualTo(ObservationBatchFailureCode.LOCAL_QUOTA_EXHAUSTED);
    }

    @Test
    void 이미_전송한_시도는_추가_전송을_요청하지_않는다() {
        CollectionBatch batch = dispatched();

        assertThat(batch.isAwaitingDispatch(batch.token())).isFalse();
        assertThat(batch.state().requestedAt()).isEqualTo(START);
    }

    @Test
    void 전송_후에는_보내기_전_취소로_바꿀_수_없다() {
        CollectionBatch batch = dispatched();

        assertThatThrownBy(() -> batch.abandonBeforeSend(batch.token()))
            .isInstanceOf(InvalidCollectionStateException.class);
    }

    @Test
    void 비어_있는_정상_수집도_입력으로_확정할_수_있다() {
        CollectionBatch batch = dispatched();
        batch.completeAttempt(batch.token(), EMPTY, RECEIVED, 0, 0, 0);

        batch.confirmInput(batch.token(), RECEIVED.plusSeconds(1));

        assertThat(batch.state().inputConfirmedAt()).isEqualTo(RECEIVED.plusSeconds(1));
        assertThatThrownBy(() -> batch.startAttempt(true)).isInstanceOf(InputAlreadyConfirmedException.class);
    }

    @Test
    void 입력_확정을_다시_요청해도_최초_시각을_보존한다() {
        CollectionBatch batch = dispatched();
        batch.completeAttempt(batch.token(), EMPTY, RECEIVED, 0, 0, 0);
        batch.confirmInput(batch.token(), RECEIVED.plusSeconds(1));

        batch.confirmInput(batch.token(), RECEIVED.plusSeconds(2));

        assertThat(batch.state().inputConfirmedAt()).isEqualTo(RECEIVED.plusSeconds(1));
    }

    @Test
    void 실패한_수집은_입력으로_확정할_수_없다() {
        CollectionBatch batch = dispatched();
        var failure = new ObservationBatchConclusion(ObservationBatchOutcome.UNKNOWN_AFTER_DISPATCH, null, null);
        batch.completeAttempt(batch.token(), failure, RECEIVED, null, null, null);

        assertThatThrownBy(() -> batch.confirmInput(batch.token(), RECEIVED.plusSeconds(1)))
            .isInstanceOf(InvalidCollectionStateException.class);
    }

    private CollectionBatch dispatched() {
        CollectionBatch batch = saved(true);
        batch.dispatch(batch.token(), START);
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

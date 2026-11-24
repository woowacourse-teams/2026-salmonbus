package com.gustler.backend.observations.infrastructure.jpa;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.observations.domain.CollectionBatchStatus;
import com.gustler.backend.observations.domain.CollectionInputConfirmedException;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.CollectionResult;
import com.gustler.backend.observations.domain.ObservationRows;
import com.gustler.backend.observations.infrastructure.jdbc.VehicleObservationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CollectionBatchRepositoryTest {

    private static final OffsetDateTime START = OffsetDateTime.parse("2026-09-23T08:00:00+09:00");
    private static final OffsetDateTime RECEIVED = START.plusSeconds(1);
    private static final CollectionPlan PLAN = new CollectionPlan(10, START, "route-10-at-8");
    private static final CollectionResult EMPTY = CollectionResult.received(
            CollectionBatchStatus.SUCCESS_EMPTY,
            4,
            ObservationRows.fromObservations(List.of()),
            RECEIVED
    );

    @Test
    void 같은_계획을_재시도하면_시도번호를_올리고_이전_결과를_비운다() {
        // given
        ObservationBatchJpaEntity batch = completedBatch();
        ObservationBatchEntityRepository entities = mock(ObservationBatchEntityRepository.class);
        when(entities.findByRouteVersionIdAndAttemptKey(PLAN.routeVersionId(), PLAN.attemptKey()))
            .thenReturn(Optional.of(batch));
        CollectionBatchRepository batches = new CollectionBatchRepository(
            entities, mock(VehicleObservationRepository.class));

        // when
        batches.prepare(PLAN, true);

        // then
        assertSoftly(checks -> {
            checks.assertThat(batch.getAttemptNumber()).isEqualTo(2);
            checks.assertThat(batch.getOutcome()).isEqualTo(CollectionBatchStatus.DISPATCHING);
            checks.assertThat(batch.getRequestedAt()).isNull();
            checks.assertThat(batch.getResponseReceivedAt()).isNull();
            checks.assertThat(batch.getResultCode()).isNull();
            checks.assertThat(batch.getProviderRows()).isNull();
            checks.assertThat(batch.getStoredRows()).isNull();
            checks.assertThat(batch.getExcludedRows()).isNull();
        });
    }

    @Test
    void 입력_확인_시각이_있는_배치는_다시_열_수_없다() {
        // given
        ObservationBatchJpaEntity batch = completedBatch();
        ReflectionTestUtils.setField(batch, "inputConfirmedAt", RECEIVED.plusSeconds(1));
        ObservationBatchEntityRepository entities = mock(ObservationBatchEntityRepository.class);
        when(entities.findByRouteVersionIdAndAttemptKey(PLAN.routeVersionId(), PLAN.attemptKey()))
            .thenReturn(Optional.of(batch));
        VehicleObservationRepository observations = mock(VehicleObservationRepository.class);
        CollectionBatchRepository batches = new CollectionBatchRepository(entities, observations);

        // when
        Throwable actualFailure = catchThrowable(() -> batches.prepare(PLAN, true));

        // then
        assertSoftly(checks -> {
            checks.assertThat(actualFailure).isInstanceOf(CollectionInputConfirmedException.class);
            checks.assertThat(batch.getInputConfirmedAt()).isEqualTo(RECEIVED.plusSeconds(1));
            checks.assertThat(batch.getAttemptNumber()).isOne();
            checks.assertThat(batch.getOutcome()).isEqualTo(CollectionBatchStatus.SUCCESS_EMPTY);
        });
        verifyNoInteractions(observations);
    }

    private ObservationBatchJpaEntity completedBatch() {
        ObservationBatchJpaEntity batch = new ObservationBatchJpaEntity(PLAN, true);
        ReflectionTestUtils.setField(batch, "id", 1L);
        batch.conclude(EMPTY, RECEIVED);
        return batch;
    }
}

package com.gustler.backend.observations.infrastructure.jpa;

import com.gustler.backend.observations.api.CollectionInput;
import com.gustler.backend.observations.api.CollectionInputs;
import com.gustler.backend.observations.domain.CollectionAttemptToken;
import com.gustler.backend.observations.domain.CollectionBatch;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaCollectionInputs implements CollectionInputs {
    private final CollectorObservationBatchRepository batches;
    private final JdbcClient jdbc;

    public JpaCollectionInputs(CollectorObservationBatchRepository batches, JdbcClient jdbc) {
        this.batches = batches;
        this.jdbc = jdbc;
    }

    @Override
    public CollectionInput lockForForecast(final long batchId) {
        return inputOf(batches.lockById(batchId).orElseThrow().toDomain());
    }

    @Override
    public CollectionInput lockForObservation(final long observationId) {
        final long batchId = jdbc.sql("SELECT observation_batch_id FROM vehicle_observation WHERE id = ?")
            .param(observationId).query(Long.class).optional()
            .orElseThrow(() -> new NoSuchElementException("관측이 더 이상 존재하지 않는다: " + observationId));
        CollectionInput input = lockForForecast(batchId);
        // 첫 조회 이후 잠금을 기다리는 동안 새 시도가 원 관측을 삭제했을 수 있다.
        final boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM vehicle_observation WHERE id = ? AND observation_batch_id = ?)")
            .param(observationId).param(batchId).query(Boolean.class).single();
        if (!exists) {
            throw new NoSuchElementException("수집 재시도로 관측이 교체되었다: " + observationId);
        }
        return input;
    }

    @Override
    public void confirmInput(final long batchId, final int attemptNumber, Instant confirmedAt) {
        ObservationBatchJpaEntity entity = batches.lockById(batchId).orElseThrow();
        CollectionBatch batch = entity.toDomain();
        batch.confirmInput(new CollectionAttemptToken(batchId, attemptNumber), confirmedAt.atOffset(ZoneOffset.UTC));
        entity.apply(batch);
        batches.flush();
    }

    private static CollectionInput inputOf(CollectionBatch batch) {
        CollectionBatch.State state = batch.state();
        return new CollectionInput(state.id(), state.routeVersionId(), state.attemptNumber(),
            state.responseReceivedAt() == null ? null : state.responseReceivedAt().toInstant(),
            batch.successful(), state.inputConfirmedAt() != null);
    }
}

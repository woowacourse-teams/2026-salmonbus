package com.gustler.backend.observations.infrastructure.jdbc;

import com.gustler.backend.observations.api.CollectionInput;
import com.gustler.backend.observations.api.CollectionInputs;
import com.gustler.backend.observations.domain.CollectionAttemptToken;
import com.gustler.backend.observations.domain.CollectionBatch;
import com.gustler.backend.observations.domain.ObservationBatchFailureCode;
import com.gustler.backend.observations.domain.ObservationBatchOutcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JPA를 시작하지 않는 정비 앱에서 사용하는 관측 입력 계약 구현. */
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcCollectionInputs implements CollectionInputs {
    private final JdbcClient jdbc;

    public JdbcCollectionInputs(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CollectionInput lockForForecast(final long batchId) {
        return inputOf(lockBatch(batchId));
    }

    @Override
    public CollectionInput lockForObservation(final long observationId) {
        final long batchId = jdbc.sql("SELECT observation_batch_id FROM vehicle_observation WHERE id = ?")
            .param(observationId).query(Long.class).optional()
            .orElseThrow(() -> new NoSuchElementException("관측이 더 이상 존재하지 않는다: " + observationId));
        CollectionInput input = inputOf(lockBatch(batchId));
        final boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM vehicle_observation WHERE id = ? AND observation_batch_id = ?)")
            .param(observationId).param(batchId).query(Boolean.class).single();
        if (!exists) {
            throw new NoSuchElementException("수집 재시도로 관측이 교체되었다: " + observationId);
        }
        return input;
    }

    @Override
    public void confirmInput(final long batchId, final int attemptNumber, Instant confirmedAt) {
        CollectionBatch batch = lockBatch(batchId);
        batch.confirmInput(new CollectionAttemptToken(batchId, attemptNumber), confirmedAt.atOffset(ZoneOffset.UTC));
        jdbc.sql("UPDATE observation_batch SET input_confirmed_at = ? WHERE id = ? AND attempt_number = ?")
            .param(batch.state().inputConfirmedAt()).param(batchId).param(attemptNumber).update();
    }

    private CollectionBatch lockBatch(final long batchId) {
        return jdbc.sql("SELECT * FROM observation_batch WHERE id = ? FOR UPDATE")
            .param(batchId).query((row, index) -> restore(row)).optional().orElseThrow();
    }

    private static CollectionBatch restore(ResultSet row) throws SQLException {
        String failure = row.getString("failure_code");
        return CollectionBatch.restore(new CollectionBatch.State(row.getLong("id"), row.getLong("route_version_id"),
            row.getObject("scheduled_at", OffsetDateTime.class), row.getString("attempt_key"),
            row.getInt("attempt_number"), row.getObject("requested_at", OffsetDateTime.class),
            row.getObject("response_received_at", OffsetDateTime.class), row.getObject("input_confirmed_at", OffsetDateTime.class),
            ObservationBatchOutcome.valueOf(row.getString("outcome")),
            failure == null ? null : ObservationBatchFailureCode.valueOf(failure), row.getObject("result_code", Integer.class),
            row.getObject("provider_rows", Integer.class), row.getObject("stored_rows", Integer.class),
            row.getObject("excluded_rows", Integer.class), row.getString("normalization_version"),
            row.getString("collection_strategy_version")));
    }

    private static CollectionInput inputOf(CollectionBatch batch) {
        CollectionBatch.State state = batch.state();
        return new CollectionInput(state.id(), state.routeVersionId(), state.attemptNumber(),
            state.responseReceivedAt() == null ? null : state.responseReceivedAt().toInstant(),
            batch.successful(), state.inputConfirmedAt() != null);
    }
}

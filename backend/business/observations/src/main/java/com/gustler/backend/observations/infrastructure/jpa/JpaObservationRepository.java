package com.gustler.backend.observations.infrastructure.jpa;

import com.gustler.backend.observations.domain.StoredObservations;
import com.gustler.backend.observations.domain.CollectedObservations;
import com.gustler.backend.observations.domain.CollectionAttemptToken;
import com.gustler.backend.observations.domain.CollectionBatch;
import com.gustler.backend.observations.domain.ConflictingCollectionResultException;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.ObservationBatchConclusion;
import com.gustler.backend.observations.domain.ObservationRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JpaObservationRepository implements ObservationRepository {
    private static final int PLAN_LOCK_NAMESPACE = 13401;
    private final CollectorObservationBatchRepository batches;
    private final CollectorVehicleObservationRepository observations;
    private final JdbcClient jdbc;

    public JpaObservationRepository(CollectorObservationBatchRepository batches,
                                    CollectorVehicleObservationRepository observations,
                                    JdbcClient jdbc) {
        this.batches = batches;
        this.observations = observations;
        this.jdbc = jdbc;
    }

    @Override
    public void lockPlan(CollectionPlan plan) {
        // 아직 행이 없는 첫 수집도 같은 계획끼리 직렬화한다. 한도 예약보다 먼저 실행한다.
        jdbc.sql("SELECT pg_advisory_xact_lock(?, hashtext(?))")
            .param(PLAN_LOCK_NAMESPACE).param(plan.routeVersionId() + ":" + plan.attemptKey())
            .query().singleRow();
        batches.findByRouteVersionIdAndAttemptKey(plan.routeVersionId(), plan.attemptKey())
            .ifPresent(existing -> existing.toDomain().requireCanStartAttempt());
    }

    @Override
    public CollectionAttemptToken openReserved(CollectionPlan plan) {
        return open(plan, true);
    }

    @Override
    public CollectionAttemptToken openNotReserved(CollectionPlan plan) {
        return open(plan, false);
    }

    @Override
    public boolean isAwaitingDispatch(CollectionAttemptToken token) {
        return batchOf(token).toDomain().isAwaitingDispatch(token);
    }

    @Override
    public void markDispatching(CollectionAttemptToken token, OffsetDateTime requestedAt) {
        ObservationBatchJpaEntity entity = batchOf(token);
        CollectionBatch batch = entity.toDomain();
        batch.dispatch(token, requestedAt);
        entity.apply(batch);
    }

    @Override
    public void abandonBeforeSend(CollectionAttemptToken token) {
        ObservationBatchJpaEntity entity = batchOf(token);
        CollectionBatch batch = entity.toDomain();
        batch.abandonBeforeSend(token);
        entity.apply(batch);
    }

    @Override
    public void concludeWithoutRows(CollectionAttemptToken token, ObservationBatchConclusion conclusion,
                                    OffsetDateTime responseReceivedAt) {
        ObservationBatchJpaEntity entity = batchOf(token);
        CollectionBatch batch = entity.toDomain();
        batch.completeAttempt(token, conclusion, responseReceivedAt, null, null, null);
        entity.apply(batch);
    }

    @Override
    public long routeVersionOf(final long batchId) {
        return jdbc.sql("SELECT route_version_id FROM observation_batch WHERE id = ?")
            .param(batchId).query(Long.class).single();
    }

    @Override
    public Optional<StoredObservations> concludeWithRows(CollectionAttemptToken token,
                                                        ObservationBatchConclusion conclusion,
                                                        CollectedObservations collected,
                                                        OffsetDateTime responseReceivedAt) {
        ObservationBatchJpaEntity entity = batchOf(token);
        CollectionBatch batch = entity.toDomain();
        final boolean changed = batch.completeAttempt(token, conclusion, responseReceivedAt,
            collected.providerRows(), collected.storableRows().size(), collected.excludedRows().size());
        if (!changed) {
            var existingRows = observations.findByObservationBatchIdOrderBySourceRowNumber(token.batchId()).stream()
                .map(VehicleObservationJpaEntity::toDomain).toList();
            if (!existingRows.equals(collected.storableRows())) {
                throw new ConflictingCollectionResultException(token.batchId());
            }
            return Optional.empty();
        }
        final long routeVersionId = batch.state().routeVersionId();
        entity.apply(batch);
        // JDBC 품질 처리가 같은 트랜잭션에서 새 수집 상태와 생성 ID를 조회할 수 있게 반영한다.
        batches.flush();
        var stored = observations.saveAllAndFlush(collected.storableRows().stream()
            .map(row -> new VehicleObservationJpaEntity(token.batchId(), routeVersionId, row)).toList());
        return Optional.of(new StoredObservations(token.batchId(), routeVersionId,
            batch.state().responseReceivedAt().toInstant(),
            stored.stream().map(VehicleObservationJpaEntity::storedRow).toList()));
    }

    private CollectionAttemptToken open(CollectionPlan plan, final boolean reserved) {
        lockPlan(plan);
        var existing = batches.findByRouteVersionIdAndAttemptKey(plan.routeVersionId(), plan.attemptKey());
        if (existing.isPresent()) {
            ObservationBatchJpaEntity entity = existing.orElseThrow();
            CollectionBatch batch = entity.toDomain();
            batch.startAttempt(reserved);
            observations.deleteByObservationBatchId(entity.getId());
            entity.apply(batch);
            return batch.token();
        }
        var saved = batches.saveAndFlush(new ObservationBatchJpaEntity(CollectionBatch.start(plan, reserved)));
        return saved.toDomain().token();
    }

    private ObservationBatchJpaEntity batchOf(CollectionAttemptToken token) {
        ObservationBatchJpaEntity entity = batches.lockById(token.batchId()).orElseThrow();
        entity.toDomain().requireAttempt(token);
        return entity;
    }
}

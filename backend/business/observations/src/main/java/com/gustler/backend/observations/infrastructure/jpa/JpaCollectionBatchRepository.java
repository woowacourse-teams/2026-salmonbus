package com.gustler.backend.observations.infrastructure.jpa;

import com.gustler.backend.observations.domain.CollectionBatch;
import com.gustler.backend.observations.domain.CollectionBatchRepository;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.StoredObservations.Row;
import com.gustler.backend.observations.domain.UpstreamObservationRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집 계획의 배치와 정규화된 관측을 저장한다.
 * 같은 계획의 재시도는 기존 배치의 저장 계약을 유지한다.
 *
 * <p>{@code collection.bulk-insert}가 켜져 있으면 관측을 INSERT 한 문장으로 넣는다. 꺼져 있으면 JPA 가 관측 한 줄마다
 * 문장 하나를 낸다. 어느 쪽이든 저장 열의 값과 반환하는 행은 같다.
 */
@Repository
@Transactional(readOnly = false, propagation = Propagation.MANDATORY)
public class JpaCollectionBatchRepository implements CollectionBatchRepository {
    private final ObservationBatchEntityRepository batches;
    private final VehicleObservationEntityRepository observations;
    private final VehicleObservationBulkInsert bulkInsert;
    private final boolean bulkInsertEnabled;

    public JpaCollectionBatchRepository(
        ObservationBatchEntityRepository batches,
        VehicleObservationEntityRepository observations,
        VehicleObservationBulkInsert bulkInsert,
        @Value("${collection.bulk-insert:false}") final boolean bulkInsertEnabled
    ) {
        this.batches = batches;
        this.observations = observations;
        this.bulkInsert = bulkInsert;
        this.bulkInsertEnabled = bulkInsertEnabled;
    }

    @Override
    public Optional<CollectionBatch> findByPlan(CollectionPlan plan) {
        return batches.findByRouteVersionIdAndAttemptKey(plan.routeVersionId(), plan.attemptKey())
            .map(ObservationBatchJpaEntity::toDomain);
    }

    @Override
    public Optional<OffsetDateTime> findForecastStart(final long batchId) {
        return batches.findForecastStart(batchId).map(start -> start.atOffset(ZoneOffset.UTC));
    }

    @Override
    public CollectionBatch getById(final long batchId) {
        return batches.findById(batchId).orElseThrow().toDomain();
    }

    @Override
    public long save(CollectionBatch batch) {
        if (batch.id() == null) {
            return batches.save(new ObservationBatchJpaEntity(batch)).getId();
        }
        batches.findById(batch.id()).orElseThrow().apply(batch);
        batches.flush();
        return batch.id();
    }

    @Override
    public int closeUnfinished(final long batchId) {
        return batches.closeUnfinished(batchId);
    }

    @Override
    public List<Long> lockUnfinished(final int limit) {
        return batches.lockUnfinished(limit);
    }

    @Override
    public void deleteObservationsOf(final long batchId) {
        observations.deleteByObservationBatchId(batchId);
    }

    /**
     * 한 문장 저장은 JDBC 로 같은 트랜잭션의 커넥션을 쓴다. 그 전에 영속성 컨텍스트의 대기 변경을 먼저 내보내
     * 문장이 JPA 로 바꾼 행을 보게 한다. 넣은 관측은 영속성 컨텍스트에 올리지 않는다.
     */
    @Override
    public List<Row> saveObservations(CollectionBatch batch, List<UpstreamObservationRow> rows) {
        if (bulkInsertEnabled) {
            observations.flush();
            return bulkInsert.insert(batch.id(), batch.routeVersionId(), rows);
        }
        return observations.saveAll(rows.stream()
            .map(row -> new VehicleObservationJpaEntity(batch.id(), batch.routeVersionId(), row))
            .toList())
            .stream()
            .map(VehicleObservationJpaEntity::storedRow)
            .toList();
    }
}

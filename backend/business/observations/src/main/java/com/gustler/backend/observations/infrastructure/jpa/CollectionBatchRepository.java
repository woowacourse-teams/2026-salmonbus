package com.gustler.backend.observations.infrastructure.jpa;

import com.gustler.backend.observations.domain.CollectionInputConfirmedException;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.CollectionPreparation;
import com.gustler.backend.observations.domain.CollectionResult;
import com.gustler.backend.observations.domain.ObservationRows;
import com.gustler.backend.observations.infrastructure.jdbc.VehicleObservationRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 조회한 배치 엔티티를 직접 변경하고 관측과 배치를 정해진 순서로 저장한다. */
@Repository
@Transactional(readOnly = false, propagation = Propagation.MANDATORY)
public class CollectionBatchRepository {

    private final ObservationBatchEntityRepository batches;
    private final VehicleObservationRepository observations;

    public CollectionBatchRepository(
        ObservationBatchEntityRepository batches,
        VehicleObservationRepository observations
    ) {
        this.batches = batches;
        this.observations = observations;
    }

    /** 기존 배치는 예보 입력 확정 여부를 확인하고 이전 관측을 삭제한 뒤 다음 수집 시도를 시작한다. */
    public CollectionPreparation.Batch prepare(CollectionPlan plan, final boolean reserved) {
        Optional<ObservationBatchJpaEntity> existing =
            batches.findByRouteVersionIdAndAttemptKey(plan.routeVersionId(), plan.attemptKey());
        if (existing.isEmpty()) {
            ObservationBatchJpaEntity created = batches.save(new ObservationBatchJpaEntity(plan, reserved));
            return new CollectionPreparation.Batch(created.getId(), false);
        }

        ObservationBatchJpaEntity batch = existing.orElseThrow();
        verifyRetryAllowed(batch);

        batches.flush();
        observations.deleteByObservationBatchId(batch.getId());

        batch.startNextAttempt(reserved);
        batches.flush();
        return new CollectionPreparation.Batch(batch.getId(), true);
    }

    private void verifyRetryAllowed(ObservationBatchJpaEntity batch) {
        Optional<OffsetDateTime> forecastStartedAt = batches.findForecastStart(batch.getId())
            .map(start -> start.atOffset(ZoneOffset.UTC));
        if (forecastStartedAt.isPresent()) {
            throw new CollectionInputConfirmedException(batch.getId(), forecastStartedAt.orElseThrow());
        }

        OffsetDateTime inputConfirmedAt = batch.getInputConfirmedAt();
        if (inputConfirmedAt != null) {
            throw new CollectionInputConfirmedException(batch.getId(), inputConfirmedAt);
        }
    }

    /** 관측 INSERT를 마친 뒤 배치를 완료 처리한다. 품질 검사와 변경 알림에 사용할 노선 버전 ID를 반환한다. */
    public long conclude(final long batchId, CollectionResult result, OffsetDateTime receivedAt) {
        ObservationBatchJpaEntity batch = batches.findById(batchId).orElseThrow();
        if (result.observations().isPresent()) {
            ObservationRows rows = result.observations().orElseThrow();
            batches.flush();
            observations.insertAll(batchId, batch.getRouteVersionId(), rows.storableRows());
        }

        batch.conclude(result, receivedAt);
        batches.flush();
        return batch.getRouteVersionId();
    }

    public void abandonBeforeSend(final long batchId) {
        ObservationBatchJpaEntity batch = batches.findById(batchId).orElseThrow();
        batch.abandonBeforeSend();
        batches.flush();
    }

    public int closeUnfinished(final long batchId) {
        return batches.closeUnfinished(batchId);
    }

    public List<Long> lockUnfinished(final int limit) {
        return batches.lockUnfinished(limit);
    }
}

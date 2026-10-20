package com.gustler.backend.forecasting.infrastructure.observations;

import com.gustler.backend.forecasting.application.evaluation.ObservationDeliveryReceiver;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.application.quality.WorkerQualityState;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.api.ObservationDeliveryHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(prefix = "forecast.evaluation.observation-cache", name = "enabled", havingValue = "true")
public class ObservationDeliveryAdapter implements ObservationDeliveryHook {
    private static final Logger log = LoggerFactory.getLogger(ObservationDeliveryAdapter.class);
    private final RouteDataQualityAccess quality;
    private final ObjectProvider<ObservationDeliveryReceiver> receivers;
    private WorkerQualityState qualityMemory;

    @Autowired(required = false)
    public void useQualityMemory(WorkerQualityState qualityMemory) { this.qualityMemory = qualityMemory; }

    public ObservationDeliveryAdapter(RouteDataQualityAccess quality,
        ObjectProvider<ObservationDeliveryReceiver> receivers) {
        this.quality = quality;
        this.receivers = receivers;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(ObservationDeliveryBatch batch) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("관측 전달에는 트랜잭션 동기화가 필요하다");
        }
        if (qualityMemory != null && batch.kind() == ObservationDeliveryBatch.Kind.STORED) {
            recordWithMemory(batch);
            return;
        }
        long revision = batch.kind() == ObservationDeliveryBatch.Kind.STORED
            ? quality.current(batch.routeVersionId()) : -1;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deliver(revision, batch);
            }
        });
    }

    private void recordWithMemory(ObservationDeliveryBatch batch) {
        final java.util.Optional<WorkerQualityState.Read> remembered;
        try {
            remembered = qualityMemory.read(batch.routeVersionId());
        } catch (RuntimeException failure) {
            // 캐시 조회 예외는 다시 던지지 않는다. DB 자체 오류의 롤백 여부는 수집 트랜잭션이 결정한다.
            log.warn(
                "event=observation_cache_publish_skipped reason=QUALITY_READ_FAILED batchId={} exceptionType={}",
                batch.batchId(), failure.getClass().getSimpleName());
            return;
        }
        // 조사 중인 원본 관측을 현재 품질 판정을 마친 관측으로 보관하지 않는다.
        if (remembered.isEmpty() || remembered.get().snapshot().anyInvestigationPending()) return;
        var read = remembered.get();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                qualityMemory.publishIfCurrent(read, () -> deliver(read.snapshot().revision(), batch));
            }
        });
    }

    private void deliver(long revision, ObservationDeliveryBatch batch) {
        try {
            ObservationDeliveryReceiver receiver = receivers.getIfAvailable();
            if (receiver != null) receiver.receive(revision, batch);
            // 콜백에서는 DB 연결을 빌리지 않는다. 누락은 이후 정산 조회에서 보완한다.
        } catch (RuntimeException failure) {
            // 이미 커밋한 수집을 실패로 바꾸지 않는다. 캐시가 비면 DB에서 읽는다.
            log.warn("event=observation_cache_publish_failed batchId={} attempt={} exceptionType={}",
                batch.batchId(), batch.attempt(), failure.getClass().getSimpleName());
        }
    }
}

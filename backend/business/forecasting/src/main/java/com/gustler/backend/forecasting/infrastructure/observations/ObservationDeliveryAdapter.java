package com.gustler.backend.forecasting.infrastructure.observations;

import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.application.evaluation.ObservationDeliveryReceiver;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.api.ObservationDeliveryHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 수집 트랜잭션에서 입력 판본을 확인하고 커밋 뒤 메모리에 전달한다. 전달 콜백에서 새 DB 연결을 빌리지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "forecast.evaluation.observation-cache", name = "enabled", havingValue = "true")
public class ObservationDeliveryAdapter implements ObservationDeliveryHook {
    private static final Logger LOGGER = LoggerFactory.getLogger(ObservationDeliveryAdapter.class);
    private final RouteWorkAccess routeWork;
    private final ObjectProvider<ObservationDeliveryReceiver> receivers;

    public ObservationDeliveryAdapter(RouteWorkAccess routeWork,
        ObjectProvider<ObservationDeliveryReceiver> receivers) {
        this.routeWork = routeWork;
        this.receivers = receivers;
    }

    @Override
    @Transactional(readOnly = false, propagation = Propagation.MANDATORY)
    public void record(ObservationDeliveryBatch batch) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("관측 전달에는 트랜잭션 동기화가 필요하다");
        }
        final long revision = readQualityRevision(batch);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deliver(revision, batch);
            }
        });
    }

    private void deliver(final long revision, ObservationDeliveryBatch batch) {
        try {
            ObservationDeliveryReceiver receiver = receivers.getIfAvailable();
            if (receiver != null) {
                receiver.receive(revision, batch);
            }
            // 콜백에서는 DB 연결을 빌리지 않는다. 누락은 이후 정산 조회에서 보완한다.
        } catch (RuntimeException failure) {
            // 이미 커밋한 수집을 실패로 바꾸지 않는다. 캐시가 비면 DB에서 읽는다.
            LOGGER.warn("event=observation_cache_publish_failed batchId={} attempt={} exceptionType={}",
                batch.batchId(), batch.attempt(), failure.getClass().getSimpleName());
        }
    }

    private long readQualityRevision(ObservationDeliveryBatch batch) {
        if (batch.kind() == ObservationDeliveryBatch.Kind.STORED) {
            return routeWork.current(batch.routeVersionId());
        }
        return -1;
    }
}

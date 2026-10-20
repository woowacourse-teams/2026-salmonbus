package com.gustler.backend.forecasting.application;

import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 실제 transaction이 없으면 성공을 추정하지 않으며 관측 실패가 커밋 결과를 뒤집지 않는다. */
public final class AfterCommitTelemetry {
    private AfterCommitTelemetry() {
    }

    /**
     * 실제 트랜잭션과 동기화가 활성화된 경우에만 커밋 후 기록을 등록한다. 거래가 없거나 롤백하면 성공 지표를 남기지 않는다.
     */
    public static void record(Runnable callback) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    callback.run();
                }
                catch (RuntimeException failure) {
                    LoggerFactory.getLogger(AfterCommitTelemetry.class).warn(
                        "event=telemetry_delivery_failed exceptionType={}", failure.getClass().getSimpleName());
                }
            }
        });
    }
}

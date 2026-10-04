package com.gustler.backend.forecasting.application;

import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 실제 transaction이 없으면 성공을 추정하지 않으며, 관측 실패가 커밋 결과를 뒤집지 않는다. */
public final class AfterCommitTelemetry {
    private AfterCommitTelemetry() { }
    public static void record(Runnable callback) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) { return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { callback.run(); }
                catch (RuntimeException failure) {
                    LoggerFactory.getLogger(AfterCommitTelemetry.class).warn(
                        "event=telemetry_delivery_failed exceptionType={}", failure.getClass().getSimpleName());
                }
            }
        });
    }
}

package com.gustler.backend.forecasting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AfterCommitTelemetryTest {
    @AfterEach void clear() { TransactionSynchronizationManager.clear(); }
    @Test void 커밋_전에_성공을_기록하지_않고_커밋_후에만_기록한다() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        AtomicInteger recorded = new AtomicInteger();
        AfterCommitTelemetry.record(recorded::incrementAndGet);
        assertThat(recorded).hasValue(0);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(recorded).hasValue(1);
    }
    @Test void 롤백과_트랜잭션_없는_호출은_성공으로_기록하지_않는다() {
        AtomicInteger recorded = new AtomicInteger();
        AfterCommitTelemetry.record(recorded::incrementAndGet);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        AfterCommitTelemetry.record(recorded::incrementAndGet);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(recorded).hasValue(0);
    }
    @Test void 계측_오류는_이미_커밋된_업무를_실패로_바꾸지_않는다() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        AfterCommitTelemetry.record(() -> { throw new IllegalStateException("secret"); });
        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
            .forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
    }
}

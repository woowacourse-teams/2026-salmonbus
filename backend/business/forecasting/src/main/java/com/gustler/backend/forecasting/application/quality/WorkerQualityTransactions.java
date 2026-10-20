package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.forecasting.application.quality.WorkerQualityState.Change;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 품질 변경 SQL보다 먼저 호출하고, DB 트랜잭션이 끝난 뒤 메모리 읽기를 허용한다. */
public final class WorkerQualityTransactions {
    private final WorkerQualityState state;

    public WorkerQualityTransactions(WorkerQualityState state) {
        this.state = state;
    }

    /**
     * 실제 트랜잭션 동안 품질 변경을 표시하고 완료 시 해제한다. 같은 트랜잭션에는 완료 동기화를 한 번만 등록한다.
     */
    public void changing() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("품질 변경에는 실제 트랜잭션이 필요하다");
        }
        if (TransactionSynchronizationManager.getSynchronizations()
            .stream()
            .anyMatch(sync -> sync instanceof Completion completion && completion.owner() == this)) {
            return;
        }
        Change change = state.beginChange();
        try {
            TransactionSynchronizationManager.registerSynchronization(new Completion(change));
        } catch (RuntimeException failure) {
            change.close();
            throw failure;
        }
    }

    private final class Completion implements TransactionSynchronization {
        private final Change change;

        private Completion(Change change) {
            this.change = change;
        }

        private WorkerQualityTransactions owner() {
            return WorkerQualityTransactions.this;
        }

        @Override
        public void afterCompletion(final int status) {
            // 커밋·롤백 모두 기존 스냅샷은 버린다. 결과 불명이면 차단을 유지한다.
            if (status == STATUS_COMMITTED || status == STATUS_ROLLED_BACK) {
                change.close();
            }
        }
    }
}

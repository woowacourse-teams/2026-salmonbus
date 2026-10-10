package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 명시 실행 전용. 스케줄러 및 운영 삭제 설정에는 아직 연결하지 않는다. */
public final class PurgeArchivedEvaluationsService {
    private final EvaluationArchiveRetentionStore store;
    private final EvaluationArchiveSource source;

    public PurgeArchivedEvaluationsService(EvaluationArchiveRetentionStore store, EvaluationArchiveSource source) {
        this.store = store;
        this.source = source;
    }

    public int purge(EvaluationArchiveBatch owned) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("삭제 검증은 DB 트랜잭션 밖에서 시작해야 한다");
        }
        var prepared = store.preparePurge(owned);
        var rows = prepared.location() == EvaluationArchiveRetentionStore.Location.PURGED
            ? List.<EvaluationArchiveBatch.Row>of() : source.readOriginal(prepared.batch());
        return store.purge(prepared, rows);
    }
}

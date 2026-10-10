package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 원본만 복구한다. 정산 대기나 통계 표본을 다시 만들지 않는다. */
public final class RestoreArchivedEvaluationsService {
    private final EvaluationArchiveRetentionStore store;
    private final EvaluationArchiveSource source;

    public RestoreArchivedEvaluationsService(EvaluationArchiveRetentionStore store, EvaluationArchiveSource source) {
        this.store = store;
        this.source = source;
    }

    public int restore(EvaluationArchiveBatch owned) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("복원 파일은 DB 트랜잭션 밖에서 읽어야 한다");
        }
        var prepared = store.prepareRestore(owned);
        var rows = prepared.location() == EvaluationArchiveRetentionStore.Location.LIVE
            ? List.<EvaluationArchiveBatch.Row>of() : source.readOriginal(prepared.batch());
        return store.restore(prepared, rows);
    }
}

package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveRetentionStore.Location;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveRetentionStore.Snapshot;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
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

    /**
     * DB 트랜잭션 밖에서 복원 파일을 검증한 뒤 준비한 상태에 반영한다. 기존 트랜잭션 안의 호출은 거부한다.
     */
    public int restore(EvaluationArchiveBatch owned) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("복원 파일은 DB 트랜잭션 밖에서 읽어야 한다");
        }
        Snapshot prepared = store.prepareRestore(owned);
        List<Row> rows = readOriginalRowsIfNeeded(prepared);
        return store.restore(prepared, rows);
    }

    private List<Row> readOriginalRowsIfNeeded(Snapshot prepared) {
        if (prepared.location() == Location.LIVE) {
            return List.of();
        }
        return source.readOriginal(prepared.batch());
    }
}

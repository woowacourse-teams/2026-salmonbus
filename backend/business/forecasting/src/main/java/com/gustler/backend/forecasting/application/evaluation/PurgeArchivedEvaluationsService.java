package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveRetentionStore.Location;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveRetentionStore.Snapshot;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
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

    /**
     * DB 트랜잭션 밖에서 아카이브를 검증한 뒤 준비한 원본을 삭제한다. 기존 트랜잭션 안의 호출은 거부한다.
     */
    public int purge(EvaluationArchiveBatch owned) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("삭제 검증은 DB 트랜잭션 밖에서 시작해야 한다");
        }
        Snapshot prepared = store.preparePurge(owned);
        List<Row> rows = readOriginalRowsIfNeeded(prepared);
        return store.purge(prepared, rows);
    }

    private List<Row> readOriginalRowsIfNeeded(Snapshot prepared) {
        if (prepared.location() == Location.PURGED) {
            return List.of();
        }
        return source.readOriginal(prepared.batch());
    }
}

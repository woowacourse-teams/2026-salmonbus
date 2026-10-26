package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.application.evaluation.ArchiveCompletedEvaluationsService.Archived;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveRetentionStore.Location;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveRetentionStore.Snapshot;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 업로드에 사용한 원본이나 다시 읽어 검증한 아카이브를 현재 DB와 대조해 삭제한다. */
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
        return purge(owned, null);
    }

    int purge(EvaluationArchiveBatch owned, Archived archived) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("삭제 검증은 DB 트랜잭션 밖에서 시작해야 한다");
        }
        Snapshot prepared = store.preparePurge(owned);
        List<Row> rows = readOriginalRowsIfNeeded(prepared, archived);
        return store.purge(prepared, rows);
    }

    private List<Row> readOriginalRowsIfNeeded(Snapshot prepared, Archived archived) {
        if (prepared.location() == Location.PURGED) {
            return List.of();
        }
        if (archived != null) {
            if (!archived.batch().equals(prepared.batch())) {
                throw new IllegalStateException("저장한 원본의 묶음·소유권·검증값이 삭제 준비 상태와 다르다");
            }
            return archived.rows();
        }
        return source.readOriginal(prepared.batch());
    }
}

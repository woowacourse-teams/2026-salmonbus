package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveStorage.Verification;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.State;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 저장이 확인된 원본을 같은 실행의 삭제 단계까지 전달한다. */
public final class ArchiveCompletedEvaluationsService {
    private final EvaluationArchiveStore store;
    private final EvaluationArchiveStorage storage;

    public ArchiveCompletedEvaluationsService(EvaluationArchiveStore store, EvaluationArchiveStorage storage) {
        this.store = store;
        this.storage = storage;
    }

    /**
     * DB 트랜잭션 밖에서 파일을 저장·검증한 뒤 소유권과 원본 변경을 재확인해 확정한다. 기존 트랜잭션 안의 호출은 거부한다.
     */
    public Archived archive(EvaluationArchiveBatch batch) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("이관은 외부 트랜잭션 없이 시작해야 한다");
        }
        List<Row> rows = List.copyOf(store.readOwned(batch));
        Verification verified = storage.storeAndVerify(batch, rows);
        // 업로드 중 바뀐 원본·품질·소유권을 다시 검사하고 기록한다.
        store.recordVerified(batch, verified.manifestSha256());
        EvaluationArchiveBatch verifiedBatch = new EvaluationArchiveBatch(batch.id(), batch.routeVersionId(),
            batch.qualityRevision(), batch.leaseToken(), batch.leaseUntil(), State.VERIFIED, batch.rowCount(),
            verified.manifestSha256());
        return new Archived(verifiedBatch, rows);
    }

    /** DB에 확정한 묶음과 업로드에 사용한 원본의 호출 단위 스냅샷이다. */
    public record Archived(EvaluationArchiveBatch batch, List<Row> rows) {
        public Archived {
            if (batch == null || batch.state() != State.VERIFIED) {
                throw new IllegalArgumentException("검증을 확정한 이관 묶음이 필요하다");
            }
            rows = List.copyOf(rows);
        }
    }
}

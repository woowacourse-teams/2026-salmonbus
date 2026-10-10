package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 명시적으로 구성해 사용한다. 후보 자동 탐색과 삭제·스케줄링은 아직 연결하지 않는다. */
public final class ArchiveCompletedEvaluationsService {
    private final EvaluationArchiveStore store;
    private final EvaluationArchiveStorage storage;

    public ArchiveCompletedEvaluationsService(EvaluationArchiveStore store, EvaluationArchiveStorage storage) {
        this.store = store;
        this.storage = storage;
    }

    public EvaluationArchiveStorage.Verification archive(EvaluationArchiveBatch batch) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("이관은 외부 트랜잭션 없이 시작해야 한다");
        }
        var rows = store.readOwned(batch);
        var verified = storage.storeAndVerify(batch, rows);
        // 업로드 중 바뀐 원본·품질·소유권을 다시 검사하고 기록한다.
        store.recordVerified(batch, verified.manifestSha256());
        return verified;
    }
}

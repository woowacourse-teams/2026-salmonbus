package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveStorage.Verification;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 명시적으로 구성해 사용한다. 후보 자동 탐색과 삭제·스케줄링은 아직 연결하지 않는다. */
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
    public Verification archive(EvaluationArchiveBatch batch) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("이관은 외부 트랜잭션 없이 시작해야 한다");
        }
        List<Row> rows = store.readOwned(batch);
        Verification verified = storage.storeAndVerify(batch, rows);
        // 업로드 중 바뀐 원본·품질·소유권을 다시 검사하고 기록한다.
        store.recordVerified(batch, verified.manifestSha256());
        return verified;
    }
}

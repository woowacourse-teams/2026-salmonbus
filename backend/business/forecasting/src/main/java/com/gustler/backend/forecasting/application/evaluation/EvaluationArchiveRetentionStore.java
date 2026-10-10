package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import java.util.List;

/** 파일 전송 사이의 DB 변경을 감지한다. 각 호출은 자체 짧은 트랜잭션을 사용한다. */
public interface EvaluationArchiveRetentionStore {
    Snapshot preparePurge(EvaluationArchiveBatch owned);
    Snapshot prepareRestore(EvaluationArchiveBatch owned);
    int purge(Snapshot prepared, List<Row> verifiedRows);
    int restore(Snapshot prepared, List<Row> verifiedRows);

    enum Location { LIVE, PURGED }
    record Snapshot(EvaluationArchiveBatch batch, long storageRevision, long currentQuality, Location location) { }
}

package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import java.util.List;

/** 파일 전송 사이의 DB 변경을 감지한다. 각 호출은 자체 짧은 트랜잭션을 사용한다. */
public interface EvaluationArchiveRetentionStore {
    /**
     * 삭제 전 소유권과 DB 상태를 확인한 스냅샷을 만든다. 외부 아카이브 검증은 준비 단계 밖에서 수행한다.
     */
    Snapshot preparePurge(EvaluationArchiveBatch owned);

    /**
     * 복원 전 소유권과 DB 상태를 확인한다. 실제 복원은 검증한 행을 전달받은 뒤 수행한다.
     */
    Snapshot prepareRestore(EvaluationArchiveBatch owned);

    /**
     * 준비한 상태와 검증한 아카이브 행을 대조해 원본을 삭제하고 삭제 건수를 반환한다.
     */
    int purge(Snapshot prepared, List<Row> verifiedRows);

    /**
     * 검증한 아카이브 행을 준비된 상태에 복원하고 반영 건수를 반환한다.
     */
    int restore(Snapshot prepared, List<Row> verifiedRows);

    enum Location {
        LIVE, PURGED
    }
    record Snapshot(EvaluationArchiveBatch batch, long storageRevision, long currentQuality, Location location) {
    }
}

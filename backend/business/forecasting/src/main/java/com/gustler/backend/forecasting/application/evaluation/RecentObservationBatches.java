package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import java.time.Instant;
import java.util.List;

/** DB에 실제 저장된 최근 수집 배치 목록. 캐시 누락과 재수집을 확인한다. */
public interface RecentObservationBatches {
    List<Header> headers(long version, Instant after, int limit);
    List<Row> restore(long version, List<Long> batches, int limit);

    record Header(long batchId, int attempt, Instant observedAt, int rows, Integer turnSequence) {
    }
    record Row(long batchId, ArrivalCandidate candidate) {
    }
}

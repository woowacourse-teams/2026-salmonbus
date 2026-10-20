package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import java.time.Instant;
import java.util.List;

/** DB에 실제 저장된 최근 수집 배치 목록. 캐시 누락과 재수집을 확인한다. */
public interface RecentObservationBatches {
    /**
     * 커밋 후 전달할 최근 관측 묶음의 헤더를 읽는다. 관측 행의 상세 복원은 별도 연산이다.
     */
    List<Header> headers(long version, Instant after, int limit);

    /**
     * 지정한 관측 묶음의 행을 평가용으로 복원한다. 원천 API를 다시 호출하지 않는다.
     */
    List<Row> restore(long version, List<Long> batches, int limit);

    record Header(long batchId, int attempt, Instant observedAt, int rows, Integer turnSequence) {
    }
    record Row(long batchId, ArrivalCandidate candidate) {
    }
}

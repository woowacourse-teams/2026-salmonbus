package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.util.List;
import java.util.Optional;

/** 조회는 잠금 없이 수행할 수 있다. 등록·종료는 호출자가 확보한 노선 품질 잠금과 트랜잭션이 필요하다. */
public interface EvaluationWorkStore {
    void register(long version, List<Long> observationIds);
    List<Long> routes();
    long lastObservationId(long version);
    List<Long> page(
        long version,
        long afterObservationId,
        long throughObservationId,
        int limit
    );
    java.util.Map<Long, Long> revisions(long version, List<Long> observationIds);
    List<Restored> restore(long version, List<Long> observationIds);
    List<Key> closeExcluded(long version, List<Long> observationIds, int limit);
    List<Key> completed(long version, List<Long> observationIds);
    List<Long> releaseCompleted(long version, List<Long> observationIds);

    record Key(long observationId, int targetStopOrder) {
    }

    /** 키는 미완료다. 평가 정보가 없으면 완료가 아니라 보류다. */
    record Restored(Key key, Optional<PendingForecast> forecast) {
    }
}

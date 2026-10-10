package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Key;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvaluationArchiveQueue {
    boolean available();
    Optional<UUID> resumable(boolean deleteEnabled);
    Optional<Candidates> next(int limit);
    boolean retireChanged(EvaluationArchiveBatch owned);
    void defer(EvaluationArchiveBatch owned);
    record Candidates(long routeVersionId, List<Key> keys) { }
}

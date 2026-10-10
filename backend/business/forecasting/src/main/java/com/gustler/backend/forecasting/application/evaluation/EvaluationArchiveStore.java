package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Key;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 후보 탐색과 별개인 명시 키 예약 경계. 각 메서드는 짧은 자체 트랜잭션으로 끝난다. */
public interface EvaluationArchiveStore {
    Optional<EvaluationArchiveBatch> reserve(long routeVersionId, List<Key> candidates);
    Optional<EvaluationArchiveBatch> reclaim(UUID batchId);
    List<Row> readOwned(EvaluationArchiveBatch batch);

    /** 실제 파일 왕복 검증을 끝낸 호출자만 사용한다. 삭제 허가를 의미하지 않는다. */
    void recordVerified(EvaluationArchiveBatch batch, String manifestSha256);
}

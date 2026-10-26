package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Key;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 후보 탐색과 별개로 명시한 키를 예약하는 경계다. 각 메서드는 짧은 자체 트랜잭션으로 끝난다. */
public interface EvaluationArchiveStore {
    /**
     * 노선별 후보를 아카이브 배치로 예약한다. 예약에 실패하면 빈 값이며 외부 파일 전송은 수행하지 않는다.
     */
    Optional<EvaluationArchiveBatch> reserve(long routeVersionId, List<Key> candidates);

    /**
     * 중단된 배치의 소유권을 다시 확보한다. 확보할 수 없으면 빈 값이다.
     */
    Optional<EvaluationArchiveBatch> reclaim(UUID batchId);

    /**
     * 현재 소유권을 가진 배치의 원본 행을 읽는다. 다른 소유자의 배치를 처리하지 않는다.
     */
    List<Row> readOwned(EvaluationArchiveBatch batch);

    /** 파일 저장과 무결성 검증을 끝낸 호출자만 사용한다. 삭제 허가를 의미하지 않는다. */
    void recordVerified(EvaluationArchiveBatch batch, String manifestSha256);
}

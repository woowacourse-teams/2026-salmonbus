package com.gustler.backend.forecasting.api.statistics;

import java.time.Instant;
import java.util.UUID;

/** 소량 비교 입력을 복사한다. 통계 게시나 원본 삭제를 수행하지 않는다. */
public interface ExportStatisticsInput {
    String export(long routeVersionId, long afterObservationId, long throughObservationId,
        Instant dataUntil, int maxRows, UUID exportId);
}

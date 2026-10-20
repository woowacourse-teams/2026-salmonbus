package com.gustler.backend.forecasting.api.statistics;

import java.time.Instant;
import java.util.UUID;

/** 소량 비교 입력을 복사한다. 통계 게시나 원본 삭제를 수행하지 않는다. */
public interface ExportStatisticsInput {
    /**
     * 고정 관측 범위와 시각 상한의 통계 입력을 최대 maxRows건 내보낸다. 외부 파일 전송이 있으므로 DB 트랜잭션 범위를 분리한다.
     */
    String export(
        long routeVersionId,
        long afterObservationId,
        long throughObservationId,
        Instant dataUntil,
        int maxRows,
        UUID exportId
    );
}

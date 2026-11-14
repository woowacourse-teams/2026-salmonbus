package com.gustler.backend.forecasting.infrastructure.pipeline;

import com.gustler.backend.forecasting.application.pipeline.PendingPipelineBatches;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 본선이 처리하지 않은 배치를 읽는다. 예보 실행은 기본 키(나눔 날짜, 배치)로 찾는다. 나눔 날짜는 배치 예약 시각의 한국 날짜다.
 */
public final class JdbcPendingPipelineBatches implements PendingPipelineBatches {
    private static final String FIND = """
        SELECT /* salmonbus:pipeline_forecast.find_unprocessed */ b.id, b.route_version_id, b.response_received_at
        FROM observation_batch b
        WHERE b.route_version_id = :routeVersionId
          AND b.response_received_at IS NOT NULL
          AND b.response_received_at >= :notBefore
          AND b.outcome IN ('SUCCESS_ROWS', 'SUCCESS_EMPTY')
          AND b.input_confirmed_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM forecast_run r
              WHERE r.source_date = (b.scheduled_at AT TIME ZONE 'Asia/Seoul')::date AND r.observation_batch_id = b.id)
        ORDER BY b.response_received_at, b.id
        LIMIT :limit
        """;

    private final JdbcClient jdbc;

    public JdbcPendingPipelineBatches(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Pending> find(final long routeVersionId, Instant notBefore, final int limit) {
        return jdbc.sql(FIND)
            .param("routeVersionId", routeVersionId)
            .param("notBefore", notBefore.atOffset(ZoneOffset.UTC))
            .param("limit", limit)
            .query((row, index) -> new Pending(row.getLong(1), row.getLong(2),
                row.getObject(3, OffsetDateTime.class).toInstant()))
            .list();
    }
}

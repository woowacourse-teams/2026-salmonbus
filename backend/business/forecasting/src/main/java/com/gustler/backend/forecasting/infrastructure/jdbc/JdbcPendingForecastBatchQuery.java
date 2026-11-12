package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatchQuery;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 예보가 필요한 수집 배치만 읽는다. 구 모델의 과거 궤적과 정원 계산은 수행하지 않는다. */
@Repository
public class JdbcPendingForecastBatchQuery implements PendingForecastBatchQuery {
    private static final String SELECT_BATCHES_AWAITING_FORECAST = """
        SELECT /* salmonbus:vehicle_trajectory_query.select_batches_awaiting_forecast */ batch.id, batch.route_version_id, version.route_id, batch.response_received_at
        FROM observation_batch batch
        JOIN route_version version
          ON version.id = batch.route_version_id
        WHERE batch.route_version_id = :routeVersionId
          AND NOT EXISTS (SELECT 1 FROM forecast_publication p WHERE p.source_batch_id = batch.id)
          AND batch.response_received_at IS NOT NULL
          AND batch.response_received_at >= :notBefore
          AND batch.outcome IN ('SUCCESS_ROWS', 'SUCCESS_EMPTY')
        ORDER BY batch.response_received_at
        LIMIT :limit
        """;

    /** 최근 처리 범위에서 신선도 기준을 지난 미발행 배치의 가장 오래된 관측 시각. */
    private static final String SELECT_OLDEST_BATCH_LEFT_BEHIND = """
        SELECT /* salmonbus:vehicle_trajectory_query.select_oldest_batch_left_behind */ batch.response_received_at
        FROM observation_batch batch
        WHERE batch.route_version_id = :routeVersionId
          AND NOT EXISTS (SELECT 1 FROM forecast_publication p WHERE p.source_batch_id = batch.id)
          AND response_received_at IS NOT NULL
          AND response_received_at >= :from
          AND response_received_at < :until
          AND outcome IN ('SUCCESS_ROWS', 'SUCCESS_EMPTY')
        ORDER BY response_received_at
        LIMIT 1
        """;

    private final JdbcClient jdbcClient;
    public JdbcPendingForecastBatchQuery(JdbcClient jdbcClient) { this.jdbcClient=jdbcClient; }

    @Override
    public List<PendingForecastBatch> findBatchesAwaitingForecast(
        final long routeVersionId,
        Instant notBefore,
        final int limit
    ) {
        return jdbcClient.sql(SELECT_BATCHES_AWAITING_FORECAST)
            .param("routeVersionId", routeVersionId)
            .param("notBefore", offsetOf(notBefore))
            .param("limit", limit)
            .query((resultSet, rowNumber) -> new PendingForecastBatch(
                resultSet.getLong("id"),
                resultSet.getLong("route_version_id"),
                resultSet.getLong("route_id"),
                instantOf(resultSet.getObject("response_received_at", OffsetDateTime.class))))
            .list();
    }

    @Override
    public Optional<Instant> findOldestLeftBehindAt(
        final long routeVersionId,
        Instant from,
        Instant until
    ) {
        return jdbcClient.sql(SELECT_OLDEST_BATCH_LEFT_BEHIND)
            .param("routeVersionId", routeVersionId)
            .param("from", offsetOf(from))
            .param("until", offsetOf(until))
            .query((resultSet, rowNumber) ->
                instantOf(resultSet.getObject("response_received_at", OffsetDateTime.class)))
            .optional();
    }

    private static Instant instantOf(OffsetDateTime at) { return at.toInstant(); }
    private static OffsetDateTime offsetOf(Instant at) { return at.atOffset(ZoneOffset.UTC); }
}

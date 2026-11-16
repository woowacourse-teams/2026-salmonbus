package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.forecast.PendingBatches;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 예보가 처리하지 않은 배치를 읽는다. 예보는 수집의 신호마다 이 조회를 한 번 한다.
 *
 * <p>배치는 (노선 판본, 수신 시각) 인덱스의 조건으로 찾으므로 테이블을 통째로 읽지 않는다. {@code observation_batch}가 일반
 * 테이블이면 성공한 배치의 부분 인덱스({@code ix_batch_forecast_candidates})를 정렬 순서대로 읽는다. 날짜 파티션 테이블이면
 * 파티션마다 같은 인덱스 조건으로 읽고 그 결과를 모아 수신 순서로 정렬한다. 일반 테이블에는 파티션 열({@code source_date})이 없어
 * 그 열을 조건에 넣지 않기 때문이다. 정렬 대상은 한 노선 판본의 신선도 창 안 배치뿐이다.
 *
 * <p>예보 실행은 기본 키(나눔 날짜, 배치)로 찾는다. 나눔 날짜는 배치 예약 시각의 한국 날짜다.
 */
public final class JdbcPendingBatches implements PendingBatches {
    static final String FIND = """
        SELECT /* salmonbus:forecast.find_unprocessed */ b.id, b.route_version_id, b.response_received_at
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

    public JdbcPendingBatches(JdbcClient jdbc) {
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

package com.gustler.backend.api.board.infrastructure.jdbc;

import com.gustler.backend.api.board.application.BoardSnapshot;
import com.gustler.backend.api.board.application.DepartureSchedule;
import com.gustler.backend.api.board.application.SnapshotObservation;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.board.domain.HeadwayRange;
import com.gustler.backend.api.board.domain.RouteHeadways;
import com.gustler.backend.api.observation.ObservationFreshness;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.domain.Route;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 보드 재사용 판단에 필요한 현재 노선·관측·모델·발행 판본을 같은 SQL에서 읽는다.
 *
 * <p>발행 이력이 있는 노선은 최신 발행 관측을, 없는 노선은 최신 정상 관측을 고른다.
 * 두 분기는 UNION ALL로 나누어 준비된 문장의 일반 실행 계획에서도 발행 전체 해시 생성을 피한다.
 * 호출자의 반복 읽기 트랜잭션에 참여하며 보드 조립과 오래됨 정책은 바꾸지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcBoardSnapshotReader {

    private static final String SNAPSHOT_SQL = """
        SELECT v.id AS version_id, r.source_route_id, r.display_name, r.start_stop_name, r.end_stop_name,
               v.turn_sequence, v.up_first_departure_time, v.up_last_departure_time,
               v.down_first_departure_time, v.down_last_departure_time,
               v.weekday_min_headway_minutes, v.weekday_max_headway_minutes,
               v.saturday_min_headway_minutes, v.saturday_max_headway_minutes,
               v.sunday_min_headway_minutes, v.sunday_max_headway_minutes,
               v.holiday_min_headway_minutes, v.holiday_max_headway_minutes,
               flags.published, b.id AS batch_id, b.response_received_at, b.stored_rows,
               model.id AS model_id, model.release_id, model.data_until,
               CASE WHEN b.id IS NULL THEN NULL ELSE concat_ws('|',
                   'q' || COALESCE(quality.quality_revision::text, '-'),
                   'p' || COALESCE(publication.published_at::text, '-'),
                   'g' || COALESCE(publication.generated_at::text, '-'),
                   'n' || COALESCE(publication.prediction_count::text, '-'),
                   'm' || COALESCE(publication.model_deployment_id::text, '-'),
                   'd' || COALESCE(publication.demand_statistics_revision::text, '-'),
                   'r' || COALESCE(publication.quality_revision::text, '-')) END AS revision
        FROM route_version v
        JOIN route r ON r.id=v.route_id
        LEFT JOIN LATERAL (SELECT p.source_batch_id FROM forecast_publication p
            WHERE p.route_version_id=v.id ORDER BY p.observed_at DESC,p.source_batch_id DESC LIMIT 1) newest ON true
        CROSS JOIN LATERAL (SELECT newest.source_batch_id IS NOT NULL AS published) flags
        LEFT JOIN LATERAL (
            (SELECT candidate.id, candidate.response_received_at, candidate.stored_rows
            FROM observation_batch candidate
            JOIN forecast_publication selected_publication ON selected_publication.source_batch_id=candidate.id
                AND selected_publication.route_version_id=candidate.route_version_id
            WHERE flags.published AND candidate.route_version_id=v.id
              AND candidate.outcome IN ('SUCCESS_ROWS','SUCCESS_EMPTY')
              AND candidate.response_received_at IS NOT NULL
              AND candidate.response_received_at >= :observedSince
            ORDER BY candidate.response_received_at DESC, candidate.id DESC LIMIT 1)
            UNION ALL
            (SELECT candidate.id, candidate.response_received_at, candidate.stored_rows
            FROM observation_batch candidate
            WHERE NOT flags.published AND candidate.route_version_id=v.id
              AND candidate.outcome IN ('SUCCESS_ROWS','SUCCESS_EMPTY')
              AND candidate.response_received_at IS NOT NULL
              AND candidate.response_received_at >= :observedSince
            ORDER BY candidate.response_received_at DESC, candidate.id DESC LIMIT 1)
        ) b ON true
        LEFT JOIN model_deployment model ON model.state='ACTIVE'
        LEFT JOIN route_data_quality quality ON quality.route_id=v.route_id
        LEFT JOIN forecast_publication publication ON publication.source_batch_id=b.id
        WHERE r.source_route_id=:routeId AND v.valid_to IS NULL
        """;

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public Optional<BoardSnapshot> findSnapshot(RouteId routeId) {
        return jdbcClient.sql(SNAPSHOT_SQL)
            .param("routeId", routeId.value())
            .param("observedSince", OffsetDateTime.now(clock).minus(ObservationFreshness.MAX_WINDOW))
            .query((row, index) -> toSnapshot(row))
            .optional();
    }

    private BoardSnapshot toSnapshot(ResultSet row) throws SQLException {
        Long batchId = row.getObject("batch_id", Long.class);
        Long modelId = row.getObject("model_id", Long.class);
        return new BoardSnapshot(
            row.getLong("version_id"),
            new Route(
                row.getString("source_route_id"),
                row.getString("display_name"),
                row.getString("start_stop_name"),
                row.getString("end_stop_name")
            ),
            row.getObject("turn_sequence", Integer.class),
            new DepartureSchedule(
                row.getString("up_first_departure_time"),
                row.getString("up_last_departure_time"),
                row.getString("down_first_departure_time"),
                row.getString("down_last_departure_time"),
                new RouteHeadways(
                    headwayOf(row, "weekday"),
                    headwayOf(row, "saturday"),
                    headwayOf(row, "sunday"),
                    headwayOf(row, "holiday")
                )
            ),
            observationOf(row, batchId),
            modelOf(row, modelId),
            row.getString("revision")
        );
    }

    private Optional<SnapshotObservation> observationOf(ResultSet row, Long batchId) throws SQLException {
        if (batchId == null) {
            return Optional.empty();
        }
        return Optional.of(
            new SnapshotObservation(
                batchId,
                timeAt(row, "response_received_at"),
                row.getObject("stored_rows", Integer.class),
                row.getBoolean("published")
            )
        );
    }

    private Optional<ForecastModel> modelOf(ResultSet row, Long modelId) throws SQLException {
        if (modelId == null) {
            return Optional.empty();
        }
        return Optional.of(new ForecastModel(modelId, row.getString("release_id"), timeAt(row, "data_until")));
    }

    private OffsetDateTime timeAt(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).atZoneSameInstant(clock.getZone()).toOffsetDateTime();
    }

    private static HeadwayRange headwayOf(ResultSet row, String day) throws SQLException {
        return HeadwayRange.of(
            row.getObject(day + "_min_headway_minutes", Integer.class),
            row.getObject(day + "_max_headway_minutes", Integer.class)
        )
            .orElse(null);
    }
}

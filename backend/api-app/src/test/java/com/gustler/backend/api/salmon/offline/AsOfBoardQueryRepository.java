package com.gustler.backend.api.salmon.offline;

import com.gustler.backend.api.board.application.BoardQueryRepository;
import com.gustler.backend.api.board.application.BoardSnapshot;
import com.gustler.backend.api.board.application.BoardVehicleObservation;
import com.gustler.backend.api.board.application.DepartureSchedule;
import com.gustler.backend.api.board.application.SnapshotObservation;
import com.gustler.backend.api.board.application.StoredPrediction;
import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardStop;
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
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 시계가 가리키는 시각에 보드가 골랐을 관측 묶음을 고르고, 그 묶음의 정류장, 예보, 차량을 읽는다.
 *
 * <p>묶음 고르기는 제품 조회(JdbcBoardSnapshotReader)에 세 조건을 더했다. 노선 판본은 그 시각에 유효하던 것,
 * 발행은 그 시각까지 발행된 것(published_at), 활성 모델은 그 시각에 활성이던 배포다(활성 시각이 빈 ACTIVE 배포는
 * 언제나 활성으로 본다). 시계가 지금이면 제품 구현과 같은 값을 돌려준다. 예보 판본 문자열도 제품과 같이 발행 열만 잇고,
 * 배치는 그 묶음의 좌석 예보를 전부 읽는다.
 */
final class AsOfBoardQueryRepository implements BoardQueryRepository {
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
                   'p' || COALESCE(publication.published_at::text, '-'),
                   'g' || COALESCE(publication.generated_at::text, '-'),
                   'n' || COALESCE(publication.prediction_count::text, '-'),
                   'm' || COALESCE(publication.model_deployment_id::text, '-'),
                   'd' || COALESCE(publication.demand_statistics_revision::text, '-'),
                   'r' || COALESCE(publication.quality_revision::text, '-')) END AS revision
        FROM route_version v
        JOIN route r ON r.id=v.route_id
        LEFT JOIN LATERAL (SELECT p.source_batch_id FROM forecast_publication p
            WHERE p.route_version_id=v.id AND p.published_at <= :at
            ORDER BY p.observed_at DESC,p.source_batch_id DESC LIMIT 1) newest ON true
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
              AND selected_publication.published_at <= :at
            ORDER BY candidate.response_received_at DESC, candidate.id DESC LIMIT 1)
            UNION ALL
            (SELECT candidate.id, candidate.response_received_at, candidate.stored_rows
            FROM observation_batch candidate
            WHERE NOT flags.published AND candidate.route_version_id=v.id
              AND candidate.outcome IN ('SUCCESS_ROWS','SUCCESS_EMPTY')
              AND candidate.response_received_at IS NOT NULL
              AND candidate.response_received_at >= :observedSince
              AND candidate.response_received_at <= :at
            ORDER BY candidate.response_received_at DESC, candidate.id DESC LIMIT 1)
        ) b ON true
        LEFT JOIN LATERAL (SELECT deployment.id, deployment.release_id, deployment.data_until
            FROM model_deployment deployment
            WHERE (deployment.activated_at <= :at OR deployment.activated_at IS NULL AND deployment.state = 'ACTIVE')
              AND (deployment.retired_at IS NULL OR deployment.retired_at > :at)
            ORDER BY deployment.activated_at DESC NULLS LAST, deployment.id DESC LIMIT 1) model ON true
        LEFT JOIN forecast_publication publication ON publication.source_batch_id=b.id
        WHERE r.source_route_id=:routeId AND v.valid_from <= :at AND (v.valid_to IS NULL OR v.valid_to > :at)
        """;

    private final JdbcClient jdbc;
    private final Clock clock;

    AsOfBoardQueryRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<BoardSnapshot> findSnapshot(RouteId routeId) {
        OffsetDateTime at = OffsetDateTime.now(clock);
        return jdbc.sql(SNAPSHOT_SQL)
            .param("routeId", routeId.value())
            .param("at", at)
            .param("observedSince", at.minus(ObservationFreshness.MAX_WINDOW))
            .query((row, index) -> snapshotOf(row))
            .optional();
    }

    @Override
    public List<BoardStop> findStops(long routeVersionId) {
        return jdbc.sql("""
            SELECT stop_order, stop_id, name, direction, boarding_allowed
            FROM route_stop WHERE route_version_id = ? ORDER BY stop_order
            """).param(routeVersionId).query((row, index) -> new BoardStop(row.getInt(1), row.getString(2),
            row.getString(3), BoardDirection.valueOf(row.getString(4)), row.getBoolean(5))).list();
    }

    @Override
    public List<StoredPrediction> findPredictions(long observationBatchId) {
        return jdbc.sql("""
            SELECT forecast.target_stop_order, observation.vehicle_id, observation.source_row_number,
                   forecast.stops_to_target, forecast.seat_full_chance, forecast.expected_seats,
                   model.id, model.release_id, model.data_until, forecast.seat_target, forecast.probability_target
            FROM seat_forecast forecast
            JOIN forecast_publication publication ON publication.id = forecast.publication_id
            JOIN vehicle_observation observation ON observation.id = forecast.vehicle_observation_id
            JOIN model_deployment model ON model.id = forecast.model_deployment_id
            WHERE publication.source_batch_id = :batchId AND observation.observation_batch_id = :batchId
            """).param("batchId", observationBatchId).query((row, index) -> new StoredPrediction(row.getInt(1),
            row.getString(2), row.getInt(3), row.getInt(4), row.getDouble(5), row.getObject(6, Double.class),
            new ForecastModel(row.getLong(7), row.getString(8), timeAt(row, 9)), row.getString(10),
            row.getString(11))).list();
    }

    @Override
    public List<BoardVehicleObservation> findObservedVehicles(long observationBatchId, long routeVersionId) {
        return jdbc.sql("""
            SELECT vehicle_id, source_row_number, passed_stop_order, stop_order
            FROM vehicle_observation WHERE observation_batch_id = ? AND route_version_id = ?
            """).params(observationBatchId, routeVersionId).query((row, index) -> new BoardVehicleObservation(
            row.getString(1), row.getInt(2), row.getInt(3), row.getInt(4))).list();
    }

    private BoardSnapshot snapshotOf(ResultSet row) throws SQLException {
        Long batchId = row.getObject("batch_id", Long.class);
        Long modelId = row.getObject("model_id", Long.class);
        Optional<SnapshotObservation> observation = batchId == null ? Optional.empty()
            : Optional.of(new SnapshotObservation(batchId, timeAt(row, row.findColumn("response_received_at")),
                row.getObject("stored_rows", Integer.class), row.getBoolean("published")));
        Optional<ForecastModel> model = modelId == null ? Optional.empty()
            : Optional.of(new ForecastModel(modelId, row.getString("release_id"),
                timeAt(row, row.findColumn("data_until"))));
        return new BoardSnapshot(
            row.getLong("version_id"),
            new Route(row.getString("source_route_id"), row.getString("display_name"),
                row.getString("start_stop_name"), row.getString("end_stop_name")),
            row.getObject("turn_sequence", Integer.class),
            new DepartureSchedule(row.getString("up_first_departure_time"), row.getString("up_last_departure_time"),
                row.getString("down_first_departure_time"), row.getString("down_last_departure_time"),
                new RouteHeadways(headwayOf(row, "weekday"), headwayOf(row, "saturday"), headwayOf(row, "sunday"),
                    headwayOf(row, "holiday"))),
            observation, model, row.getString("revision"));
    }

    private OffsetDateTime timeAt(ResultSet row, int column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).atZoneSameInstant(clock.getZone()).toOffsetDateTime();
    }

    private static HeadwayRange headwayOf(ResultSet row, String day) throws SQLException {
        return HeadwayRange.of(row.getObject(day + "_min_headway_minutes", Integer.class),
            row.getObject(day + "_max_headway_minutes", Integer.class)).orElse(null);
    }
}

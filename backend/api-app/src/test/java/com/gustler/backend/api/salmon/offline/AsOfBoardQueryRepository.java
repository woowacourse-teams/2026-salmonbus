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
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.observation.ObservationFreshness;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.domain.Route;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 시계가 가리키는 시각에 보드가 골랐을 관측 묶음을 고르고, 그 묶음의 정류장, 예보, 차량을 읽는다.
 *
 * <p>묶음 고르기와 예보 읽기는 제품 조회(JdbcBoardSnapshotReader, JdbcForecastRecordReader)의 조건에 판정 시각 조건만 더했다.
 * 노선 판본은 그 시각에 유효하던 것, 활성 모델은 그 시각에 활성이던 배포다(활성 시각이 빈 ACTIVE 배포는 언제나 활성으로
 * 본다). 예보 실행 줄({@code forecast_run})은 착수 시각({@code confirmed_at})이 그 시각 이전인 것만 있었던 것으로 본다.
 * 실행 줄과 그 기록({@code forecast_record})은 착수 뒤 한 번에 저장되고 다시 쓰이지 않으며, 착수 시각은 실행 줄이 가진 시각
 * 가운데 저장 시점에 가장 가깝다. 착수부터 저장까지의 지연은 되살리지 않는다.
 */
final class AsOfBoardQueryRepository implements BoardQueryRepository {
    private static final String SEAT_TARGET = "AFTER_ALIGHTING_ESTIMATE";
    private static final String PROBABILITY_TARGET = "DEPARTURE_ZERO";
    private static final ZoneId SOURCE_DATE_ZONE = ZoneId.of("Asia/Seoul");
    private static final String SNAPSHOT_SQL = """
        SELECT v.id AS version_id, r.source_route_id, r.display_name, r.start_stop_name, r.end_stop_name,
               v.turn_sequence, v.up_first_departure_time, v.up_last_departure_time,
               v.down_first_departure_time, v.down_last_departure_time,
               v.weekday_min_headway_minutes, v.weekday_max_headway_minutes,
               v.saturday_min_headway_minutes, v.saturday_max_headway_minutes,
               v.sunday_min_headway_minutes, v.sunday_max_headway_minutes,
               v.holiday_min_headway_minutes, v.holiday_max_headway_minutes,
               flags.published, b.id AS batch_id, b.response_received_at, b.stored_rows, b.source_date,
               model.id AS model_id, model.release_id, model.data_until,
               CASE WHEN b.id IS NULL THEN NULL ELSE concat_ws('|',
                   'c' || COALESCE(b.confirmed_at::text, '-'),
                   'n' || COALESCE(b.prediction_count::text, '-'),
                   'm' || COALESCE(b.model_deployment_id::text, '-')) END AS revision
        FROM route_version v
        JOIN route r ON r.id=v.route_id
        CROSS JOIN LATERAL (SELECT EXISTS (SELECT 1 FROM forecast_run any_run
            WHERE any_run.route_version_id=v.id AND any_run.confirmed_at <= :at) AS published) flags
        LEFT JOIN LATERAL (
            (SELECT candidate.id, candidate.response_received_at, candidate.stored_rows,
                   run.source_date, run.confirmed_at, run.prediction_count, run.model_deployment_id
            FROM forecast_run run
            JOIN observation_batch candidate ON candidate.id=run.observation_batch_id
                AND candidate.route_version_id=run.route_version_id
            WHERE flags.published AND run.route_version_id=v.id
              AND run.source_date >= :earliestSourceDate
              AND run.received_at >= :observedSince
              AND run.confirmed_at <= :at
              AND candidate.outcome IN ('SUCCESS_ROWS','SUCCESS_EMPTY')
              AND candidate.response_received_at IS NOT NULL
              AND candidate.response_received_at >= :observedSince
            ORDER BY candidate.response_received_at DESC, candidate.id DESC LIMIT 1)
            UNION ALL
            (SELECT candidate.id, candidate.response_received_at, candidate.stored_rows,
                   NULL::date, NULL::timestamptz, NULL::integer, NULL::bigint
            FROM observation_batch candidate
            WHERE NOT flags.published AND candidate.route_version_id=v.id
              AND candidate.outcome IN ('SUCCESS_ROWS','SUCCESS_EMPTY')
              AND candidate.response_received_at IS NOT NULL
              AND candidate.response_received_at >= :observedSince
              AND candidate.response_received_at <= :at
            ORDER BY candidate.response_received_at DESC, candidate.id DESC LIMIT 1)
        ) b(id, response_received_at, stored_rows, source_date, confirmed_at, prediction_count, model_deployment_id)
            ON true
        LEFT JOIN LATERAL (SELECT deployment.id, deployment.release_id, deployment.data_until
            FROM model_deployment deployment
            WHERE (deployment.activated_at <= :at OR deployment.activated_at IS NULL AND deployment.state = 'ACTIVE')
              AND (deployment.retired_at IS NULL OR deployment.retired_at > :at)
            ORDER BY deployment.activated_at DESC NULLS LAST, deployment.id DESC LIMIT 1) model ON true
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
        OffsetDateTime observedSince = at.minus(ObservationFreshness.MAX_WINDOW);
        return jdbc.sql(SNAPSHOT_SQL)
            .param("routeId", routeId.value())
            .param("at", at)
            .param("observedSince", observedSince)
            .param("earliestSourceDate", earliestSourceDate(observedSince))
            .query((row, index) -> snapshotOf(row))
            .optional();
    }

    /** 제품 조회와 같이, 조회 창 시작의 한국 날짜 하루 전부터의 날짜 파티션만 본다. */
    private static LocalDate earliestSourceDate(OffsetDateTime observedSince) {
        return observedSince.atZoneSameInstant(SOURCE_DATE_ZONE).toLocalDate().minusDays(1);
    }

    @Override
    public List<BoardStop> findStops(long routeVersionId) {
        return jdbc.sql("""
            SELECT stop_order, stop_id, name, direction, boarding_allowed
            FROM route_stop WHERE route_version_id = ? ORDER BY stop_order
            """).param(routeVersionId).query((row, index) -> new BoardStop(row.getInt(1), row.getString(2),
            row.getString(3), BoardDirection.valueOf(row.getString(4)), row.getBoolean(5))).list();
    }

    /**
     * 판정 시각까지 착수한, 예측을 실은 실행 줄({@code PUBLISHED})의 기록만 읽는다. 버스당 한 줄의 배열을 목표 정류장마다 한
     * 예측으로 펼치고, 예측 목표는 제품 조회와 같이 하차 반영 목표로 고정한다.
     *
     * @throws ServiceUnavailableException 예측을 낸 모델 배포를 찾을 수 없을 때(제품 조회와 같다)
     */
    @Override
    public List<StoredPrediction> findRecordedPredictions(final long observationBatchId, LocalDate sourceDate) {
        return jdbc.sql("""
            SELECT target.target_stop_order, record.vehicle_id, record.source_row_number,
                   target.target_stop_order - record.passed_stop_order, target.full_probability,
                   target.expected_seats, model.id, model.release_id, model.data_until
            FROM forecast_run run
            JOIN forecast_record record ON record.source_date=run.source_date
                AND record.observation_batch_id=run.observation_batch_id
            CROSS JOIN LATERAL unnest(record.target_stop_orders, record.expected_seats, record.full_probabilities)
                AS target(target_stop_order, expected_seats, full_probability)
            LEFT JOIN model_deployment model ON model.id=run.model_deployment_id
            WHERE run.source_date = :sourceDate AND run.observation_batch_id = :batchId
              AND run.outcome='PUBLISHED' AND run.confirmed_at <= :at
            ORDER BY record.source_row_number, target.target_stop_order
            """)
            .param("sourceDate", sourceDate)
            .param("batchId", observationBatchId)
            .param("at", OffsetDateTime.now(clock))
            .query((row, index) -> predictionOf(row))
            .list();
    }

    private StoredPrediction predictionOf(ResultSet row) throws SQLException {
        Long modelId = row.getObject(7, Long.class);
        if (modelId == null) {
            throw new ServiceUnavailableException();
        }
        return new StoredPrediction(row.getInt(1), row.getString(2), row.getInt(3), row.getInt(4),
            probabilityOf(row.getObject(5, Double.class)), row.getObject(6, Double.class),
            new ForecastModel(modelId, row.getString(8), timeAt(row, 9)), SEAT_TARGET, PROBABILITY_TARGET);
    }

    private static double probabilityOf(Double fullProbability) {
        if (fullProbability == null) {
            return Double.NaN;
        }
        return fullProbability;
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
        final boolean published = row.getBoolean("published");
        Optional<SnapshotObservation> observation = batchId == null ? Optional.empty()
            : Optional.of(new SnapshotObservation(batchId, timeAt(row, row.findColumn("response_received_at")),
                row.getObject("stored_rows", Integer.class), published, sourceDateOf(row, published)));
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

    private static LocalDate sourceDateOf(ResultSet row, final boolean published) throws SQLException {
        if (!published) {
            return null;
        }
        return row.getObject("source_date", LocalDate.class);
    }

    private OffsetDateTime timeAt(ResultSet row, int column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).atZoneSameInstant(clock.getZone()).toOffsetDateTime();
    }

    private static HeadwayRange headwayOf(ResultSet row, String day) throws SQLException {
        return HeadwayRange.of(row.getObject(day + "_min_headway_minutes", Integer.class),
            row.getObject(day + "_max_headway_minutes", Integer.class)).orElse(null);
    }
}

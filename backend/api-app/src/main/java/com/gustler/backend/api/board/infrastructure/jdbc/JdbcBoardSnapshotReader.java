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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 보드 재사용 판단에 필요한 현재 노선·관측·모델·발행 판본을 같은 SQL에서 읽는다.
 *
 * <p>예보 실행 줄({@code forecast_run})이 있는 노선은 실행 줄이 있는 최신 관측을, 없는 노선은 최신 정상 관측을 고른다.
 * 두 분기는 UNION ALL로 나누어 준비된 문장의 일반 실행 계획에서도 실행 줄 전체 해시 생성을 피한다.
 * 실행 줄이 있는 관측의 스냅샷은 그 배치의 날짜 파티션을 함께 실어 예보 기록을 파티션 하나에서 읽게 한다.
 * 호출자의 반복 읽기 트랜잭션에 참여하며 보드 조립과 오래됨 정책은 바꾸지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcBoardSnapshotReader {

    private static final ZoneId SOURCE_DATE_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * {@code revision} 열은 판정보드 캐시가 쓰는 판본 표시로, 실행 줄의 착수 시각·예측 수·모델 배포가 바뀌면 달라진다.
     * 실행 줄이 없는 관측은 세 칸이 모두 {@code -}다.
     */
    private static final String SNAPSHOT_SELECT = """
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
            WHERE any_run.route_version_id=v.id) AS published) flags
        LEFT JOIN LATERAL (
            (SELECT candidate.id, candidate.response_received_at, candidate.stored_rows,
                   run.source_date, run.confirmed_at, run.prediction_count, run.model_deployment_id
            FROM forecast_run run
            JOIN observation_batch candidate ON candidate.id=run.observation_batch_id
                AND candidate.route_version_id=run.route_version_id
            WHERE flags.published AND run.route_version_id=v.id
              AND run.source_date >= :earliestSourceDate
              AND run.received_at >= :observedSince
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
            ORDER BY candidate.response_received_at DESC, candidate.id DESC LIMIT 1)
        ) b(id, response_received_at, stored_rows, source_date, confirmed_at, prediction_count, model_deployment_id)
            ON true
        LEFT JOIN model_deployment model ON model.state='ACTIVE'
        """;

    private static final String ONE_ROUTE = """
        WHERE r.source_route_id=:routeId AND v.valid_to IS NULL
        """;

    private static final String MANY_ROUTES = """
        WHERE r.source_route_id = ANY(:routeIds) AND v.valid_to IS NULL
        """;

    private final JdbcClient jdbcClient;
    private final Clock clock;

    /**
     * 한 노선의 스냅샷을 읽는다. 실행 줄의 수신 시각은 배치의 응답 수신 시각과 같은 값이다.
     */
    public Optional<BoardSnapshot> findSnapshot(RouteId routeId) {
        return query(ONE_ROUTE)
            .param("routeId", routeId.value())
            .query((row, index) -> toSnapshot(row))
            .optional();
    }

    /**
     * 여러 노선의 스냅샷을 {@link #findSnapshot}과 같은 SQL 조건으로 한 번에 읽는다. 스냅샷이 없는 노선은 결과에 없다.
     * 한 노선에 행이 여럿이면 모두 돌려주고 실패 판정은 호출하는 쪽이 한다.
     */
    public List<BoardSnapshot> findSnapshots(Collection<RouteId> routeIds) {
        if (routeIds.isEmpty()) {
            return List.of();
        }
        String[] values = routeIds.stream()
            .map(RouteId::value)
            .distinct()
            .toArray(String[]::new);
        return query(MANY_ROUTES)
            .param("routeIds", values)
            .query((row, index) -> toSnapshot(row))
            .list();
    }

    private JdbcClient.StatementSpec query(String routeCondition) {
        OffsetDateTime observedSince = OffsetDateTime.now(clock).minus(ObservationFreshness.MAX_WINDOW);
        return jdbcClient.sql(SNAPSHOT_SELECT + routeCondition)
            .param("observedSince", observedSince)
            .param("earliestSourceDate", earliestSourceDate(observedSince));
    }

    /**
     * 조회 창 안에서 수신한 배치가 속할 수 있는 가장 이른 날짜 파티션이다. 파티션 날짜는 배치 예약 시각의 한국 날짜이고
     * 예약에서 수신까지는 수집 한 번의 시간이므로, 창 시작의 한국 날짜 하루 전이면 빠뜨리지 않는다.
     */
    private static LocalDate earliestSourceDate(OffsetDateTime observedSince) {
        return observedSince.atZoneSameInstant(SOURCE_DATE_ZONE).toLocalDate().minusDays(1);
    }

    private BoardSnapshot toSnapshot(ResultSet row) throws SQLException {
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
            observationOf(row),
            modelOf(row, modelId),
            row.getString("revision")
        );
    }

    private Optional<SnapshotObservation> observationOf(ResultSet row) throws SQLException {
        Long batchId = row.getObject("batch_id", Long.class);
        if (batchId == null) {
            return Optional.empty();
        }
        final boolean published = row.getBoolean("published");
        LocalDate sourceDate = null;
        if (published) {
            sourceDate = row.getObject("source_date", LocalDate.class);
        }
        return Optional.of(
            new SnapshotObservation(
                batchId,
                timeAt(row, "response_received_at"),
                row.getObject("stored_rows", Integer.class),
                published,
                sourceDate
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

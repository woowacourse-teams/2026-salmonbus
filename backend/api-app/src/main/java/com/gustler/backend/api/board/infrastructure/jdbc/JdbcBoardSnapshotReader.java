package com.gustler.backend.api.board.infrastructure.jdbc;

import com.gustler.backend.api.board.ForecastSourceSetting;
import com.gustler.backend.api.board.application.BoardSnapshot;
import com.gustler.backend.api.board.application.DepartureSchedule;
import com.gustler.backend.api.board.application.ForecastSource;
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
 * <p>발행 이력이 있는 노선은 최신 발행 관측을, 없는 노선은 최신 정상 관측을 고른다.
 * 두 분기는 UNION ALL로 나누어 준비된 문장의 일반 실행 계획에서도 발행 전체 해시 생성을 피한다.
 * 호출자의 반복 읽기 트랜잭션에 참여하며 보드 조립과 오래됨 정책은 바꾸지 않는다.
 *
 * <p>예보 출처({@link ForecastSourceSetting})에 따라 발행 표시를 {@code forecast_publication} 대신 {@code forecast_run}에서
 * 찾을 수 있다. 새 출처의 스냅샷은 그 배치의 날짜 파티션을 함께 실어 예보 기록을 파티션 하나에서 읽게 한다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcBoardSnapshotReader {

    private static final ZoneId SOURCE_DATE_ZONE = ZoneId.of("Asia/Seoul");

    private static final String ROUTE_COLUMNS = """
        SELECT v.id AS version_id, r.source_route_id, r.display_name, r.start_stop_name, r.end_stop_name,
               v.turn_sequence, v.up_first_departure_time, v.up_last_departure_time,
               v.down_first_departure_time, v.down_last_departure_time,
               v.weekday_min_headway_minutes, v.weekday_max_headway_minutes,
               v.saturday_min_headway_minutes, v.saturday_max_headway_minutes,
               v.sunday_min_headway_minutes, v.sunday_max_headway_minutes,
               v.holiday_min_headway_minutes, v.holiday_max_headway_minutes,
        """;

    private static final String LEGACY_COLUMNS = """
               flags.published, b.id AS batch_id, b.response_received_at, b.stored_rows,
               model.id AS model_id, model.release_id, model.data_until,
               CASE WHEN b.id IS NULL THEN NULL ELSE concat_ws('|',
                   'p' || COALESCE(publication.published_at::text, '-'),
                   'g' || COALESCE(publication.generated_at::text, '-'),
                   'n' || COALESCE(publication.prediction_count::text, '-'),
                   'm' || COALESCE(publication.model_deployment_id::text, '-'),
                   'd' || COALESCE(publication.demand_statistics_revision::text, '-'),
                   'r' || COALESCE(publication.quality_revision::text, '-')) END AS revision
        """;

    private static final String ROUTE_TABLES = """
        FROM route_version v
        JOIN route r ON r.id=v.route_id
        """;

    private static final String LEGACY_JOINS = """
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

        LEFT JOIN forecast_publication publication ON publication.source_batch_id=b.id
        """;

    private static final String SNAPSHOT_SELECT = ROUTE_COLUMNS + LEGACY_COLUMNS + ROUTE_TABLES + LEGACY_JOINS;

    /**
     * 새 예보 기록에서 판본 표시를 만든다. 옛 표시와 같은 여섯 칸을 같은 순서로 두고 출처 표시 {@code R}을 덧붙인다.
     * 착수 시각이 생성·발행 시각을 대신하고, 통계 판은 0, 품질 판은 1로 고정한다(옛 경로의 운영 값).
     */
    private static final String RECORD_REVISION = """
               CASE WHEN %1$s.id IS NULL THEN NULL ELSE concat_ws('|',
                   'p' || COALESCE(%1$s.confirmed_at::text, '-'),
                   'g' || COALESCE(%1$s.confirmed_at::text, '-'),
                   'n' || COALESCE(%1$s.prediction_count::text, '-'),
                   'm' || COALESCE(%1$s.model_deployment_id::text, '-'),
                   'd' || CASE WHEN %1$s.source_date IS NULL THEN '-' ELSE '0' END,
                   'r' || CASE WHEN %1$s.source_date IS NULL THEN '-' ELSE '1' END,
                   'R') END
        """;

    private static final String RECORD_FLAGS = """
        CROSS JOIN LATERAL (SELECT EXISTS (SELECT 1 FROM forecast_run any_run
            WHERE any_run.route_version_id=v.id) AS published) %s
        """;

    /** 실행 줄이 있는 최신 정상 관측 배치. 실행 줄의 수신 시각은 배치의 응답 수신 시각과 같은 값이다. */
    private static final String RECORDED_BATCH = """
            (SELECT candidate.id, candidate.response_received_at, candidate.stored_rows,
                   run.source_date, run.confirmed_at, run.prediction_count, run.model_deployment_id
            FROM forecast_run run
            JOIN observation_batch candidate ON candidate.id=run.observation_batch_id
                AND candidate.route_version_id=run.route_version_id
            WHERE %s.published AND run.route_version_id=v.id
              AND run.source_date >= :earliestSourceDate
              AND run.received_at >= :observedSince
              AND candidate.outcome IN ('SUCCESS_ROWS','SUCCESS_EMPTY')
              AND candidate.response_received_at IS NOT NULL
              AND candidate.response_received_at >= :observedSince
            ORDER BY candidate.response_received_at DESC, candidate.id DESC LIMIT 1)
        """;

    private static final String RECORD_SNAPSHOT_SELECT = ROUTE_COLUMNS + """
               flags.published, b.id AS batch_id, b.response_received_at, b.stored_rows, b.source_date,
               model.id AS model_id, model.release_id, model.data_until,
        """ + RECORD_REVISION.formatted("b") + """
               AS revision
        """ + ROUTE_TABLES + RECORD_FLAGS.formatted("flags") + """
        LEFT JOIN LATERAL (
        """ + RECORDED_BATCH.formatted("flags") + """
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

    private static final String AUTO_SNAPSHOT_SELECT = ROUTE_COLUMNS + LEGACY_COLUMNS + """
               , record_flags.published AS record_published, recorded.id AS record_batch_id,
               recorded.response_received_at AS record_response_received_at,
               recorded.stored_rows AS record_stored_rows, recorded.source_date AS record_source_date,
        """ + RECORD_REVISION.formatted("recorded") + """
               AS record_revision
        """ + ROUTE_TABLES + LEGACY_JOINS + RECORD_FLAGS.formatted("record_flags") + """
        LEFT JOIN LATERAL
        """ + RECORDED_BATCH.formatted("record_flags") + """
            recorded ON true
        """;

    private static final String ONE_ROUTE = """
        WHERE r.source_route_id=:routeId AND v.valid_to IS NULL
        """;

    private static final String MANY_ROUTES = """
        WHERE r.source_route_id = ANY(:routeIds) AND v.valid_to IS NULL
        """;

    private static final String SNAPSHOT_SQL = SNAPSHOT_SELECT + ONE_ROUTE;

    private static final String SNAPSHOTS_SQL = SNAPSHOT_SELECT + MANY_ROUTES;

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public Optional<BoardSnapshot> findSnapshot(RouteId routeId) {
        return findSnapshot(routeId, ForecastSourceSetting.LEGACY);
    }

    /**
     * {@link #findSnapshot(RouteId)}와 같은 규칙으로 한 노선의 스냅샷을 읽되 예보 출처를 고른다.
     *
     * <p>{@link ForecastSourceSetting#RECORD}는 {@code forecast_run}에 실행 줄이 있는 최신 배치를,
     * {@link ForecastSourceSetting#AUTO}는 노선 판본마다 두 출처의 고른 배치 가운데 번호가 큰 쪽(같으면 옛 출처)을 쓴다.
     * 노선에 그 출처의 예보가 한 번도 없으면 두 경우 모두 최신 정상 관측을 고르는 옛 규칙을 따른다.
     */
    public Optional<BoardSnapshot> findSnapshot(RouteId routeId, ForecastSourceSetting source) {
        return query(source, ONE_ROUTE)
            .param("routeId", routeId.value())
            .query((row, index) -> toSnapshot(row, source))
            .optional();
    }

    /**
     * 여러 노선의 스냅샷을 {@link #findSnapshot}과 같은 SQL 조건으로 한 번에 읽는다. 스냅샷이 없는 노선은 결과에 없다.
     * 한 노선에 행이 여럿이면 모두 돌려주고 실패 판정은 호출하는 쪽이 한다.
     */
    public List<BoardSnapshot> findSnapshots(Collection<RouteId> routeIds) {
        return findSnapshots(routeIds, ForecastSourceSetting.LEGACY);
    }

    /**
     * {@link #findSnapshots(Collection)}과 같은 규칙으로 읽되 예보 출처를 {@link #findSnapshot(RouteId, ForecastSourceSetting)}처럼
     * 고른다.
     */
    public List<BoardSnapshot> findSnapshots(Collection<RouteId> routeIds, ForecastSourceSetting source) {
        if (routeIds.isEmpty()) {
            return List.of();
        }
        String[] values = routeIds.stream()
            .map(RouteId::value)
            .distinct()
            .toArray(String[]::new);
        return query(source, MANY_ROUTES)
            .param("routeIds", values)
            .query((row, index) -> toSnapshot(row, source))
            .list();
    }

    private JdbcClient.StatementSpec query(ForecastSourceSetting source, String routeCondition) {
        OffsetDateTime observedSince = OffsetDateTime.now(clock).minus(ObservationFreshness.MAX_WINDOW);
        JdbcClient.StatementSpec statement = jdbcClient.sql(selectOf(source) + routeCondition)
            .param("observedSince", observedSince);
        if (source == ForecastSourceSetting.LEGACY) {
            return statement;
        }
        return statement.param("earliestSourceDate", earliestSourceDate(observedSince));
    }

    private static String selectOf(ForecastSourceSetting source) {
        return switch (source) {
            case LEGACY -> SNAPSHOT_SELECT;
            case RECORD -> RECORD_SNAPSHOT_SELECT;
            case AUTO -> AUTO_SNAPSHOT_SELECT;
        };
    }

    /**
     * 조회 창 안에서 수신한 배치가 속할 수 있는 가장 이른 날짜 파티션이다. 파티션 날짜는 배치 예약 시각의 한국 날짜이고
     * 예약에서 수신까지는 수집 한 번의 시간이므로, 창 시작의 한국 날짜 하루 전이면 빠뜨리지 않는다.
     */
    private static LocalDate earliestSourceDate(OffsetDateTime observedSince) {
        return observedSince.atZoneSameInstant(SOURCE_DATE_ZONE).toLocalDate().minusDays(1);
    }

    private BoardSnapshot toSnapshot(ResultSet row, ForecastSourceSetting source) throws SQLException {
        return switch (source) {
            case LEGACY -> toSnapshot(row);
            case RECORD -> snapshotOf(row, recordedObservationOf(row, "batch_id", "response_received_at",
                "stored_rows", "source_date", row.getBoolean("published")), row.getString("revision"));
            case AUTO -> toAutoSnapshot(row);
        };
    }

    /**
     * 두 출처의 고른 배치를 견준다. 두 출처 모두 이 노선 판본의 예보가 있으면 배치 번호가 큰 쪽, 같거나 새 출처에 고른 배치가 없으면
     * 옛 출처다. 한 출처에만 예보가 있으면 그 출처이고, 둘 다 없으면 최신 정상 관측을 고른 옛 출처 행을 쓴다.
     */
    private BoardSnapshot toAutoSnapshot(ResultSet row) throws SQLException {
        final boolean legacyPublished = row.getBoolean("published");
        final boolean recordPublished = row.getBoolean("record_published");
        Long legacyBatchId = row.getObject("batch_id", Long.class);
        Long recordBatchId = row.getObject("record_batch_id", Long.class);
        final boolean recordIsNewer = recordBatchId != null && (legacyBatchId == null || recordBatchId > legacyBatchId);
        if (recordPublished && (!legacyPublished || recordIsNewer)) {
            return snapshotOf(row, recordedObservationOf(row, "record_batch_id", "record_response_received_at",
                "record_stored_rows", "record_source_date", true), row.getString("record_revision"));
        }
        return toSnapshot(row);
    }

    private Optional<SnapshotObservation> recordedObservationOf(ResultSet row, String batchColumn,
        String receivedColumn, String storedRowsColumn, String sourceDateColumn, final boolean published)
        throws SQLException {
        Long batchId = row.getObject(batchColumn, Long.class);
        if (batchId == null) {
            return Optional.empty();
        }
        LocalDate sourceDate = null;
        if (published) {
            sourceDate = row.getObject(sourceDateColumn, LocalDate.class);
        }
        return Optional.of(
            new SnapshotObservation(
                batchId,
                timeAt(row, receivedColumn),
                row.getObject(storedRowsColumn, Integer.class),
                published,
                ForecastSource.RECORD,
                sourceDate
            )
        );
    }

    private BoardSnapshot toSnapshot(ResultSet row) throws SQLException {
        Long batchId = row.getObject("batch_id", Long.class);
        return snapshotOf(row, observationOf(row, batchId), row.getString("revision"));
    }

    private BoardSnapshot snapshotOf(ResultSet row, Optional<SnapshotObservation> observation, String revision)
        throws SQLException {
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
            observation,
            modelOf(row, modelId),
            revision
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

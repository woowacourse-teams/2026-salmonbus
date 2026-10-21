package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore.Key;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore.Restored;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관측별 미완료 정산 작업과 변경 판본을 관리한다.
 * 복원에 실패한 상세는 완료 처리하지 않는다.
 */
@Repository
@Transactional(readOnly = false, propagation = Propagation.MANDATORY)
public class JdbcEvaluationWorkStore implements EvaluationWorkStore {
    private static final int MAX_OBSERVATIONS = 100;
    private static final String REGISTER = """
        INSERT /* salmonbus:evaluation_work.register */ INTO forecast_evaluation_work
            (vehicle_observation_id, route_version_id)
        SELECT DISTINCT forecast.vehicle_observation_id, forecast.route_version_id
        FROM seat_forecast forecast
        WHERE forecast.route_version_id = :version
          AND forecast.vehicle_observation_id IN (:ids)
          AND NOT EXISTS (
              SELECT 1 FROM forecast_evaluation_result result
              WHERE result.vehicle_observation_id = forecast.vehicle_observation_id
                AND result.target_stop_order = forecast.target_stop_order
          )
          AND NOT EXISTS (
              SELECT 1 FROM forecast_evaluation_compact compact
              WHERE compact.vehicle_observation_id = forecast.vehicle_observation_id
                AND compact.target_stop_order = forecast.target_stop_order
          )
          AND NOT EXISTS (
              SELECT 1 FROM evaluation_archive_member archived
              WHERE archived.vehicle_observation_id = forecast.vehicle_observation_id
                AND archived.target_stop_order = forecast.target_stop_order
          )
        ON CONFLICT (vehicle_observation_id) DO UPDATE
        SET revision = forecast_evaluation_work.revision + 1
        """;

    private static final String PAGE = """
        SELECT /* salmonbus:evaluation_work.page */ vehicle_observation_id
        FROM forecast_evaluation_work
        WHERE route_version_id = :version
          AND vehicle_observation_id > :after
          AND vehicle_observation_id <= :through
        ORDER BY vehicle_observation_id
        LIMIT :limit
        """;

    private static final String RESTORE = """
        WITH /* salmonbus:evaluation_work.restore */ pending AS MATERIALIZED (
            SELECT vehicle_observation_id, target_stop_order, route_version_id
            FROM forecast_evaluation_pending
            WHERE route_version_id = :version AND vehicle_observation_id IN (:ids)
        ), source_ids AS MATERIALIZED (
            SELECT DISTINCT vehicle_observation_id AS id FROM pending
        ), sources AS MATERIALIZED (
            SELECT source.id, source.vehicle_id, source.quality_direction, batch.response_received_at
            FROM source_ids keys
            JOIN forecast_eligible_observation source ON source.id = keys.id
            JOIN observation_batch batch ON batch.id = source.observation_batch_id
        )
        SELECT /* salmonbus:evaluation_work.restore */
            pending.vehicle_observation_id, pending.target_stop_order, pending.route_version_id,
            sources.vehicle_id, sources.quality_direction, sources.response_received_at,
            forecast.generated_at, forecast.stops_to_target,
            sources.id IS NOT NULL AS eligible
        FROM pending
        JOIN seat_forecast forecast
          ON forecast.vehicle_observation_id = pending.vehicle_observation_id
         AND forecast.target_stop_order = pending.target_stop_order
        LEFT JOIN sources ON sources.id = pending.vehicle_observation_id
        ORDER BY pending.vehicle_observation_id, pending.target_stop_order
        """;

    private static final String EXCLUDE = """
        WITH /* salmonbus:evaluation_work.close_excluded */ closing AS MATERIALIZED (
            SELECT pending.vehicle_observation_id, pending.target_stop_order, pending.route_version_id
            FROM forecast_evaluation_pending pending
            JOIN vehicle_observation source ON source.id = pending.vehicle_observation_id
            LEFT JOIN observation_trip_assignment assignment ON assignment.observation_id = source.id
            JOIN vehicle_one_way_trip trip ON trip.id = COALESCE(assignment.trip_id, source.vehicle_trip_key)
            WHERE pending.route_version_id = :version AND source.route_version_id = :version
              AND pending.vehicle_observation_id IN (:ids)
              AND trip.status = 'EXCLUDED'
              AND EXISTS (
                  SELECT 1 FROM trip_quality_rebuild completed
                  WHERE completed.route_version_id = source.route_version_id
                    AND completed.vehicle_id = source.vehicle_id
                    AND completed.completed AND completed.phase = 'DONE'
              )
              AND NOT EXISTS (
                  SELECT 1 FROM trip_quality_rebuild investigation
                  WHERE investigation.route_version_id = source.route_version_id
                    AND NOT investigation.completed
                    AND (investigation.vehicle_id = '' OR investigation.vehicle_id = source.vehicle_id)
              )
            ORDER BY pending.vehicle_observation_id, pending.target_stop_order
            LIMIT :limit
        )
        INSERT INTO forecast_evaluation_result
            (vehicle_observation_id, target_stop_order, route_version_id, scoring_state, scored_at)
        SELECT vehicle_observation_id, target_stop_order, route_version_id,
            'QUALITY_EXCLUDED', CURRENT_TIMESTAMP
        FROM closing
        ON CONFLICT (vehicle_observation_id, target_stop_order) DO NOTHING
        RETURNING vehicle_observation_id, target_stop_order
        """;

    private static final String RELEASE = """
        DELETE /* salmonbus:evaluation_work.release_completed */ FROM forecast_evaluation_work work
        WHERE work.route_version_id = :version
          AND work.vehicle_observation_id IN (:ids)
          AND NOT EXISTS (
              SELECT 1 FROM forecast_evaluation_pending pending
              WHERE pending.vehicle_observation_id = work.vehicle_observation_id
                AND pending.route_version_id = work.route_version_id
          )
        RETURNING vehicle_observation_id
        """;

    private final JdbcClient jdbc;

    public JdbcEvaluationWorkStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void register(final long version, List<Long> observationIds) {
        List<Long> ids = checked(observationIds);
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql(REGISTER).param("version", version).param("ids", ids).update();
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public List<Long> routes() {
        return jdbc.sql("""
            SELECT /* salmonbus:evaluation_work.routes */ version.id FROM route_version version
            WHERE EXISTS (SELECT 1 FROM forecast_evaluation_work work WHERE work.route_version_id=version.id)
            ORDER BY version.route_id, version.id
            """).query(Long.class).list();
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public long lastObservationId(final long version) {
        return jdbc.sql("""
            SELECT /* salmonbus:evaluation_work.last_observation */ vehicle_observation_id FROM forecast_evaluation_work WHERE route_version_id=:version
            ORDER BY vehicle_observation_id DESC LIMIT 1
            """)
            .param("version", version)
            .query(Long.class)
            .optional()
            .orElse(0L);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public List<Long> page(
        final long version,
        final long afterObservationId,
        final long throughObservationId,
        final int limit
    ) {
        if (!hasValidPageRequest(afterObservationId, limit)) {
            throw new IllegalArgumentException("작업 탐색은 음수가 아닌 시작 위치와 제한된 개수가 필요하다");
        }
        return jdbc.sql(PAGE).param("version", version)
            .param("after", afterObservationId).param("through", throughObservationId)
            .param("limit", limit).query(Long.class).list();
    }

    private boolean hasValidPageRequest(long afterObservationId, int limit) {
        if (afterObservationId < 0 || limit < 1) {
            return false;
        }
        return limit <= MAX_OBSERVATIONS;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public Map<Long, Long> revisions(final long version, List<Long> observationIds) {
        List<Long> ids = checked(observationIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
            SELECT /* salmonbus:evaluation_work.revisions */ vehicle_observation_id, revision FROM forecast_evaluation_work
            WHERE route_version_id=:version AND vehicle_observation_id IN (:ids)
            """)
            .param("version", version)
            .param("ids", ids)
            .query(rs -> {
                Map<Long, Long> result = new LinkedHashMap<>();
                while (rs.next()) {
                    result.put(rs.getLong(1), rs.getLong(2));
                }
                return Map.copyOf(result);
            });
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public List<Restored> restore(final long version, List<Long> observationIds) {
        List<Long> ids = checked(observationIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(RESTORE).param("version", version).param("ids", ids)
            .query((rs, index) -> {
                Key key = new Key(rs.getLong("vehicle_observation_id"), rs.getInt("target_stop_order"));
                if (!rs.getBoolean("eligible")) {
                    return new Restored(key, Optional.empty());
                }
                PendingForecast forecast = new PendingForecast(
                    key.observationId(), key.targetStopOrder(), rs.getLong("route_version_id"),
                    rs.getString("vehicle_id"), rs.getInt("stops_to_target"),
                    rs.getObject("response_received_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("generated_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("quality_direction", Long.class));
                return new Restored(key, Optional.of(forecast));
            }).list();
    }

    @Override
    public List<Key> closeExcluded(final long version, List<Long> observationIds, final int limit) {
        List<Long> ids = checked(observationIds);
        if (limit < 0 || limit > 500) {
            throw new IllegalArgumentException("품질 제외 처리 한도를 벗어났다");
        }
        if (ids.isEmpty() || limit == 0) {
            return List.of();
        }
        return jdbc.sql(EXCLUDE).param("version", version).param("ids", ids).param("limit", limit)
            .query((rs, index) -> new Key(rs.getLong("vehicle_observation_id"),
                rs.getInt("target_stop_order"))).list();
    }

    @Override
    public List<Key> completed(final long version, List<Long> observationIds) {
        List<Long> ids = checked(observationIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
            SELECT /* salmonbus:evaluation_work.completed */ result.vehicle_observation_id, result.target_stop_order
            FROM forecast_evaluation_result result
            WHERE result.route_version_id=:version AND result.vehicle_observation_id IN (:ids)
            UNION
            SELECT compact.vehicle_observation_id, compact.target_stop_order
            FROM forecast_evaluation_compact compact
            JOIN seat_forecast forecast USING(vehicle_observation_id,target_stop_order)
            WHERE forecast.route_version_id=:version AND compact.vehicle_observation_id IN (:ids)
            UNION
            SELECT archived.vehicle_observation_id, archived.target_stop_order
            FROM evaluation_archive_member archived
            JOIN seat_forecast forecast USING(vehicle_observation_id,target_stop_order)
            WHERE forecast.route_version_id=:version AND archived.vehicle_observation_id IN (:ids)
            """).param("version", version).param("ids", ids)
            .query((rs, index) -> new Key(rs.getLong("vehicle_observation_id"),
                rs.getInt("target_stop_order"))).list();
    }

    @Override
    public List<Long> releaseCompleted(final long version, List<Long> observationIds) {
        List<Long> ids = checked(observationIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(RELEASE).param("version", version).param("ids", ids).query(Long.class).list();
    }

    private static List<Long> checked(List<Long> observations) {
        List<Long> ids = List.copyOf(observations);
        if (ids.size() > MAX_OBSERVATIONS || ids.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("작업은 양수 관측 ID로 한 번에 제한된 개수만 처리한다");
        }
        return ids.stream()
            .distinct()
            .toList();
    }
}

package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.quality.DemandStatisticsRebuildTrigger;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsRebuildRequestStore;
import com.gustler.backend.forecasting.domain.statistics.RebuildScope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 노선 전체와 차량별 재집계 요청의 우선순위·현재 식별·완료를 저장 경계에서 관리한다.
 */
@Repository
public class JdbcDemandStatisticsRebuildRequestStore implements DemandStatisticsRebuildRequestStore,
    DemandStatisticsRebuildTrigger {
    private final JdbcClient jdbc;

    public JdbcDemandStatisticsRebuildRequestStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void request(final long routeVersionId, RebuildScope scope) {
        jdbc.sql("""
            INSERT INTO stop_demand_rebuild_request(route_version_id, vehicle_id, request_id)
            VALUES (?, ?, ?)
            ON CONFLICT(route_version_id, vehicle_id) DO UPDATE SET
                request_id = EXCLUDED.request_id, requested_at = CURRENT_TIMESTAMP
            """).params(routeVersionId, scope.vehicleId(), UUID.randomUUID()).update();
    }

    @Override
    public boolean isPending(final long routeVersionId, RebuildScope scope) {
        return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM stop_demand_rebuild_request WHERE route_version_id = ? AND vehicle_id = ?)
            """).params(routeVersionId, scope.vehicleId()).query(Boolean.class).single();
    }

    @Override
    public boolean blocksAccumulation(final long routeVersionId, String vehicleId) {
        return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM stop_demand_rebuild_request
                WHERE route_version_id = ? AND vehicle_id IN ('', ?))
            """).params(routeVersionId, vehicleId).query(Boolean.class).single();
    }

    @Override
    public Optional<RebuildScope> next(final long routeVersionId) {
        return jdbc.sql("""
            SELECT vehicle_id FROM stop_demand_rebuild_request WHERE route_version_id = ? ORDER BY vehicle_id LIMIT 1
            """).param(routeVersionId).query(String.class).optional().map(RebuildScope::new);
    }

    @Override
    public Optional<UUID> current(final long routeVersionId, RebuildScope scope) {
        return jdbc.sql("""
            SELECT request_id FROM stop_demand_rebuild_request WHERE route_version_id = ? AND vehicle_id = ?
            """).params(routeVersionId, scope.vehicleId()).query(UUID.class).optional();
    }

    @Override
    public void complete(final long routeVersionId, RebuildScope scope, UUID requestId) {
        jdbc.sql("""
            DELETE FROM stop_demand_rebuild_request WHERE route_version_id = ? AND vehicle_id = ? AND request_id = ?
            """).params(routeVersionId, scope.vehicleId(), requestId).update();
    }

    @Override
    public void renewVehicleRequests(final long routeVersionId) {
        jdbc.sql("""
            UPDATE stop_demand_rebuild_request SET request_id = gen_random_uuid(), requested_at = CURRENT_TIMESTAMP
            WHERE route_version_id = ? AND vehicle_id <> ''
            """).param(routeVersionId).update();
    }

    @Override
    public void requestVehicle(final long routeVersionId, String vehicleId) {
        request(routeVersionId, RebuildScope.vehicle(vehicleId));
    }

    @Override
    public void requestRoute(final long routeVersionId) {
        request(routeVersionId, RebuildScope.wholeRoute());
    }
}

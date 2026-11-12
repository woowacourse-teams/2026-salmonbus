package com.gustler.backend.forecasting.infrastructure.alighting;

import com.gustler.backend.forecasting.api.alighting.AlightingAwareHistoryBootstrapException;
import com.gustler.backend.forecasting.api.alighting.BootstrapAlightingAwareHistory;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareFeatureUnavailableException;
import com.gustler.backend.forecasting.domain.model.ModelRoute;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.infrastructure.bundle.alighting.AlightingAwareInferenceBundle;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 수동 유지보수 구성에만 등록한다. SQL은 조회뿐이며 저장 대상은 지정한 체크포인트 디렉터리다. */
public final class AlightingAwareHistoryBootstrapService implements BootstrapAlightingAwareHistory {
    private final JdbcClient jdbc;

    public AlightingAwareHistoryBootstrapService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BootstrapAlightingAwareHistory.Result rebuild(BootstrapAlightingAwareHistory.Request request) {
        Path bundlePath = Path.of(request.bundleDirectory()).toAbsolutePath().normalize();
        Path checkpointPath = Path.of(request.checkpointDirectory()).toAbsolutePath().normalize();
        if (checkpointPath.startsWith(bundlePath) || bundlePath.startsWith(checkpointPath)) {
            throw new AlightingAwareHistoryBootstrapException("ALIGHTING_AWARE_CHECKPOINT_DIRECTORY_CONFLICT", null);
        }
        try {
            AlightingAwareInferenceBundle bundle = loadBundle(bundlePath);
            RouteIdentity identity = jdbc.sql("""
                SELECT route.source_route_id,route.display_name FROM route_version version
                JOIN route route ON route.id=version.route_id WHERE version.id=:version
                """).param("version", request.routeVersionId())
                .query((row, index) -> new RouteIdentity(row.getString(1), row.getString(2))).optional()
                .orElseThrow(() -> new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_ROUTE_VERSION_MISSING"));
            String route = ModelRoute.of(identity.sourceId());
            AlightingAwareRouteStatistics statistics = AlightingAwareStatisticsReader.read(bundle.statistics(route), bundle.holidays());
            List<RouteStop> stops = jdbc.sql("""
                SELECT stop_order,stop_id,boarding_allowed,direction FROM route_stop
                WHERE route_version_id=:version ORDER BY stop_order
                """).param("version", request.routeVersionId())
                .query((row, index) -> new RouteStop(request.routeVersionId(), row.getInt(1), row.getString(2),
                    row.getBoolean(3), RouteDirection.valueOf(row.getString(4))))
                .list();
            AlightingAwareHistoryBootstrap.Progress progress = new AlightingAwareHistoryBootstrap(jdbc).rebuild(
                new RouteStops(request.routeVersionId(), identity.sourceId(), stops, identity.name()), statistics,
                request.sourceOriginBatchId(), request.throughBatchId(), request.batchLimit(),
                request.maximumHistoryRows(), checkpointPath.toString(), request.maximumCheckpoints(), request.maximumSeconds());
            return new BootstrapAlightingAwareHistory.Result(request.routeVersionId(), request.sourceOriginBatchId(),
                request.throughBatchId(),
                progress.checkpoints(), checkpointPath.toString(), bundle.digest(), progress.processedThroughBatchId(), progress.complete());
        } catch (AlightingAwareFeatureUnavailableException failure) {
            String code = failure.getMessage().split(":", 2)[0];
            throw new AlightingAwareHistoryBootstrapException(stableCode(code), failure);
        } catch (IllegalArgumentException failure) {
            throw new AlightingAwareHistoryBootstrapException("ALIGHTING_AWARE_BUNDLE_OR_SCOPE_REJECTED", failure);
        }
    }

    private String stableCode(String code) {
        if (code.matches("ALIGHTING_AWARE_[A-Z_]+")) {
            return code;
        }
        return "ALIGHTING_AWARE_HISTORY_BOOTSTRAP_REJECTED";
    }

    private static String rejectionCode(String code) {
        if (code.matches("ALIGHTING_AWARE_[A-Z_]+")) {
            return code;
        }
        return "ALIGHTING_AWARE_HISTORY_BOOTSTRAP_REJECTED";
    }

    private static AlightingAwareInferenceBundle loadBundle(Path directory) {
        try {
            return AlightingAwareInferenceBundle.loadForStatistics(directory);
        } catch (IOException | RuntimeException failure) {
            throw new AlightingAwareHistoryBootstrapException("ALIGHTING_AWARE_BUNDLE_REJECTED", failure);
        }
    }

    private record RouteIdentity(String sourceId, String name) {
    }
}

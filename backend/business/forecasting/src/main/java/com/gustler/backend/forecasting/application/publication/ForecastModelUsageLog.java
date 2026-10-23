package com.gustler.backend.forecasting.application.publication;

import static com.gustler.backend.diagnostics.Logfmt.quote;

import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 예보를 한 건 이상 저장한 모델과 계산 불가 상태가 바뀔 때만 기록한다.
 * 재시작이나 캐시 퇴출 후에는 다시 기록하므로 배치 성공 횟수나 제외 총횟수가 아니다.
 */
final class ForecastModelUsageLog {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForecastModelUsageLog.class);
    private static final int MAX_ROUTES = 256;
    private final Map<String, State> latest = new LinkedHashMap<>();

    synchronized void committed(
        String routeId,
        String routeName,
        final long routeVersionId,
        final long batchId,
        RuntimeSnapshot runtime
    ) {
        if (changed(routeId, new State(routeVersionId, runtime.deploymentId(), true))) {
            LOGGER.info("event=forecast_model_used routeId={} routeName={} routeVersionId={} batchId={} "
                + "modelDeploymentId={} releaseId={} featureContractVersion={}",
                quote(routeId), quote(routeName), routeVersionId, batchId, runtime.deploymentId(),
                quote(runtime.releaseId()),
                quote(runtime.featureContractVersion()));
        }
    }

    synchronized void referenceMismatch(
        String routeId,
        String routeName,
        final long routeVersionId,
        RuntimeSnapshot runtime
    ) {
        if (changed(routeId, new State(routeVersionId, runtime.deploymentId(), false))) {
            LOGGER.warn("event=forecast_route_skipped reason=ROUTE_REFERENCE_MISMATCH routeId={} routeName={} "
                    + "routeVersionId={} modelDeploymentId={} releaseId={}",
                quote(routeId), quote(routeName), routeVersionId, runtime.deploymentId(), quote(runtime.releaseId()));
        }
    }

    private boolean changed(String routeId, State next) {
        if (next.equals(latest.get(routeId))) {
            return false;
        }
        if (!latest.containsKey(routeId) && latest.size() >= MAX_ROUTES) {
            latest.remove(latest.keySet().iterator().next());
        }
        latest.put(routeId, next);
        return true;
    }

    private record State(long routeVersionId, long deploymentId, boolean committed) {
    }
}

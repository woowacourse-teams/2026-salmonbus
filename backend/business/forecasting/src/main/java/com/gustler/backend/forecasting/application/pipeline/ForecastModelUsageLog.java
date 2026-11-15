package com.gustler.backend.forecasting.application.pipeline;

import static com.gustler.backend.diagnostics.Logfmt.quote;

import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 노선이 어느 모델 배포로 예보되는지, 모델의 기준 정류장이 맞지 않아 예보하지 못하는지를 상태가 바뀔 때만 로그로 남긴다.
 *
 * <p>노선(GBIS 노선 ID)마다 마지막으로 남긴 상태(노선 판본, 모델 배포, 예보했는지)를 기억하고 같은 상태는 다시 남기지 않는다. 다시
 * 뜨거나 기억하는 노선이 {@value #MAX_ROUTES}개를 넘어 밀려난 뒤에는 다시 남기므로, 로그 수는 배치 수나 건너뛴 횟수가 아니다.
 */
final class ForecastModelUsageLog {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForecastModelUsageLog.class);
    private static final int MAX_ROUTES = 256;
    private final Map<String, State> latest = new LinkedHashMap<>();

    /** 예측을 한 건 이상 저장한 배치를 알린다. */
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
                quote(runtime.releaseId()), quote(runtime.featureContractVersion()));
        }
    }

    /** 모델의 기준 정류장과 노선 판본의 정류장이 달라 예보하지 못하는 노선을 알린다. */
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

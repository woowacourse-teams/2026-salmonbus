package com.gustler.backend.worker.scheduling;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.api.model.CheckActiveModelCoverage;
import com.gustler.backend.observations.application.CollectionBatchService;
import com.gustler.backend.observations.application.ObservationCollector;
import com.gustler.backend.worker.configuration.CollectionProperties;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 설정에 적힌 노선을 한 차례 수집한다.
 *
 * <p>한 노선이 실패해도 나머지 노선의 수집을 계속한다. 한 노선 때문에 다른 노선까지
 * 해당 수집을 통째로 건너뛰면 누락 범위가 두 배가 된다.
 */
public class CollectionScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CollectionScheduler.class);

    private final CollectionProperties properties;
    private final ObservationCollector collector;
    private final CollectionBatchService batchService;
    private final CheckActiveModelCoverage modelCoverage;

    public CollectionScheduler(
        CollectionProperties properties,
        ObservationCollector collector,
        CollectionBatchService batchService,
        CheckActiveModelCoverage modelCoverage
    ) {
        this.properties = properties;
        this.collector = collector;
        this.batchService = batchService;
        this.modelCoverage = modelCoverage;
    }

    public void collectAllRoutes() {
        List<String> routeIds = properties.routeIds();
        if (routeIds.isEmpty()) {
            return;
        }

        closeUnfinishedBatchesFromPreviousRound();

        Set<String> forecastRequiredRouteIds = properties.forecastRequiredRouteIds();
        final boolean canCollectForecastRequiredRoutes =
            canCollectForecastRequiredRoutes(routeIds, forecastRequiredRouteIds);

        routeIds.stream()
            .filter(routeId -> canCollectForecastRequiredRoutes || !forecastRequiredRouteIds.contains(routeId))
            .forEach(this::collectRouteSafely);
    }

    private boolean canCollectForecastRequiredRoutes(List<String> routeIds, Set<String> forecastRequiredRouteIds) {
        if (properties.allowPreforecastObservations()) {
            return true;
        }

        final boolean hasNoForecastRequiredRoutes = routeIds.stream().noneMatch(forecastRequiredRouteIds::contains);
        if (hasNoForecastRequiredRoutes) {
            return true;
        }
        return hasActiveModelForAllRoutes(routeIds);
    }

    private boolean hasActiveModelForAllRoutes(List<String> routeIds) {
        try {
            return modelCoverage.coversAll(routeIds);
        } catch (RuntimeException modelCheckFailure) {
            LOGGER.warn(
                    "event=collection_forecast_scope_unavailable exceptionType={}",
                    modelCheckFailure.getClass().getSimpleName()
            );

            return false;
        }
    }

    /**
     * 새 회차가 시작되기 전에 이전 회차의 미완료 기록을 정리한다.
     *
     * <p>별도 트랜잭션의 커밋이 끝난 뒤에 정리 완료를 기록한다.
     * 정리 실패는 계측하고 현재 회차의 수집은 계속한다.
     */
    private void closeUnfinishedBatchesFromPreviousRound() {
        try {
            WorkerOperationLog.run("collection_recovery", "all", () -> {
                List<Long> closedBatchIds = batchService.closePreviousRound();
                if (closedBatchIds.isEmpty()) {
                    return;
                }

                WorkerOperationLog.collectionRecovered(closedBatchIds.size());

                LOGGER.warn(
                        "event=collection_batch_recovery status=CLOSED count={} batchIds={}",
                        closedBatchIds.size(),
                        closedBatchIds.stream()
                            .map(String::valueOf)
                            .collect(Collectors.joining(","))
                );
            });
        } catch (RuntimeException batchCleanupFailure) {
            return;
        }
    }

    /** 계측 경계가 정제된 실패를 기록한 뒤 다음 노선을 수집한다. 예외 원문을 다시 출력하지 않는다. */
    private void collectRouteSafely(String routeId) {
        try {
            WorkerOperationLog.run("collection_route", routeId, () -> collector.collectOnce(routeId));
        } catch (RuntimeException routeCollectionFailure) {
            return;
        }
    }
}

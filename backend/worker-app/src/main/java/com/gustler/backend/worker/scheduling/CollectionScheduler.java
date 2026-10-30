package com.gustler.backend.worker.scheduling;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.observations.api.CollectObservations;
import com.gustler.backend.routeoperations.api.RouteOperation;
import com.gustler.backend.routeoperations.api.RouteOperations;
import com.gustler.backend.worker.configuration.CollectionProperties;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 설정에 적힌 노선을 한 차례 수집한다.
 *
 * <p>한 노선이 실패해도 나머지 노선의 수집을 계속한다. 한 노선 때문에 다른 노선까지
 * 해당 수집을 통째로 건너뛰면 누락 범위가 두 배가 된다.
 */
public class CollectionScheduler {
    private final CollectionProperties properties;
    private final CollectObservations collector;

    private RouteOperations operations;

    public CollectionScheduler(CollectionProperties properties, CollectObservations collector) {
        this.properties = properties;
        this.collector = collector;
    }

    @Autowired(required = false)
    public void setOperations(RouteOperations operations) {
        this.operations = operations;
    }

    public void collectAllRoutes() {
        List<String> routes = collectionRouteIds();
        for (String routeId : routes) {
            if (operations != null) {
                RouteOperation state = operations.required(routeId);
                if (!state.canCollect()) {
                    continue;
                }
                operations.acknowledge(routeId, state.revision());
            }
            collectSafely(routeId);
        }
    }

    private List<String> collectionRouteIds() {
        if (operations == null) {
            return properties.routeIds();
        }
        return operations.list().stream()
            .filter(RouteOperation::canCollect)
            .map(RouteOperation::routeId)
            .toList();
    }

    /**
     * 계측 경계가 정제된 실패를 기록한 뒤 다음 노선으로 넘어간다. 예외 원문을 다시 출력하지 않는다.
     */
    private void collectSafely(String routeId) {
        try {
            WorkerOperationLog.run("collection_route", routeId, () -> collector.collectOnce(routeId));
        } catch (RuntimeException recordedFailure) {
            return;
        }
    }
}

package com.gustler.backend.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 설정에 적힌 노선을 한 바퀴 돈다.
 *
 * <p>한 노선이 터져도 나머지를 계속 돈다. 한 노선 때문에 다른 노선까지 그 판을 통째로 건너뛰면
 * 구멍이 두 배가 된다.
 */
@Component
public class CollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(CollectionScheduler.class);

    private final CollectionProperties properties;
    private final ObservationCollector collector;

    public CollectionScheduler(
        CollectionProperties properties,
        ObservationCollector collector
    ) {
        this.properties = properties;
        this.collector = collector;
    }

    public void collectAllRoutes() {
        for (String routeId : properties.routeIds()) {
            collectSafely(routeId);
        }
    }

    private void collectSafely(
        String routeId
    ) {
        try {
            collector.collectOnce(routeId);
        } catch (final RuntimeException e) {
            log.error("수집 한 판이 실패했다. 다음 노선을 이어서 돈다. 노선={}", routeId, e);
        }
    }
}

package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.api.evaluation.SameDayInitializationPolicy;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.SameDayFullOutcomesStore;
import com.gustler.backend.forecasting.domain.evaluation.SeoulDay;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 정산/품질 변경과 같은 route 잠금을 쓰고, 원본 계산과 저장을 한 트랜잭션으로 닫는다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class SameDayFullOutcomesInitializer {
    private final SameDayFullOutcomesService service;
    private final SameDayFullOutcomesStore repository;
    private final RouteDataQualityAccess quality;
    private final QueryTimeLimits limits;
    private final SameDayInitializationPolicy policy;

    public SameDayFullOutcomesInitializer(SameDayFullOutcomesService service, SameDayFullOutcomesStore repository,
        RouteDataQualityAccess quality, QueryTimeLimits limits, SameDayInitializationPolicy policy) {
        this.service = service;
        this.repository = repository;
        this.quality = quality;
        this.limits = limits;
        this.policy = policy;
    }

    // 원본 SQL 25초에 준비 확인/저장/커밋 여유를 둔다. 노선 목록 조회 제한과는 별개다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public boolean initialize(long routeId, SeoulDay day) {
        return initialize(routeId, day, new SameDayInitializationAttempt());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public boolean initialize(long routeId, SeoulDay day, SameDayInitializationAttempt attempt) {
        attempt.run(SameDayInitializationAttempt.Stage.CONFIGURE, this::configureTimeouts);
        attempt.run(SameDayInitializationAttempt.Stage.LOCK, () -> quality.lockByRoute(routeId));
        // 잠금을 기다리는 동안 준비됐을 수 있으므로 잠금 획득 후 다시 확인한다.
        boolean initialized = service.initializeIfAbsent(routeId, day, attempt);
        attempt.awaitingCommit();
        return initialized;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 2, readOnly = true)
    public List<Long> activeRouteIds() {
        configureTimeouts();
        return repository.findActiveRouteIds();
    }

    private void configureTimeouts() {
        limits.apply(policy.statementTimeout(), policy.lockTimeout());
    }
}

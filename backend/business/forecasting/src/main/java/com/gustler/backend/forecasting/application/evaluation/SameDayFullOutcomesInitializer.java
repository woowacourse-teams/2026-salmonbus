package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.api.evaluation.SameDayInitializationPolicy;
import com.gustler.backend.forecasting.application.evaluation.SameDayInitializationAttempt.Stage;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.evaluation.SameDayFullOutcomesStore;
import com.gustler.backend.forecasting.domain.evaluation.SeoulDay;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 정산/품질 변경과 같은 route 잠금을 쓰고 원본 계산과 저장을 한 트랜잭션에서 처리한다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class SameDayFullOutcomesInitializer {
    private final SameDayFullOutcomesService service;
    private final SameDayFullOutcomesStore repository;
    private final RouteDataQualityAccess quality;
    private final QueryTimeLimits limits;
    private final ModelDeploymentRepository deployments;
    private final SameDayInitializationPolicy policy;

    public SameDayFullOutcomesInitializer(
        SameDayFullOutcomesService service,
        SameDayFullOutcomesStore repository,
        RouteDataQualityAccess quality,
        QueryTimeLimits limits,
        SameDayInitializationPolicy policy,
        ModelDeploymentRepository deployments
    ) {
        this.service = service;
        this.repository = repository;
        this.quality = quality;
        this.limits = limits;
        this.policy = policy;
        this.deployments = deployments;
    }

    // 원본 SQL 25초에 준비 확인/저장/커밋 여유를 둔다. 노선 목록 조회 제한과는 별개다.
    /**
     * 당일 성적 초기화를 제한된 범위에서 진행하고 진행 여부를 반환한다. 품질·배포 판본과 시간대를 함께 적용한다.
     */
    @Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public boolean initialize(final long routeId, SeoulDay day) {
        return initialize(routeId, day, new SameDayInitializationAttempt());
    }

    /**
     * 당일 성적 초기화를 제한된 범위에서 진행하고 진행 여부를 반환한다. 품질·배포 판본과 시간대를 함께 적용한다.
     */
    @Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public boolean initialize(final long routeId, SeoulDay day, SameDayInitializationAttempt attempt) {
        attempt.run(Stage.CONFIGURE, this::configureTimeouts);
        attempt.run(Stage.LOCK, () -> quality.lockByRoute(routeId));
        // 잠금을 기다리는 동안 준비됐을 수 있으므로 잠금 획득 후 다시 확인한다.
        Optional<ActiveModelDeployment> deployment = deployments.findActive();
        final boolean initialized = deployment.isPresent()
            && service.initializeIfAbsent(routeId, deployment.get().id(), day, attempt);
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

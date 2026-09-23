package com.gustler.backend.forecasting.application.model;

import com.gustler.backend.forecasting.domain.model.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.model.ActiveModelSlot;
import com.gustler.backend.forecasting.domain.model.ModelActivation;
import com.gustler.backend.forecasting.domain.model.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.model.ModelIdentity;
import com.gustler.backend.forecasting.domain.model.StagedModelDeployment;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** 파일 검증이 끝난 뒤 활성 모델 선택과 요청 기록만 같은 트랜잭션에서 변경한다. */
public class TransactionalModelActivation {
    private final ModelDeploymentRepository deployments;
    private final Clock clock;

    public TransactionalModelActivation(ModelDeploymentRepository deployments, Clock clock) {
        this.deployments = deployments;
        this.clock = clock;
    }

    @Transactional
    public ModelActivation activate(UUID requestId, long expectedVersion, ModelIdentity identity) {
        ActiveModelSlot slot = deployments.lockActiveSlot();
        var previousRequest = deployments.findActivation(requestId);
        if (previousRequest.isPresent()) {
            previousRequest.get().requireSameRequest(expectedVersion, identity);
            return previousRequest.get();
        }
        slot.requireVersion(expectedVersion);
        final ActiveModelDeployment target;
        final long resultingVersion;
        if (slot.contains(identity)) {
            target = slot.deployment().orElseThrow();
            resultingVersion = slot.version();
        } else {
            long deploymentId = deployments.stage(new StagedModelDeployment(
                UUID.randomUUID(), identity.releaseId(), identity.modelKey(), identity.modelVersion(),
                identity.bundleDigest(), identity.predictionTargetVersion(), identity.calculationVersion(),
                identity.supportedScopeDigest(), identity.dataUntil()));
            target = new ActiveModelDeployment(deploymentId, identity);
            resultingVersion = slot.activate(expectedVersion, target).version();
            deployments.activate(deploymentId, slot, resultingVersion);
        }
        ModelActivation result = new ModelActivation(requestId, expectedVersion, target,
            resultingVersion, clock.instant().truncatedTo(ChronoUnit.MICROS));
        deployments.recordActivation(result);
        return result;
    }
}
